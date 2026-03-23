package io.github.imba_tjd.audio_share_app.service

import android.content.Context
import ashipo.jopus.OPUS_OK
import ashipo.jopus.Opus
import io.github.imba_tjd.audio_share_app.R
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MockFileClient(
    private val context: Context,
    private val onReceiveError: (e: String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + CoroutineName("MockFileClient"))
    private var job: Job? = null

    // 模拟数据回调
    var onPcmData: ((data: FloatArray) -> Unit)? = null
    var onOpusData: ((data: ByteArray)-> Unit)? = null

    // 每次读取的字节数
    private val FRAME_SIZE = 3840
    private val FRAME_INTERVAL_MS = 10L

    fun start() {
        stop() // 确保之前的任务已停止
        job = scope.launch {
            try {
                // 打开 res/raw/capture.pcm
                context.resources.openRawResource(R.raw.capture).use { inputStream ->
                    runLoop(inputStream)
                }
            } catch (e: Exception) {
                onReceiveError("Mock Client Error: ${e.message}")
            }
        }
    }

    private suspend fun runLoop(inputStream: InputStream) {
        val buffer = ByteArray(FRAME_SIZE)

        while (scope.isActive) {
            val readCount = withContext(Dispatchers.IO) {
                inputStream.read(buffer)
            }

            if (readCount == -1) {
                // 文件读完了，可以选择循环播放或者停止
                // 如果要循环播放：inputStream.reset() (前提是支持mark)
                // 或者重新 openRawResource
                break
            }

            // 如果读到的数据不足 FRAME_SIZE（文件末尾），只发送实际读到的部分
            val data = if (readCount == FRAME_SIZE) {
                buffer.copyOf()
            } else {
                buffer.copyOfRange(0, readCount)
            }

            val floatArray = FloatArray(FRAME_SIZE / 4)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floatArray)

            // 触发回调
            onPcmData?.invoke(floatArray)

//            delay(FRAME_INTERVAL_MS)
        }
    }

    fun startOpus() {
        stop()
        job = scope.launch {
            try {
                context.resources.openRawResource(R.raw.opus).use { inputStream ->
                    runLoopOpus(inputStream)
                }
            } catch (e: Exception) {
                onReceiveError("Mock Client Error: ${e.toString()}")
            }
        }
    }

    private suspend fun runLoopOpus(inputStream: InputStream) = withContext(Dispatchers.IO) {
        val dataInputStream = DataInputStream(inputStream)

        while (true) {
            scope.ensureActive()

            val byte1 = dataInputStream.read()
            if (byte1 == -1) break
            val byte2 = dataInputStream.read()
            if (byte2 == -1) break

            val length = (byte1 and 0xFF) or ((byte2 and 0xFF) shl 8)
            if (length <= 0) continue

            val buffer = ByteArray(length)
            dataInputStream.readFully(buffer)

            onOpusData?.invoke(buffer)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }


}