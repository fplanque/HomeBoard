package com.evofactory.homeboard

import android.app.Application

/** Starts the log first, for the launcher and the Home button helper alike */
class HomeBoardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.start(this)
        ScreenState.watch(this)
    }
}
