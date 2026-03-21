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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    fun clear() {
        lock.withLock {
            buffer.clear()
            expectedSeq = -1
            preBuffering = true
        }
    }

    private fun isSeqOlder(seq: Int, expected: Int): Boolean {
        val diff = (seq.toShort() - expected.toShort()).toShort()
        return diff < 0
    }
}

class NetClient(private val onMessage: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("NetClient"))
    val jitterBuffer = JitterBuffer()

    private var selector: SelectorManager? = null
    private var controlSocket: BoundDatagramSocket? = null
    private var dataSocket: BoundDatagramSocket? = null

    // 用于发送 FIN / PIN 的远端地址
    private var serverBusinessAddr: InetSocketAddress? = null

    @Volatile
    private var running = false

    data class ServerInfo(
        val address: InetSocketAddress,
        val dataPort: Int,
        val useOpus: Boolean,
        val opusSkip: Int
    )

    suspend fun connect(): ServerInfo = withContext(Dispatchers.IO) {
        stop() // 确保清理旧连接
        running = true
        val sel = SelectorManager(Dispatchers.IO).also { selector = it }

        try {
            // 1. 发现服务器 (8888)
            val info = discoverServer(sel)
            onMessage("Found Server: ${info.address.hostname}")

            // 2. 建立业务通道 (依据服务端下发的动态端口)
            val targetAddr = InetSocketAddress(info.address.hostname, info.dataPort)
            serverBusinessAddr = targetAddr

            setupChannels(sel, targetAddr)

            // 3. 启动后台循环
            startHeartbeatLoop(targetAddr)
            startDataLoop()

            return@withContext info
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    suspend fun stop() {
        if (!running) return
        running = false

        try {
            controlSocket?.let { sock ->
                serverBusinessAddr?.let { addr ->
                    repeat(3) {
                        sock.send(Datagram(ByteReadPacket("FIN".encodeToByteArray()), addr))
                        val resp = sock.receive()
                        if (resp.packet.readText() == "END")
                            return
                    }
                }
            }
        } catch (e: Exception) {
            // FIN/END 过程中的超时或网络断开可以安全忽略
        } finally {
            controlSocket?.dispose()
            dataSocket?.dispose()
            withContext(Dispatchers.IO) {
                selector?.close()
            }

            selector = null
            controlSocket = null
            dataSocket = null
            serverBusinessAddr = null

            // 取消内部的所有循环子协程，准备接受下一次 connect
            scope.coroutineContext.cancelChildren()
            jitterBuffer.clear()
        }
    }

    private suspend fun discoverServer(sel: SelectorManager): ServerInfo {
        val sock = aSocket(sel).udp().bind { broadcast = true }
        val broadcastAddr = InetSocketAddress("255.255.255.255", 8888)

        try {
            repeat(5) {
                sock.send(Datagram(ByteReadPacket("PROBE".encodeToByteArray()), broadcastAddr))
                val result = withTimeoutOrNull(1000) { sock.receive() }
                if (result != null) {
                    val text = result.packet.readText()
                    return parseBootMessage(text).copy(address = result.address as InetSocketAddress)
                }
            }
        } finally {
            sock.dispose()
        }
        throw Exception("未找到音频服务器 (8888)")
    }

    private suspend fun setupChannels(sel: SelectorManager, server: InetSocketAddress) {
        val dSock = aSocket(sel).udp().bind().also { dataSocket = it }
        val cSock = aSocket(sel).udp().bind().also { controlSocket = it }

        val dataPort = dSock.localAddress.port()
        val msg = "SYN:$dataPort".encodeToByteArray()

        repeat(3) {
            cSock.send(Datagram(ByteReadPacket(msg), server))
            val resp = withTimeoutOrNull(2000) { cSock.receive() }
            if (resp != null && resp.packet.readText() == "ACK") return
        }
        throw Exception("握手失败 (7777)")
    }

    private fun startHeartbeatLoop(server: InetSocketAddress) = scope.launch {
        // 本来这里有一个receive PON，更新最后收到回复的时间，再在pin里检查是否超时的逻辑的。但是因为stop时要接收END，就无法在此处接收了
        while (running) {
            delay(1000)
            try {
                controlSocket?.send(Datagram(ByteReadPacket("PIN".encodeToByteArray()), server))
            } catch (e: Exception) {
                Log.i("NetClient PIN", "failed")
            }
        }
    }

    private fun startDataLoop() = scope.launch {
        val sock = dataSocket ?: return@launch
        try {
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
        } catch (e: Exception) {
            // 接收异常（如socket关闭）时自然退出循环
        }
    }

    private fun parseBootMessage(text: String): ServerInfo {
        var dataPort = 7777 // 默认 fallback
        var useOpus = false
        var opusSkip = 0

        // 动态解析指令，未知的直接忽略
        text.lines().forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                when (key) {
                    "UDP" -> dataPort = value.toIntOrNull() ?: 7777
                    "OPUS" -> {
                        useOpus = true
                        opusSkip = value.toIntOrNull() ?: 0
                    }
                }
            }
        }
        return ServerInfo(InetSocketAddress("0.0.0.0", 0), dataPort, useOpus, opusSkip)
    }
}
