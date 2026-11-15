package com.wildenglish.app.audiobook.playback

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用更新后在后台重新拉起常驻配音服务（首页 Activity 不参与）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            VoicePrepCenter.init(context.applicationContext as Application)
            VoicePrepCenter.onAppStart()
        }
    }
}
