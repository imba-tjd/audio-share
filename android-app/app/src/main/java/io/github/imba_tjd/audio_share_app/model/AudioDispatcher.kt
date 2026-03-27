package io.github.imba_tjd.audio_share_app.model

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import android.os.Process

val AudioDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread({
        // 设置 Android 系统级别的音频优先级
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        runnable.run()
    }, "AudioLoopThread")
}.asCoroutineDispatcher()
