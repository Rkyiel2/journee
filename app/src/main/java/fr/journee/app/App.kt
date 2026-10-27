package fr.journee.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // A crash or reboot in the middle of a sync must not leave the widget saying "updating…".
        Store.setSyncing(this, false)
        if (Store.hasAccount(this)) Sync.schedule(this)
    }
}
