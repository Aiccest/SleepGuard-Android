package com.sleepmate.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ProtectionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            ProtectionService.restoreIfEnabled(context)
        } catch (e: Exception) {
            // Android may restrict starting a camera foreground service from boot.
            // The saved enabled state remains true; MainActivity retries on resume.
            Log.w("SleepMate", "Boot recovery deferred: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
