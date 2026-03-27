package io.github.imba_tjd.audio_share_app.service

import android.util.Log
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.CancellationException
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class TcpClient: NetClient {
    private val TAG = "TcpClient"
    private var socket: Socket? = null
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private val scope = CoroutineScope(Dispatchers.IO + CoroutineName("TcpClient"))

    override var onError: ((String) -> Unit)? = null

    var onData: ((ByteBuffer) -> Unit)? = null

    suspend fun connect(addr: InetSocketAddress): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Connecting to $addr.host:$addr.port...")
            val conn = aSocket(selectorManager).tcp().connect(addr)
            socket = conn

            writeChannel = conn.openWriteChannel(autoFlush = true)
            readChannel = conn.openReadChannel()

            writeChannel?.writeFully("SYN".toByteArray())

            Log.i(TAG, "TCP Connected and SYN sent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}")
            false
        }
    }

    suspend fun predrain() {
        val buf = ByteBuffer.allocate(1920)

        repeat(3) {
            readChannel?.readFully(buf)
            buf.clear()
        }
    }

    fun startDataLoop() = scope.launch {
        val reader = readChannel ?: return@launch

        // 一次读取5ms
        val bufSizeInBytes = 1920
        val buf = ByteBuffer.allocateDirect(bufSizeInBytes)

        try {
            while (isActive && !socket!!.isClosed) {
                buf.clear()
                reader.readFully(buf)
                buf.flip()

                onData?.invoke(buf)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "Read loop error: ${e.message}")
                onError?.invoke(e.toString())
            }
        } finally {
            closeInternal()
        }
    }

    override suspend fun stop() {
        scope.cancel()
    }

    private suspend fun closeInternal() {
        withContext(NonCancellable) {
            try {
                writeChannel?.let {
                    if (!it.isClosedForWrite) {
                        it.writeFully("FIN".toByteArray(Charsets.UTF_8))
                        it.flush()
                    }
                }
                socket?.close()
                socket = null
            } catch (e: Exception) {
                Log.e(TAG, "Error during close: ${e.message}")
            }
        }
    }
}