package io.github.imba_tjd.audio_share_app.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.imba_tjd.audio_share_app.MainActivity

class PlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "PlaybackService"
        const val ACTION_STOP_SERVICE = "action_stop_service"
    }

    private var mediaSession: MediaSession? = null

    private val customCommandStop = SessionCommand(ACTION_STOP_SERVICE, Bundle.EMPTY)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        val player = AudioPlayer(applicationContext)

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setMediaButtonPreferences(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_STOP)
                        .setSessionCommand(customCommandStop)
                        .setDisplayName("Stop")
                        .setEnabled(true)
                        .build()
                )
            )
            .setCallback(MediaSessionCallback())
            .build()
            .apply {
                this.player.addListener(object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        TileService.requestListeningState(
                            applicationContext,
                            ComponentName(applicationContext, QsTileService::class.java)
                        )
                    }
                })
            }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    @OptIn(UnstableApi::class)
    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.d(TAG, "onConnect ${controller.packageName} ${controller.connectionHints}")
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(customCommandStop)
                        .build()
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_STOP_SERVICE) {
                Log.d(TAG, "ACTION_STOP_SERVICE")
                // 这里调用 stop 会触发 AudioPlayer.handleStop()
                session.player.stop()
                stopSelf()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            Log.d(TAG, "onDisconnected")
            super.onDisconnected(session, controller)
        }

//        override fun onPlaybackResumption(
//            mediaSession: MediaSession,
//            controller: MediaSession.ControllerInfo
//        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
//            Log.d(TAG, "onPlaybackResumption")
//            return super.onPlaybackResumption(mediaSession, controller)
//        }
    }
}