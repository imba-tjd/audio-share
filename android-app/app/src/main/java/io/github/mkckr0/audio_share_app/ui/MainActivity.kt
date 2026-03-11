/*
 *    Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.mkckr0.audio_share_app.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.os.bundleOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.model.AppSettingsKeys
import io.github.mkckr0.audio_share_app.model.Asset
import io.github.mkckr0.audio_share_app.model.Channel
import io.github.mkckr0.audio_share_app.model.appSettingsDataStore
import io.github.mkckr0.audio_share_app.model.getBoolean
import io.github.mkckr0.audio_share_app.service.PlaybackService
import io.github.mkckr0.audio_share_app.ui.screen.MainScreen
import io.github.mkckr0.audio_share_app.ui.theme.AppTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val tag = MainActivity::class.simpleName

    private lateinit var _mediaControllerFuture: ListenableFuture<MediaController>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen()
            }
        }

        // create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                Channel.UPDATE.id,
                Channel.UPDATE.title,
                Channel.UPDATE.importance
            )
            notificationManager.createNotificationChannel(channel)
        }

        // request permissions
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
