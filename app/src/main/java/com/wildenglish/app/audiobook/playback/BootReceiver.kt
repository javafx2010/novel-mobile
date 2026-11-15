package com.wildenglish.app.audiobook.playback

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the resident dubbing service after a device reboot ("常驻"). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            VoicePrepCenter.init(context.applicationContext as Application)
            VoicePrepCenter.onAppStart()
        }
    }
}
