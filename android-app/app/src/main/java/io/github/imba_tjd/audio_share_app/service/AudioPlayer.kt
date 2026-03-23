package io.github.imba_tjd.audio_share_app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player.Commands
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.datastore.core.use
import ashipo.jopus.OPUS_OK
import ashipo.jopus.Opus
import com.google.common.util.concurrent.Futures.immediateVoidFuture
import com.google.common.util.concurrent.ListenableFuture
import io.github.imba_tjd.audio_share_app.R
import io.github.imba_tjd.audio_share_app.model.AudioConfigKeys
import io.github.imba_tjd.audio_share_app.model.ServerInfo
import io.github.imba_tjd.audio_share_app.model.audioConfigDataStore
import io.github.imba_tjd.audio_share_app.model.getFloat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.time.LocalTime
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
class AudioPlayer(val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val tag = AudioPlayer::class.simpleName

    private var _initState: State = State.Builder()
        .setAvailableCommands(
            Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_STOP,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_METADATA,
                    COMMAND_RELEASE,
                )
                .build()
        ).setPlaylist( // 必须提供一个 Dummy MediaItem，否则在 BUFFERING 或 READY 状态时报错 "Empty playlist only allowed in STATE_IDLE..."
            listOf(
                MediaItemData.Builder("dummy_uid")
                    .setMediaItem(MediaItem.Builder().setMediaId("dummy_uid").build())
                    .build()
            )
        )
        .build()

    // 状态与框架回调。当本代码修改了_state后，要调用invalidateState()，框架就会获取当前状态
    private var _state: State = _initState
    override fun getState(): State = _state

    private val netClient = NetClient { msg ->
        message = msg
    }

    private var _audioTrack: AudioTrack? = null
    private val audioTrack get() = _audioTrack!!

    private var _loudnessEnhancer: LoudnessEnhancer? = null

    private val scope: CoroutineScope = MainScope()

    // 唯一负责控制生命周期的 Job
    private var playJob: Job? = null

    companion object {
        private var _message by mutableStateOf("")
        var message: String
            get() = _message
            set(v) {
                val now = LocalTime.now()
                _message += "[${now.minute}:${now.second}] ${v}\n"
            }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        _state = _state.buildUpon()
            .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        // 这里不需要调用 invalidateState()，因为当前正处于 Media3 的回调周期内。当本函数完成后，框架会自动获取一次状态

        return CallbackToFutureAdapter.getFuture { completer ->
            playJob?.cancel()
            if (playWhenReady) {
                playJob = scope.launch {
                    try {
                        startInternal()
                    } finally {
                        // 确保即使协程被取消，也能优雅执行清理
                        withContext(NonCancellable) {
                            stopAllInternal()
                        }
                    }
                }
            } else {
                scope.launch {
                    stopAllInternal()
                    updatePlaybackState(STATE_IDLE, false)
                    message = context.getString(R.string.label_paused)
                }
            }
            completer.set(Unit)
        }
    }

    private suspend fun startInternal() {
        _message = ""

        val serverInfo = ServerInfo.fromDataStore(context.applicationContext)

        while (currentCoroutineContext().isActive) {
            try {
                updatePlaybackState(STATE_BUFFERING, true)

                // 1. 挂起直到连接成功 ()
                message = context.getString(R.string.label_connecting)
                netClient.connect(serverInfo)

                // 2. 更新 UI 为 Ready
                message = context.getString(R.string.label_started)
                updatePlaybackState(STATE_READY, true)

                // 3. 阻塞式执行拉取与播放，直到异常或主动取消
                runAudioLoop()

            } catch (e: CancellationException) {
                throw e // 让外层 playJob 正确取消
            } catch (e: Exception) {
                Log.e(tag, "Playback error", e)

                // 出现异常先关闭旧连接，清理声卡，随后重试
                stopAllInternal()
                val reason = e.message ?: "未知网络错误"

                for (wait in 3 downTo 1) {
                    message = "$reason, ${context.getString(R.string.label_retry).format(wait)}"
                    delay(1.seconds)
                }
            }
        }
    }

    private fun updatePlaybackState(playbackState: Int, playWhenReady: Boolean) {
        _state = state.buildUpon()
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        invalidateState()
    }

    override fun handleStop(): ListenableFuture<*> {
        playJob?.cancel()
        scope.launch {
            stopAllInternal()
            _state = _initState.buildUpon().setPlaybackState(STATE_IDLE).build()
            invalidateState()
            message = context.getString(R.string.label_stopped)
        }
        return immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        playJob?.cancel()
        scope.launch {
            stopAllInternal()
            scope.cancel()
        }
        return immediateVoidFuture()
    }

    // 统筹释放资源（NetClient 的 stop 包含挂起等待 END 的逻辑）
    private suspend fun stopAllInternal() {
        netClient.stop()
        _loudnessEnhancer?.release()
        _loudnessEnhancer = null
        _audioTrack?.run {
            try { pause(); flush(); release() } catch (e: Exception) {}
        }
        _audioTrack = null
    }

    // 核心拉取数据的循环协程
    private suspend fun runAudioLoop() = withContext(Dispatchers.IO) {
        initAudioTrack() // Ensure this is set to AudioFormat.ENCODING_PCM_FLOAT

        fun opus_native() {
            val opus = Opus()
            val initResult = opus.initDecoder(48000, 2)
            if (initResult != OPUS_OK) {
                println("Opus Init Error: ${opus.getErrorString(initResult)}")
                return
            }

            val framesPerChannel = 480
            val channels = 2
            val pcmBuffer = FloatArray(framesPerChannel * channels)

            try {
                while (isActive) {
                    // Jitter buffer returns payload, or null if a packet was lost/delayed
                    val payload = netClient.jitterBuffer.pull()

                    val framesDecoded = payload?.let { pl ->
                        opus.decodeFloat(
                            payload,              // encodedData (null for PLC)
                            pl.size,         // encodedBytes (0 for PLC)
                            pcmBuffer,            // outputBuffer
                            framesPerChannel,     // outputBufferFrames
                            1                     // fec (0 unless your payload has in-band FEC)
                        )
                    } ?:
                    opus.plcFloat(pcmBuffer, framesPerChannel)

                    if (framesDecoded > 0) {
                        val totalSamples = framesDecoded * channels

                        // AudioTrack WRITE_BLOCKING acts as our real-time clock.
                        // Because Opus PLC always outputs audio even when payload is null,
                        // this write guarantees the timing loop never spins out of control.
                        audioTrack.write(pcmBuffer, 0, totalSamples, AudioTrack.WRITE_BLOCKING)
                    } else if (framesDecoded < 0) {
                        message = "Opus Decode Error: ${opus.getErrorString(framesDecoded)}"
                    }
                }
            } finally {
                opus.releaseDecoder()
            }
        }

        fun opus_android() {
            OpusDecoderAndroid().use { opus ->
                opus.init()

                val outBuffer = FloatArray(480 * 2)
                val silenceBuffer = ByteArray(480 * 2 * 4)

                while (isActive) {
                    val payload = netClient.jitterBuffer.pull()
                    if (payload != null) {
                        val decoded_num = opus.decode(payload, outBuffer)
                        audioTrack.write(outBuffer, 0, decoded_num, AudioTrack.WRITE_BLOCKING)
                    } else {
                        audioTrack.write(silenceBuffer, 0, silenceBuffer.size, AudioTrack.WRITE_BLOCKING)
                    }
                }
            }
        }

//        val opus = OpusDecoderAndroid()
//        opus.init(0)
//        val outBuffer = FloatArray(480 * 2)
//
//        netClient.OnData = { data ->
//            val decoded_num = opus.decode(data, outBuffer)
//            audioTrack.write(outBuffer, 0, decoded_num, AudioTrack.WRITE_BLOCKING)
//        }

        val opus = Opus()
        val initResult = opus.initDecoder(48000, 2)
        if (initResult != OPUS_OK) {
            throw RuntimeException("Opus init fail")
        }

        val framesPerChannel = 480
        val channels = 2
        val outBuffer = FloatArray(framesPerChannel * channels)

        netClient.OnData = { data ->
//            val num = opus.decodeFloat(
//                data,              // encodedData (null for PLC)
//                data.size,         // encodedBytes (0 for PLC)
//                outBuffer,            // outputBuffer
//                framesPerChannel,     // outputBufferFrames
//                1                     // fec (0 unless your payload has in-band FEC)
//            )
//            audioTrack.write(outBuffer, 0, num, AudioTrack.WRITE_BLOCKING)

            audioTrack.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)

        }

        while(isActive) {
            delay(1000)
        }

//        opus_android()
//        opus_native()
    }

    // 初始化硬件音频层
    private suspend fun initAudioTrack() {
        val encoding = AudioFormat.ENCODING_PCM_FLOAT
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val sampleRate = 48000

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        Log.i(tag, "min buffer size: $minBufferSize bytes")

        val audioConfig = context.audioConfigDataStore.data.first()
        val bufferScale = (audioConfig[floatPreferencesKey(AudioConfigKeys.BUFFER_SCALE)]
            ?: context.getFloat(R.string.default_buffer_scale)).toInt()
        Log.i(tag, "buffer scale: $bufferScale")

        _audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setChannelMask(channelMask)
                    .setSampleRate(sampleRate)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * bufferScale)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val volume = audioConfig[floatPreferencesKey(AudioConfigKeys.VOLUME)]
            ?: context.getFloat(R.string.default_volume)
        audioTrack.setVolume(volume)

        val loudnessEnhancerGain = (audioConfig[floatPreferencesKey(AudioConfigKeys.LOUDNESS_ENHANCER)]
            ?: context.getFloat(R.string.default_loudness_enhancer)).toInt()

        if (loudnessEnhancerGain > 0) {
            _loudnessEnhancer = LoudnessEnhancer(audioTrack.audioSessionId).apply {
                setTargetGain(loudnessEnhancerGain)
                enabled = true
            }
        }

        audioTrack.play()
    }
}
