package com.example

import android.app.Application
import com.example.data.engine.ExecutionEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PaperPulseApplication : Application() {
    @Inject
    lateinit var executionEngine: ExecutionEngine

    override fun onCreate() {
        super.onCreate()
        executionEngine.start()
    }
}
