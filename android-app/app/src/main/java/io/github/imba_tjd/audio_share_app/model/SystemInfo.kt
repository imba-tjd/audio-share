package io.github.imba_tjd.audio_share_app.model

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager

data class SystemInfo(
    val hasLowLatencyFeature: Boolean,
    val hasProFeature: Boolean,
    val sampleRate: String?,
    val framesPerBuffer: String?
) {
    private constructor(context: Context, am: AudioManager): this(
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY),
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO),
        am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE),
        am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER),
    )

    constructor(context: Context): this(context, context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
}
