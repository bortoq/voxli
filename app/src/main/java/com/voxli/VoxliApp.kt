package com.voxli

import android.app.Application
import com.voxli.di.initKoin

class VoxliApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(this)
    }
}
