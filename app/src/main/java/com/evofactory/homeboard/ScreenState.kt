package com.evofactory.homeboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

private const val TAG = "Screen"

/**
 * Whether the TV is interactive: screen on, or the screensaver showing. It turns false as soon as the TV
 * starts going to sleep. When the screensaver ends for standby, the launcher shows again for a moment,
 * and without this all its cameras started and stopped again within 5 s (Kitchen TV, 2026-11-06 19:23).
 */
object ScreenState {
    var interactive by mutableStateOf(true)
        private set

    private var watching = false

    /** Follows Android's screen on and off broadcasts, once per process */
    fun watch(context: Context) {
        val app = context.applicationContext
        refresh(app)
        if (watching) return
        watching = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = set(intent.action == Intent.ACTION_SCREEN_ON)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Only Android sends these
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Asks Android now: a broadcast can come after the launcher has already shown again */
    fun refresh(context: Context) = set(context.getSystemService(PowerManager::class.java).isInteractive)

    private fun set(value: Boolean) {
        if (value == interactive) return
        AppLog.i(TAG, if (value) "TV interactive again: cameras may start" else "TV going to sleep: cameras stop and don't start")
        interactive = value
    }
}
