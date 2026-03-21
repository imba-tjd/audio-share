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
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player.Commands
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.Futures.immediateVoidFuture
import com.google.common.util.concurrent.ListenableFuture
import io.github.imba_tjd.audio_share_app.R
import io.github.imba_tjd.audio_share_app.model.AudioConfigKeys
import io.github.imba_tjd.audio_share_app.model.NetworkConfigKeys
import io.github.imba_tjd.audio_share_app.model.audioConfigDataStore
import io.github.imba_tjd.audio_share_app.model.getFloat
import io.github.imba_tjd.audio_share_app.model.getInteger
import io.github.imba_tjd.audio_share_app.model.getResourceUri
import io.github.imba_tjd.audio_share_app.model.networkConfigDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
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
        )
        .build()

    private var _state: State = _initState
    override fun getState(): State = _state

    private val netClientCallback = NetClientCallBack()
    private val netClient = NetClient(netClientCallback)

    private var _audioTrack: AudioTrack? = null
    private val audioTrack get() = _audioTrack!!

    private var _loudnessEnhancer: LoudnessEnhancer? = null

    private val scope: CoroutineScope = MainScope()
    private val retryScope: CoroutineScope = MainScope()

    // 用于控制音频播放循环的协程
    private var audioLoopJob: Job? = null
    private var playJob: Job? = null

    companion object {
        private var _message by mutableStateOf("")

        var message: String
            get() = _message
            set(v) { _message += "${v}\n" }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        _message = context.getString(R.string.label_connecting) + "\n"

        return CallbackToFutureAdapter.getFuture { completer ->
            playJob?.cancel()
            playJob = scope.launch {
                try {
                    Log.d(tag, "handleSetPlayWhenReady playWhenReady=$playWhenReady")
                    _state = state.buildUpon().setPlayerError(null).build()
                    invalidateState()
                    if (playWhenReady) {
                        val networkConfig = context.networkConfigDataStore.data.first()
                        if (!isActive) return@launch

                        val host = networkConfig[stringPreferencesKey(NetworkConfigKeys.HOST)]
                            ?: context.getString(R.string.default_host)
                        val port = networkConfig[intPreferencesKey(NetworkConfigKeys.PORT)]
                            ?: context.getInteger(R.integer.default_port)

                        val mediaItem = MediaItem.fromUri("tcp://$host:$port").buildUpon()
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Audio Share")
                                    .setArtist("$host:$port")
                                    .setArtworkUri(context.getResourceUri(R.drawable.artwork))
                                    .build()
                            )
                            .build()

                        _state = state.buildUpon()
                            .setPlaylist(
                                listOf(
                                    MediaItemData.Builder("media-1")
                                        .setMediaItem(mediaItem)
                                        .build()
                                )
                            )
                            .setCurrentMediaItemIndex(0)
                            .setPlaybackState(STATE_BUFFERING)
                            .setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                            .build()
                        invalidateState()

                        netClient.start()
                    } else {
                        stopAll()
                        retryScope.coroutineContext.cancelChildren()
                        _state = state.buildUpon()
                            .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                            .build()
                        invalidateState()
                        message = context.getString(R.string.label_paused)
                    }
                    completer.set(Unit)
                } catch (e: Exception) {
                    Log.e(tag, "handleSetPlayWhenReady", e)
                    completer.setException(e)
                }
            }
        }
    }

    override fun handleStop(): ListenableFuture<*> {
        Log.d(tag, "handleStop")
        playJob?.cancel()
        stopAll()
        retryScope.coroutineContext.cancelChildren()
        _state = _initState.buildUpon()
            .setPlaybackState(STATE_IDLE)
            .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        invalidateState()
        message = context.getString(R.string.label_stopped)
        return immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        Log.d(tag, "handleRelease")
        playJob?.cancel()
        stopAll()
        scope.cancel()
        retryScope.cancel()
        netClientCallback.cbscope.cancel()
        _state = State.Builder().build()
        return immediateVoidFuture()
    }

    private fun stopAll() {
        netClient.stop()
        audioLoopJob?.cancel()
        retryScope.coroutineContext.cancelChildren()

        _loudnessEnhancer?.release()
        _loudnessEnhancer = null

        _audioTrack?.run {
            pause()
            flush()
            release()
        }
        _audioTrack = null
    }

    // 初始化硬件音频层
    private suspend fun initAudioTrack() {
        val encoding = AudioFormat.ENCODING_PCM_32BIT // 根据未解码Opus的假设，先用PCM32
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

    // 核心拉取数据的循环协程
    private fun startAudioLoop() {
        audioLoopJob?.cancel()
        audioLoopJob = scope.launch(Dispatchers.IO) {
            initAudioTrack()

            // 预设一段静音数据（默认 480 帧，后续会自动适应包大小）
            var silenceBuffer = ByteArray(480 * 2 * 4)

            while (isActive) {
                val payload = netClient.jitterBuffer.pull()

                if (payload != null) {
                    // 动态调整静音数组的大小以匹配正常的 payload 长度
                    if (silenceBuffer.size != payload.size) {
                        silenceBuffer = ByteArray(payload.size)
                    }
                    // 阻塞式写入，由底层声卡控制消费速度
                    audioTrack.write(payload, 0, payload.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    // Jitter Buffer 没有就绪数据（缓冲中或丢包），填充静音以保持声卡时钟
                    audioTrack.write(silenceBuffer, 0, silenceBuffer.size, AudioTrack.WRITE_BLOCKING)
                }
            }
        }
    }

    inner class NetClientCallBack : NetClient.Callback {
        override val cbscope: CoroutineScope = MainScope() + CoroutineName("NetClientCallbackScope")

        override suspend fun onServerFound(ip: String, meta: String) {
            message = "Found Server: ${ip}"
        }

        override suspend fun onConnected() {
            // 连接成功，由 AudioPlayer 启动播放线程拉取数据
            startAudioLoop()

            _state = state.buildUpon()
                .setPlaybackState(STATE_READY)
                .build()
            invalidateState()
            Log.d(tag, "onPlaybackStarted")
            message = context.getString(R.string.label_started)
        }

        override suspend fun onError(msg: String, e: Throwable?) {
            if (e is CancellationException) return

            // switch to retryScope to prevent NetClient cancel callback scope
            retryScope.coroutineContext.cancelChildren()
            retryScope.launch {
                netClient.stop()

                val reason = e?.message ?: msg
                var wait = 3
                while (wait > 0) {
                    message = "$reason, ${context.getString(R.string.label_retry).format(wait)}"
                    delay(1.seconds)
                    --wait
                }

                _state = state.buildUpon()
                    .setPlayerError(null)
                    .setPlaybackState(STATE_BUFFERING)
                    .build()
                invalidateState()

                netClient.start()
            }
        }
    }
}
