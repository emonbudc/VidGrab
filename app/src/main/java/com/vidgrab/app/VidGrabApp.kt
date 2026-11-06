package com.vidgrab.app

import android.app.Application
import com.vidgrab.app.data.HistoryStore
import com.vidgrab.app.engine.Engine

class VidGrabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HistoryStore.load(this)
        Engine.start(this)
    }
}
