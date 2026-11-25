package com.sleepguard.app

import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log

class SleepGuardNotificationListener : NotificationListenerService() {
    private lateinit var mediaSessionManager: MediaSessionManager
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            watchControllers(controllers.orEmpty())
            evaluatePlayback(controllers.orEmpty())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            val component = ComponentName(this, SleepGuardNotificationListener::class.java)
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, component)
            val controllers = mediaSessionManager.getActiveSessions(component)
            watchControllers(controllers)
            evaluatePlayback(controllers)
        } catch (_: SecurityException) {
            ProtectionService.stop(this)
        }
    }

    private fun watchControllers(controllers: List<MediaController>) {
        callbacks.forEach { (controller, callback) ->
            try { controller.unregisterCallback(callback) } catch (_: Exception) { }
        }
        callbacks.clear()
        controllers.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    evaluatePlayback(currentControllers())
                }
            }
            try {
                controller.registerCallback(callback)
                callbacks[controller] = callback
            } catch (_: Exception) { }
        }
    }

    private fun currentControllers(): List<MediaController> =
        try {
            mediaSessionManager.getActiveSessions(
                ComponentName(this, SleepGuardNotificationListener::class.java)
            )
        } catch (_: Exception) {
            emptyList()
        }

    private fun evaluatePlayback(controllers: List<MediaController>) {
        val selected = MediaAppPreferences.getSelectedPackages(this)
        val playing = selected.isNotEmpty() && controllers.any { controller ->
            controller.packageName in selected &&
                (controller.playbackState?.state == PlaybackState.STATE_PLAYING ||
                 controller.playbackState?.state == PlaybackState.STATE_BUFFERING)
        }
        if (playing) {
            val intent = Intent(this, ProtectionService::class.java)
                .setAction(ProtectionService.ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            } catch (_: Exception) {
                Log.w("SleepGuard", "Unable to start protection service")
            }
        } else {
            ProtectionService.stop(this)
        }
    }

    override fun onListenerDisconnected() {
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) { }
        callbacks.forEach { (controller, callback) ->
            try { controller.unregisterCallback(callback) } catch (_: Exception) { }
        }
        callbacks.clear()
        ProtectionService.stop(this)
        super.onListenerDisconnected()
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        evaluatePlayback(currentControllers())
    }

    override fun onDestroy() {
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) { }
        callbacks.forEach { (controller, callback) ->
            try { controller.unregisterCallback(callback) } catch (_: Exception) { }
        }
        callbacks.clear()
        super.onDestroy()
    }
}
