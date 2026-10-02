package com.fimall.googleloc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fimall.googleloc.data.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        App.enqueueWrite { service ->
            val prefs = service.getRemotePreferences(Prefs.FILE)
            if (!prefs.getBoolean(Prefs.KEY_ENABLED, false)) {
                prefs.edit().putBoolean(Prefs.KEY_ENABLED, true).apply()
            }
        }
    }
}
