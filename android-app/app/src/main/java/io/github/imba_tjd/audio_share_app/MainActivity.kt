package io.github.imba_tjd.audio_share_app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.concurrent.futures.await
import androidx.core.os.bundleOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.imba_tjd.audio_share_app.model.AppSettingsKeys
import io.github.imba_tjd.audio_share_app.model.Channel
import io.github.imba_tjd.audio_share_app.model.appSettingsDataStore
import io.github.imba_tjd.audio_share_app.model.getBoolean
import io.github.imba_tjd.audio_share_app.service.PlaybackService
import io.github.imba_tjd.audio_share_app.ui.screen.MainScreen
import io.github.imba_tjd.audio_share_app.ui.theme.AppTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var _mediaControllerFuture: ListenableFuture<MediaController>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        // create MediaController
        val sessionToken =
            SessionToken(this, ComponentName(this, PlaybackService::class.java))
        _mediaControllerFuture = MediaController.Builder(this, sessionToken)
            .setConnectionHints(bundleOf("src" to "MainActivity"))
            .buildAsync()

        // auto start playback
        MainScope().launch {
            val appSettings = appSettingsDataStore.data.first()
            val autoStart = appSettings[booleanPreferencesKey(AppSettingsKeys.START_PLAYBACK_WHEN_APP_START)] ?: getBoolean(
                R.bool.default_start_playback_when_app_start)

            if (autoStart) {
                awaitMediaController().play()
            }
        }
    }

    override fun onDestroy() {
        MediaController.releaseFuture(_mediaControllerFuture)

        super.onDestroy()
    }

    suspend fun awaitMediaController(): MediaController {
        return _mediaControllerFuture.await()
    }

}