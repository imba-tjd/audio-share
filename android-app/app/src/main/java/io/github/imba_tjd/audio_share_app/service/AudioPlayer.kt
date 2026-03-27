package io.github.imba_tjd.audio_share_app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioTrack.WRITE_BLOCKING
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.media3.common.MediaItem
import androidx.media3.common.Player.Commands
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import ashipo.jopus.OPUS_OK
import ashipo.jopus.Opus
import com.google.common.util.concurrent.Futures.immediateVoidFuture
import com.google.common.util.concurrent.ListenableFuture
import io.github.imba_tjd.audio_share_app.R
import io.github.imba_tjd.audio_share_app.model.AudioConfigKeys
import io.github.imba_tjd.audio_share_app.model.AudioDispatcher
import io.github.imba_tjd.audio_share_app.model.NetworkConfigKeys
import io.github.imba_tjd.audio_share_app.model.ServerInfo
import io.github.imba_tjd.audio_share_app.model.audioConfigDataStore
import io.github.imba_tjd.audio_share_app.model.getFloat
import io.github.imba_tjd.audio_share_app.model.networkConfigDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalTime
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

    private var netClient: NetClient? = null

    private var _audioTrack: AudioTrack? = null
    private val audioTrack get() = _audioTrack!!

    private var _loudnessEnhancer: LoudnessEnhancer? = null
    private var _equalizer: Equalizer? = null

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

        while (currentCoroutineContext().isActive) {
            try {
                updatePlaybackState(STATE_BUFFERING, true)

                // 1. 先初始化AT，而非先进行连接，减小服务端开始发送到客户端开始消费之间的延迟
                initAudioTrack()

                // 2. 提前读取Loop所需数据
                val serverInfo = ServerInfo.fromDataStore(context.applicationContext)

                // 3. 挂起直到连接成功 ()
                message = context.getString(R.string.label_connecting)
                netClient = setupConnection()

                // 4. 更新 UI 为 Ready
                scope.launch {
                    message = context.getString(R.string.label_started)
                    updatePlaybackState(STATE_READY, true)
                }

                // 5. 阻塞式执行拉取与播放，直到异常或主动取消
                runAudioLoop(serverInfo)

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
        playJob?.cancel() // 取消此任务会在playinternal的finally里执行stopAllInternal
        scope.launch {
//            stopAllInternal()
            _state = _initState.buildUpon().setPlaybackState(STATE_IDLE).build()
            invalidateState()
            message = context.getString(R.string.label_stopped)
        }
        return immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        playJob?.cancel()
//        scope.launch {
//            stopAllInternal()
//        }
        scope.cancel()
        return immediateVoidFuture()
    }

    // 统筹释放资源（NetClient 的 stop 包含挂起等待 END 的逻辑）
    private suspend fun stopAllInternal() {
        netClient?.stop()
        _loudnessEnhancer?.release()
        _loudnessEnhancer = null
        _equalizer?.release()
        _equalizer = null
        _audioTrack?.run {
            try { pause(); flush(); release() } catch (e: Exception) {}
        }
        _audioTrack = null
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
        if (volume != 1.0f)
            audioTrack.setVolume(volume)

        val loudnessEnhancerGain = (audioConfig[floatPreferencesKey(AudioConfigKeys.LOUDNESS_ENHANCER)]
            ?: context.getFloat(R.string.default_loudness_enhancer)).toInt()

        if (loudnessEnhancerGain > 0) {
            _loudnessEnhancer = LoudnessEnhancer(audioTrack.audioSessionId).apply {
                setTargetGain(loudnessEnhancerGain)
                enabled = true
            }
        }

        val eq_choose_ndx = audioConfig[intPreferencesKey(AudioConfigKeys.EQ_CHOOSE_NDX)] ?: 0
        if (eq_choose_ndx > 0) {
            _equalizer = Equalizer(0, audioSessionId).apply {
                usePreset(eq_choose_ndx.toShort())
                enabled = true
            }
        }

        audioTrack.play()
    }

    // 核心拉取数据的循环协程
    private suspend fun runAudioLoop(info: ServerInfo) = withContext(AudioDispatcher) {
        when(info.proto) {
            "UDP" -> {
                val client = netClient as UdpClient
                val use_jb = context.networkConfigDataStore.data.first()[booleanPreferencesKey(NetworkConfigKeys.USE_JB)] ?: false
                if (use_jb)
                    UdpOpusJb(client)
                else
                    UdpOpusCb(client)
            }
            "ADB" -> AdbLoop(netClient as AdbClient)
            "TCP" -> TcpLoop(netClient as TcpClient)
            else -> throw RuntimeException("Unknown proto")
        }
    }

    private suspend fun UdpPcmCb(client: UdpClient) {
        client.onData = { data ->
            val floatdata = FloatArray(data.size / 4)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floatdata)
            audioTrack.write(floatdata, 0, floatdata.size, WRITE_BLOCKING)
        }

        while(currentCoroutineContext().isActive) {
            delay(1000)
        }
    }

    private suspend fun mockFilePcmCb() {
        val client = MockFileClient(context) { message = it }
        client.onPcmData = { data ->
            audioTrack.write(data, 0, data.size, WRITE_BLOCKING)
        }

        client.start()

        while(currentCoroutineContext().isActive) {
            delay(1000)
        }
    }

    private suspend fun mockFileOpusCb() {
        val client = MockFileClient(context) { message = it }

        val opus = Opus()
        val initResult = opus.initDecoder(48000, 2)
        if (initResult != OPUS_OK) {
            throw RuntimeException("Opus init fail")
        }

        val outBuffer = FloatArray(480 * 2)

        client.onOpusData = { data->
            opus.decodeFloat(
                data,              // encodedData (null for PLC)
                data.size,         // encodedBytes (0 for PLC)
                outBuffer,            // outputBuffer
                480,     // outputBufferFrames
                0                     // fec (0 unless your payload has in-band FEC)
            )
            audioTrack.write(outBuffer, 0, outBuffer.size, WRITE_BLOCKING)
        }

        client.startOpus()

        while(currentCoroutineContext().isActive) {
            delay(1000)
        }
    }

    private suspend fun UdpOpusCb(client: UdpClient) {
        val opus = Opus()
        val initResult = opus.initDecoder(48000, 2)
        if (initResult != OPUS_OK) {
            throw RuntimeException("Opus init fail")
        }

        val framesPerChannel = 480
        val channels = 2
        val outBuffer = FloatArray(framesPerChannel * channels)

        client.onData = { data ->
            opus.decodeFloat(
                data,
                data.size,
                outBuffer,
                framesPerChannel,
                0
            )
            audioTrack.write(outBuffer, 0, outBuffer.size, WRITE_BLOCKING)
        }

        while(currentCoroutineContext().isActive) {
            delay(1000)
        }
    }

    private fun UdpOpusJb(client: UdpClient) {
        val opus = Opus()
        val initResult = opus.initDecoder(48000, 2)
        if (initResult != OPUS_OK) {
            println("Opus Init Error: ${opus.getErrorString(initResult)}")
            return
        }

        val framesPerChannel = 480
        val channels = 2
        val outBuffer = FloatArray(framesPerChannel * channels)

        try {
            while (playJob!!.isActive) {
                when (val result = client.jitterBuffer.pull()) {
                    is JitterBuffer.PullResult.Normal -> {
                        // 1. 正常解码当前包
                        val decodedFrames = opus.decodeFloat(
                            result.payload, result.payload.size, outBuffer, 480, 0
                        )
                        if (decodedFrames > 0) {
                            audioTrack.write(outBuffer, 0, decodedFrames * 2, WRITE_BLOCKING)
                        }
                    }

                    is JitterBuffer.PullResult.Lost -> {
                        if (result.nextPayloadForFec != null) {
                            // 2. 尝试用 N+1 包中的 FEC 数据来恢复丢失的 N 包
                            // 注意：这里的 fec 参数传 1
                            val decodedFrames = opus.decodeFloat(
                                result.nextPayloadForFec, result.nextPayloadForFec.size, outBuffer, framesPerChannel, 1
                            )
                            if (decodedFrames > 0) {
                                audioTrack.write(outBuffer, 0, decodedFrames * 2, WRITE_BLOCKING)
                            } else {
                                // FEC 解码失败（可能因为当时没开启FEC），退化为 PLC
                                val plcFrames = opus.plcFloat(outBuffer, framesPerChannel)
                                audioTrack.write(outBuffer, 0, plcFrames * 2, WRITE_BLOCKING)
                            }
                        } else {
                            // 3. 下一个包也没来，神仙难救，直接 PLC 脑补
                            val plcFrames = opus.plcFloat(outBuffer, framesPerChannel)
                            audioTrack.write(outBuffer, 0, plcFrames * 2, WRITE_BLOCKING)
                        }
                    }

                    is JitterBuffer.PullResult.Underrun -> {
                        // 4. 网络严重卡顿，没数据了，用 PLC 填充防止爆音，直到预缓冲重新完成
                        val plcFrames = opus.plcFloat(outBuffer, framesPerChannel)
                        audioTrack.write(outBuffer, 0, plcFrames * 2, WRITE_BLOCKING)
                    }
                }
            }
        } finally {
            opus.releaseDecoder()
        }
    }

    private suspend fun AdbLoop(client: AdbClient) {
        client.predrain()
        while(currentCoroutineContext().isActive) {
            val data = client.read()
            audioTrack.write(data, client.bufByteSize, WRITE_BLOCKING)
        }
    }

    private suspend fun TcpLoop(client: TcpClient) {
        client.onData = { data ->
            audioTrack.write(data, 1920, WRITE_BLOCKING)
        }
        client.predrain()
        client.startDataLoop()

        while(currentCoroutineContext().isActive) {
            delay(1000)
        }
    }

    private suspend fun setupConnection(): NetClient {
        val serverInfo = ServerInfo.fromDataStore(context.applicationContext)

        val client = when(serverInfo.proto) {
            "ADB" -> AdbClient().apply { connect() }
            "UDP" -> UdpClient().apply { connect(serverInfo) }
            "TCP" -> TcpClient().apply { connect(serverInfo.address) }
            else -> throw RuntimeException("Unknown proto")
        }
        client.onError = { message = it }

        return client
    }
}
