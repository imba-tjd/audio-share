package io.github.imba_tjd.audio_share_app.service

import io.github.imba_tjd.audio_share_app.model.ServerInfo
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.ByteReadPacket
import io.ktor.utils.io.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class DiscoverClient {
    private var selector: SelectorManager = SelectorManager(Dispatchers.IO)

    private val broadcastAddr = InetSocketAddress("255.255.255.255", 8888)

    suspend fun probe(): Result<ServerInfo> = withContext(Dispatchers.IO) {
        aSocket(selector).udp().bind { broadcast = true }.use { sock ->
            try {
                repeat(5) {
                    sock.send(Datagram(ByteReadPacket("PROBE".encodeToByteArray()), broadcastAddr))
                    val result = withTimeoutOrNull(1000) { sock.receive() }
                    if (result != null) {
                        val text = result.packet.readText()
                        val info = parseBootMessage(text)

                        val addr = result.address as InetSocketAddress
                        val realaddr = InetSocketAddress(addr.hostname, info.address.port)
                        realaddr.hostname

                        return@withContext Result.success(info.copy(address = realaddr))
                    }
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }
        return@withContext Result.failure(Exception("Probe no response"))
    }

    private fun parseBootMessage(text: String): ServerInfo {
        var proto = "UDP"
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
                    "TCP" -> {
                        proto = "TCP"
                        dataPort = value.toIntOrNull() ?: 7777
                    }
                    "UDP" -> {
                        proto = "UDP"
                        dataPort = value.toIntOrNull() ?: 7777
                    }
                    "OPUS" -> {
                        useOpus = true
                        opusSkip = value.toIntOrNull() ?: 0
                    }
                }
            }
        }
        return ServerInfo(
            address = InetSocketAddress("0.0.0.0", dataPort),
            proto = proto,
            useOpus = useOpus,
            opusSkip = opusSkip
        )
    }
}