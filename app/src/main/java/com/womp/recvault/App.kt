package com.womp.recvault

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notif.channels(this)
        SyncWorker.schedulePeriodic(this)
    }
}
