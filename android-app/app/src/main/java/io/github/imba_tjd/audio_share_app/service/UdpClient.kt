package io.github.imba_tjd.audio_share_app.service

import android.util.Log
import io.github.imba_tjd.audio_share_app.model.ServerInfo
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.CancellationException
import io.ktor.utils.io.core.ByteReadPacket
import io.ktor.utils.io.core.remaining
import io.ktor.utils.io.readText
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.io.readShortLe
import kotlinx.io.readUShortLe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// 数据包实体
class UdpAudioPacket(
    val seq: Int,
    val payload: ByteArray
)
// 需要timestamp的场合：
// 1. Adaptive Jitter Buffers（根据网络环境调整minDepth）
// 2. Variable Frame Sizes
// 3. A/V Sync (Lip Sync)

// 抖动缓冲区实现
class JitterBuffer(
    private val maxDepth: Int = 10,
    private val minDepth: Int = 3 //  pre-roll or initial buffering
) {
    private val buffer = TreeMap<Int, UdpAudioPacket>()
    private val lock = ReentrantLock()
    private var expectedSeq: Int = -1
    private var preBuffering = true

    fun put(packet: UdpAudioPacket) {
        lock.withLock {
            if (!preBuffering && expectedSeq != -1 && isSeqOlder(packet.seq, expectedSeq)) {
                return // Drop late packets
            }
            buffer[packet.seq] = packet

            // Limit latency depth
            while (buffer.size > maxDepth) {
                buffer.pollFirstEntry()
            }
        }
    }

    fun pull(): ByteArray? {
        lock.withLock {
            if (preBuffering) {
                if (buffer.size < minDepth) return null
                preBuffering = false
                expectedSeq = buffer.firstKey()
            }

            // Buffer Underrun (Network lag or disconnect)
            if (buffer.isEmpty()) {
                preBuffering = true // re-enter buffering phase
                return null
            }

            // Packet Loss Detection
            val firstKey = buffer.firstKey()
            if (isSeqOlder(expectedSeq, firstKey)) {
                // expectedSeq is missing!
                // We increment expectedSeq and return null.
                // This explicitly triggers exactly one frame of PLC in the decoder.
                expectedSeq = (expectedSeq + 1) and 0xFFFF
                return null
            }

            // 4. Normal Delivery
            val packet = buffer.remove(expectedSeq)
            expectedSeq = (expectedSeq + 1) and 0xFFFF
            return packet?.payload
        }
    }

    fun clear() {
        lock.withLock {
            buffer.clear()
            expectedSeq = -1
            preBuffering = true
        }
    }

    private fun isSeqOlder(seq: Int, expected: Int): Boolean {
        // handle 16-bit sequence wrap-around
        val diff = (seq.toShort() - expected.toShort()).toShort()
        return diff < 0
    }
}

class UdpClient(val onReceiveError: (e: String) -> Unit) {

    private val scope = CoroutineScope(Dispatchers.IO + CoroutineName("NetClient"))

    val jitterBuffer = JitterBuffer()

    private var selector: SelectorManager? = null
    private var socket: BoundDatagramSocket? = null
    private var serverAddr: SocketAddress? = null

    var OnData: ((data: ByteArray) -> Unit)? = null

    suspend fun connect(info: ServerInfo) = withContext(Dispatchers.IO) {
        stop() // 确保清理旧连接
        val sel = SelectorManager(Dispatchers.IO).also { selector = it }

        try {
            setupChannels(sel, info.address)
            startDataLoop()
            startHeartbeatLoop()
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    suspend fun stop() {
        scope.coroutineContext.cancelChildren()

        try {
            socket?.let { sock ->
                serverAddr?.let { addr ->
                    repeat(3) {
                        sock.send(Datagram(ByteReadPacket("FIN".encodeToByteArray()), addr))
                        val resp = withTimeoutOrNull(1000) { sock.receive() }
                        if (resp != null && resp.packet.readText() == "END")
                            return
                    }
                }
            }
        } catch (e: Exception) {
            // FIN/END 过程中的超时或网络断开可以安全忽略
        } finally {
            socket?.dispose()
            withContext(Dispatchers.IO) {
                selector?.close()
            }

            selector = null
            socket = null
            serverAddr = null

            jitterBuffer.clear()
        }
    }

    private suspend fun setupChannels(sel: SelectorManager, server: InetSocketAddress) {
        serverAddr = server
        val so = aSocket(sel).udp().bind().also { socket = it}
        val msg = "SYN".encodeToByteArray()

        repeat(3) {
            so.send(Datagram(ByteReadPacket(msg), server))
            val resp = withTimeoutOrNull(1000) { so.receive() }
            if (resp != null && resp.packet.readText() == "ACK") return
        }
        throw Exception("握手失败")
    }

    private fun startHeartbeatLoop() = scope.launch {
        val ping = "PIN".encodeToByteArray()

        while (isActive) {
            delay(1000)
            try {
                serverAddr?.let { socket?.send(Datagram(ByteReadPacket(ping), it)) }
            } catch (e: Exception) {
                Log.i("NetClient PIN", "failed")
            }
        }
    }

    private fun startDataLoop() = scope.launch {
        val sock = socket ?: return@launch
        var errorCnt = 0

        while (isActive) {
            try {
                val datagram = sock.receive()
                val packet = datagram.packet
                val availableBytes = packet.remaining

                if (availableBytes <= 4) {
                    handleMeta(packet.readByteArray())
                    continue
                }

                packet.readShortLe() // meta padding
                val seq = packet.readUShortLe().toInt() and 0xFFFF

                val payload = packet.readByteArray()

                OnData?.invoke(payload) ?: jitterBuffer.put(UdpAudioPacket(seq, payload))
            }
            catch (e: Exception) {
                if (e is CancellationException) throw e

                onReceiveError("Receive Error" + e.toString())
                if (errorCnt++ > 10) {
                   throw e
                }
            }
        }
    }

    private fun handleMeta(buf: ByteArray) {
        val cmd = String(buf, Charsets.UTF_8)
        when(cmd) {
            "PON" -> ""
        }
    }
}
