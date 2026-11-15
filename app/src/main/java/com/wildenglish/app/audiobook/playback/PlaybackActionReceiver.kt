package com.wildenglish.app.audiobook.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the lock-screen notification's 上一句 / 播放暂停 / 下一句 and forwards them to the app. */
class PlaybackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlaybackSession.onAction(intent.action)
    }
}
