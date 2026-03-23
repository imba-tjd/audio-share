package io.github.imba_tjd.audio_share_app.service

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.media.AudioFormat
import android.os.Build
import androidx.datastore.core.Closeable

class OpusDecoderAndroid(val sampleRate: Int = 48000, val channels: Int = 2) : Closeable {
    private var decoder: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val TIMEOUT_US = 5000L // Reduced timeout (5ms) for real-time responsiveness
    private var ptsUs = 0L

    fun init(preSkip: Int = 120) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)

        // 1. Force Float32 output natively (Requires API 24+)
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)

        // 2. Enable Android Low Latency mode (Requires API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        // csd-0 (Opus Identification Header)
        val csd0 = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        csd0.put("OpusHead".toByteArray(Charsets.UTF_8))
        csd0.put(1.toByte()) // version
        csd0.put(channels.toByte())
        csd0.putShort(preSkip.toShort())
        csd0.putInt(sampleRate)
        csd0.putShort(0.toShort()) // gain
        csd0.put(0.toByte()) // mapping family
        csd0.flip()
        format.setByteBuffer("csd-0", csd0)

        // csd-1 (Pre-skip in nanoseconds)
        val preSkipNs = (preSkip * 1_000_000_000L / sampleRate)
        val csd1 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        csd1.putLong(preSkipNs)
        csd1.flip()
        format.setByteBuffer("csd-1", csd1)

        // 3. csd-2 (Seek Pre-roll in nanoseconds) - REQUIRED by Android for Opus
        val csd2 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        csd2.putLong(0L)
        csd2.flip()
        format.setByteBuffer("csd-2", csd2)

        decoder.configure(format, null, null, 0)
        decoder.start()
    }

    /**
     * @param input Encoded Opus byte array
     * @param outBuffer Pre-allocated FloatArray to prevent GC allocation latency
     * @return Number of floats written to outBuffer
     */
    fun decode(input: ByteArray, outBuffer: FloatArray): Int {
        var floatsWritten = 0

        // 1. Queue Input
        val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            val buffer = decoder.getInputBuffer(inputIndex)
            buffer?.clear()
            buffer?.put(input)

            // Increment PTS to prevent decoders from dropping "duplicate" frames
            decoder.queueInputBuffer(inputIndex, 0, input.size, ptsUs, 0)
            ptsUs += 20000L // Approximate 20ms per frame
        }

        // 2. Drain Output continuously until empty (Fixes the Massive Latency issue)
        val info = MediaCodec.BufferInfo()
        while (true) {
            // Use a 0 timeout after the first successful drain to prevent thread-blocking
            val outputIndex = decoder.dequeueOutputBuffer(info, if (floatsWritten == 0) TIMEOUT_US else 0L)

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    break // Codec is completely drained
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Decoder format configured (usually fires on the very first frame)
                    // If you want strictly safe code, check decoder.outputFormat here
                    // to verify KEY_PCM_ENCODING == ENCODING_PCM_FLOAT
                }
                outputIndex >= 0 -> {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0) {
                        val floatBuffer = outputBuffer.asFloatBuffer()
                        val availableFloats = floatBuffer.remaining()

                        // Prevent out-of-bounds crash if outBuffer array is too small
                        val toRead = minOf(availableFloats, maxOf(0, outBuffer.size - floatsWritten))
                        floatBuffer.get(outBuffer, floatsWritten, toRead)
                        floatsWritten += toRead
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
        return floatsWritten
    }

    override fun close() {
        decoder.stop()
        decoder.release()
    }
}