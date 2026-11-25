package com.example

import android.app.Application
import com.example.data.alerts.PriceAlertRepository
import com.example.data.engine.ExecutionEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PaperPulseApplication : Application() {
    @Inject
    lateinit var executionEngine: ExecutionEngine

    @Inject
    lateinit var priceAlertRepository: PriceAlertRepository

    override fun onCreate() {
        super.onCreate()
        executionEngine.start()
        priceAlertRepository.startMonitoring()
    }
}
