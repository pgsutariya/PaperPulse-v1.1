package com.example.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.TradingPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TradeNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsRepository: TradingPreferencesRepository
) {
    private val nextId = AtomicInteger(1000)

    init {
        ensureChannels()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val tradeChannel = NotificationChannel(
                CHANNEL_TRADE_ACTIVITY,
                "Trade Activity & Executions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Order fills, pending order updates, position averaging, SL/TSL/Target hits, and auto square-off."
            }
            val signalChannel = NotificationChannel(
                CHANNEL_SIGNALS,
                "Trade Signals & Targets",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "New strategy signals, BTST/STBT setups, and signal Target/SL alerts."
            }
            manager.createNotificationChannel(tradeChannel)
            manager.createNotificationChannel(signalChannel)
        }
    }

    fun hasSystemPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun notifyTradeActivity(title: String, body: String) {
        val config = runCatching { prefsRepository.getConfig() }.getOrNull() ?: return
        if (!config.notificationsEnabled) return
        post(CHANNEL_TRADE_ACTIVITY, title, body, NotificationCompat.PRIORITY_HIGH)
    }

    suspend fun notifySignalEvent(title: String, body: String) {
        val config = runCatching { prefsRepository.getConfig() }.getOrNull() ?: return
        if (!config.signalNotificationsEnabled) return
        post(CHANNEL_SIGNALS, title, body, NotificationCompat.PRIORITY_DEFAULT)
    }

    private fun post(channelId: String, title: String, body: String, priority: Int) {
        if (!hasSystemPermission()) return
        runCatching {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(priority)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            NotificationManagerCompat.from(context).notify(nextId.incrementAndGet(), notification)
        }
    }

    companion object {
        const val CHANNEL_TRADE_ACTIVITY = "paperpulse_trade_activity"
        const val CHANNEL_SIGNALS = "paperpulse_signals"
    }
}
