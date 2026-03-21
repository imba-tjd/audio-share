package io.github.imba_tjd.audio_share_app.service

import android.util.Log
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.port
import io.ktor.utils.io.core.ByteReadPacket
import io.ktor.utils.io.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.TimeSource

// 数据包实体
class UdpAudioPacket(
    val seq: Int,
    val timestamp: Long,
    val payload: ByteArray
)

// 抖动缓冲区实现
class JitterBuffer(
    private val maxDepth: Int = 20,
    private val minDepth: Int = 5
) {
    private val buffer = TreeMap<Int, UdpAudioPacket>()
    private val lock = ReentrantLock()
    private var expectedSeq: Int = -1
    private var preBuffering = true

    fun put(packet: UdpAudioPacket) {
        lock.withLock {
            if (!preBuffering && expectedSeq != -1 && isSeqOlder(packet.seq, expectedSeq)) {
                return
            }
            buffer[packet.seq] = packet
            while (buffer.size > maxDepth) {
                buffer.pollFirstEntry()
            }
        }
    }

    fun pull(): ByteArray? {
        lock.withLock {
            if (buffer.isEmpty()) return null
            if (preBuffering) {
                if (buffer.size < minDepth) return null
                preBuffering = false
                expectedSeq = buffer.firstKey()
            }
            val packet = buffer.remove(expectedSeq)
            expectedSeq = (expectedSeq + 1) and 0xFFFF
            return packet?.payload
        }
    }

    private fun isSeqOlder(seq: Int, expected: Int): Boolean {
        val diff = (seq.toShort() - expected.toShort()).toShort()
        return diff < 0
    }
}

class NetClient(cb: Callback) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("NetClient"))
    val jitterBuffer = JitterBuffer()

    interface Callback {
        val cbscope: CoroutineScope
        suspend fun onServerFound(ip: String, meta: String)
        suspend fun onConnected()
        suspend fun onError(msg: String, e: Throwable?)
    }

    private var callback: Callback = cb
    private lateinit var selector: SelectorManager
    private var controlSocket: BoundDatagramSocket? = null
    private var dataSocket: BoundDatagramSocket? = null
    private var serverAddr7777: InetSocketAddress? = null // 业务地址 (7777)
    private var running = false

    fun start() {
        running = true
        selector = SelectorManager(Dispatchers.IO)

        scope.launch {
            try {
                // 1. 发现阶段 (Port: 8888)
                val discoveryInfo = discoverServer()

                // 获取 IP 字符串用于回调和构造新地址
                val ipString = discoveryInfo.address.hostname

                // 2. 构造业务地址 (强制切换到 7777)
                serverAddr7777 = InetSocketAddress(ipString, 7777)

                callback.launch {
                    onServerFound(ipString, "UDP:7777")
                }

                // 3. 连接阶段 (指向 7777)
                setupChannels(serverAddr7777!!)

                startControlLoopLaunch()
                startDataLoopLaunch()

                callback.launch { onConnected() }

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                callback.launch { onError(e.message ?: "error", e) }
                stop()
            }
        }
    }

    fun stop() {
        running = false
        scope.coroutineContext.cancelChildren()
        controlSocket?.dispose()
        dataSocket?.dispose()
        if (::selector.isInitialized) selector.close()
    }

    private data class ServerInfo(
        val address: InetSocketAddress,
        val useOpus: Boolean,
        val opusSkip: Int
    )

    private suspend fun discoverServer(): ServerInfo {
        // 使用随机本地端口发送广播，目标为 8888
        val sock = aSocket(selector).udp().bind { broadcast = true }
        val broadcastAddr = InetSocketAddress("255.255.255.255", 8888)

        repeat(5) {
            val probe = "PROBE".encodeToByteArray()
            sock.send(Datagram(ByteReadPacket(probe), broadcastAddr))

            val result = withTimeoutOrNull(1000) { sock.receive() }
            if (result != null) {
                val sender = result.address as InetSocketAddress
                val text = result.packet.readText()
                sock.close() // 发现后关闭临时 socket
                return parseBootMessage(text).copy(address = sender)
            }
        }
        sock.dispose()
        throw Exception("No server found on port 8888")
    }

    private fun parseBootMessage(text: String): ServerInfo {
        val lines = text.split('\n')
        var useOpus = false
        var opusSkip = 0
        for (line in lines) {
            if (line.startsWith("OPUS:")) {
                useOpus = true
                opusSkip = line.substringAfter("OPUS:").toIntOrNull() ?: 0
            }
        }
        return ServerInfo(InetSocketAddress("0.0.0.0", 0), useOpus, opusSkip)
    }

    private suspend fun setupChannels(server: InetSocketAddress) {
        // 创建本地数据端口和控制端口
        dataSocket = aSocket(selector).udp().bind()
        controlSocket = aSocket(selector).udp().bind()

        val dataPort = dataSocket!!.localAddress.port()
        val msg = "SYN:$dataPort".encodeToByteArray()

        repeat(3) {
            controlSocket!!.send(Datagram(ByteReadPacket(msg), server))
            val resp = withTimeoutOrNull(2_000) { controlSocket!!.receive() }
            if (resp != null && resp.packet.readText() == "ACK") return
        }

        throw Exception("Handshake failed on 7777")
    }

    private fun startControlLoopLaunch() = scope.launch {
        val server = serverAddr7777 ?: return@launch
        var lastPong = TimeSource.Monotonic.markNow()

        // 监听控制响应 (PON)
        launch {
            while (running) {
                val pkt = controlSocket!!.receive()
                if (pkt.packet.readText() == "PON") {
                    lastPong = TimeSource.Monotonic.markNow()
                }
            }
        }

        // 发送心跳 (PIN) 到 7777
        while (running) {
            delay(1_000)
            try {
                controlSocket?.send(Datagram(ByteReadPacket("PIN".encodeToByteArray()), server))
            } catch (e: Exception) {
                Log.i("NetClient PIN", "failed")
            }

//            if (TimeSource.Monotonic.markNow() - lastPong > 6.seconds) {
//                throw Exception("Heartbeat timeout")
            // TODO: 这里会导致程序崩溃
//            }
        }
    }

    private fun startDataLoopLaunch() = scope.launch {
        val sock = dataSocket ?: return@launch
        while (running) {
            val datagram = sock.receive()
            val rawBytes = datagram.packet.readByteArray()
            if (rawBytes.size <= 8) continue

            val buf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.short // padding
            val seq = buf.short.toInt() and 0xFFFF
            val timestamp = buf.int.toLong() and 0xFFFFFFFFL

            val payload = ByteArray(rawBytes.size - 8)
            buf.get(payload)
            jitterBuffer.put(UdpAudioPacket(seq, timestamp, payload))
        }
    }

    private fun Callback.launch(block: suspend Callback.() -> Unit) =
        cbscope.launch { block() }
}
