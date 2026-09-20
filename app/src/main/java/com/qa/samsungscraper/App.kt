package com.qa.samsungscraper

import android.app.Application
import com.qa.samsungscraper.store.CaptureStore
import com.qa.samsungscraper.util.Util

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CaptureStore.init(this)
        Util.ensureChannel(this)
    }
}
