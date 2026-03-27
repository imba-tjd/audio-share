package io.github.imba_tjd.audio_share_app.service

import android.net.LocalSocket
import android.net.LocalSocketAddress
import io.ktor.utils.io.core.readFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.OutputStream
import java.nio.ByteBuffer

class AdbClient: NetClient {
    private val addr = LocalSocketAddress("audio_share")
    private val x = LocalSocket()

    private var ins: Source? = null
    private var os: OutputStream? = null

    override var onError: ((String) -> Unit)? = null


    suspend fun connect() = withContext(Dispatchers.IO) {
        x.connect(addr)
        ins = x.inputStream.asSource().buffered()
        os = x.outputStream

        os!!.write(syn)
    }


    private val syn = "SYN".encodeToByteArray()
    private val fin = "FIN".encodeToByteArray()

    override suspend fun stop() = withContext(Dispatchers.IO) {
        os!!.write(fin)
        x.close()
    }

    val bufByteSize = 1920

    private val buf = ByteBuffer.allocateDirect(bufByteSize)

    fun read(): ByteBuffer {
        val src = ins ?: throw IllegalStateException("InputStream is null")

        buf.clear() // 重置 position 和 limit，且变回写模式
        src.readFully(buf)
        buf.flip() // 允许被读取

        return buf
    }

    fun predrain() {
        val buf = ByteArray(bufByteSize)

        repeat(3) {
            ins?.readFully(buf)
        }
    }
}
