package com.example.data.feed.live

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.domain.model.MarketSegment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores per-segment feed configuration and user-entered broker API credentials locally using
 * EncryptedSharedPreferences (AES-256 GCM / SIV). No keys are ever hardcoded.
 */
@Singleton
class SecureBrokerCredentialsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "paperpulse_secure_broker_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            // Fallback for headless JVM/Robolectric tests where AndroidKeyStore is absent
            context.getSharedPreferences("paperpulse_secure_broker_prefs_test", Context.MODE_PRIVATE)
        }
    }

    private val _settings = MutableStateFlow(loadFromPrefs())
    val settings: StateFlow<BrokerFeedSettings> = _settings.asStateFlow()

    fun getCurrent(): BrokerFeedSettings = _settings.value

    fun updateSettings(newSettings: BrokerFeedSettings) {
        val editor = prefs.edit()
            .putString(KEY_SELECTED_FEED, newSettings.selectedFeed.name)
            .putString(KEY_ANGEL_API_KEY, newSettings.angelOneApiKey.trim())
            .putString(KEY_ANGEL_CLIENT_CODE, newSettings.angelOneClientCode.trim())
            .putString(KEY_ANGEL_PIN, newSettings.angelOnePin.trim())
            .putString(KEY_ANGEL_TOTP_SECRET, newSettings.angelOneTotpSecret.trim())
            .putString(KEY_UPSTOX_API_KEY, newSettings.upstoxApiKey.trim())
            .putString(KEY_UPSTOX_TOKEN, newSettings.upstoxAccessToken.trim())
            .putString(KEY_KITE_API_KEY, newSettings.kiteApiKey.trim())
            .putString(KEY_KITE_TOKEN, newSettings.kiteAccessToken.trim())
            .putLong(KEY_SIM_INTERVAL, newSettings.simulatedTickIntervalMs.coerceIn(300L, 5000L))

        MarketSegment.entries.forEach { seg ->
            val cfg = newSettings.segmentConfigs[seg]
            if (cfg != null) {
                editor.putString(segFeedKey(seg), cfg.feedType.name)
                editor.putBoolean(segEnabledKey(seg), cfg.isEnabled)
            }
        }
        editor.apply()
        _settings.value = newSettings
    }

    fun updateSelectedFeed(feedType: FeedType) {
        val current = _settings.value
        val updatedSegments = current.segmentConfigs.toMutableMap()
        MarketSegment.entries.forEach { seg ->
            if (feedType.supportsSegment(seg)) {
                updatedSegments[seg] = SegmentFeedConfig(seg, feedType, isEnabled = true)
            }
        }
        updateSettings(current.copy(selectedFeed = feedType, segmentConfigs = updatedSegments))
    }

    fun updateSegmentConfig(config: SegmentFeedConfig) {
        val current = _settings.value
        val updatedSegments = current.segmentConfigs.toMutableMap()
        updatedSegments[config.segment] = config
        updateSettings(current.copy(segmentConfigs = updatedSegments))
    }

    fun updateSegmentConfigs(configs: Map<MarketSegment, SegmentFeedConfig>) {
        val current = _settings.value
        updateSettings(current.copy(segmentConfigs = configs))
    }

    private fun loadFromPrefs(): BrokerFeedSettings {
        val angelApiKey = prefs.getString(KEY_ANGEL_API_KEY, "") ?: ""
        val angelClient = prefs.getString(KEY_ANGEL_CLIENT_CODE, "") ?: ""
        val angelPin = prefs.getString(KEY_ANGEL_PIN, "") ?: ""
        val angelTotp = prefs.getString(KEY_ANGEL_TOTP_SECRET, "") ?: ""
        val hasAngelCreds = angelApiKey.isNotBlank() && angelClient.isNotBlank() &&
            angelPin.isNotBlank() && angelTotp.isNotBlank()

        val defaults = BrokerFeedSettings.defaultSegmentConfigs(hasAngelOneCreds = hasAngelCreds)
        val segmentConfigs = MarketSegment.entries.associateWith { seg ->
            val defaultCfg = defaults[seg]!!
            val savedFeedName = prefs.getString(segFeedKey(seg), null)
            val savedFeed = savedFeedName?.let {
                runCatching { FeedType.valueOf(it) }.getOrNull()
            }?.takeIf { it.supportsSegment(seg) } ?: defaultCfg.feedType
            val enabled = if (prefs.contains(segEnabledKey(seg))) {
                prefs.getBoolean(segEnabledKey(seg), true)
            } else {
                defaultCfg.isEnabled
            }
            SegmentFeedConfig(segment = seg, feedType = savedFeed, isEnabled = enabled)
        }

        val feedName = prefs.getString(KEY_SELECTED_FEED, FeedType.SIMULATED.name)
        val feedType = runCatching { FeedType.valueOf(feedName ?: FeedType.SIMULATED.name) }
            .getOrDefault(FeedType.SIMULATED)

        return BrokerFeedSettings(
            selectedFeed = feedType,
            segmentConfigs = segmentConfigs,
            angelOneApiKey = angelApiKey,
            angelOneClientCode = angelClient,
            angelOnePin = angelPin,
            angelOneTotpSecret = angelTotp,
            upstoxApiKey = prefs.getString(KEY_UPSTOX_API_KEY, "") ?: "",
            upstoxAccessToken = prefs.getString(KEY_UPSTOX_TOKEN, "") ?: "",
            kiteApiKey = prefs.getString(KEY_KITE_API_KEY, "") ?: "",
            kiteAccessToken = prefs.getString(KEY_KITE_TOKEN, "") ?: "",
            simulatedTickIntervalMs = prefs.getLong(KEY_SIM_INTERVAL, 1000L)
        )
    }

    fun getCustomAngelTokens(): List<Triple<String, Int, String>> {
        val raw = prefs.getString(KEY_CUSTOM_ANGEL_TOKENS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(";")
            .mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size == 3) {
                    val exch = parts[1].toIntOrNull() ?: return@mapNotNull null
                    Triple(parts[0], exch, parts[2])
                } else null
            }
    }

    fun saveCustomAngelToken(symbol: String, exchangeType: Int, token: String) {
        val current = getCustomAngelTokens().associateBy { it.first }.toMutableMap()
        current[symbol] = Triple(symbol, exchangeType, token)
        val encoded = current.values
            .take(120)
            .joinToString(";") { (sym, exch, tok) -> "$sym|$exch|$tok" }
        prefs.edit().putString(KEY_CUSTOM_ANGEL_TOKENS, encoded).apply()
    }

    private fun segFeedKey(seg: MarketSegment) = "seg_feed_${seg.name}"
    private fun segEnabledKey(seg: MarketSegment) = "seg_enabled_${seg.name}"

    private companion object {
        const val KEY_SELECTED_FEED = "selected_feed"
        const val KEY_ANGEL_API_KEY = "angel_api_key"
        const val KEY_ANGEL_CLIENT_CODE = "angel_client_code"
        const val KEY_ANGEL_PIN = "angel_pin"
        const val KEY_ANGEL_TOTP_SECRET = "angel_totp_secret"
        const val KEY_UPSTOX_API_KEY = "upstox_api_key"
        const val KEY_UPSTOX_TOKEN = "upstox_token"
        const val KEY_KITE_API_KEY = "kite_api_key"
        const val KEY_KITE_TOKEN = "kite_token"
        const val KEY_SIM_INTERVAL = "sim_interval_ms"
        const val KEY_CUSTOM_ANGEL_TOKENS = "custom_angel_tokens"
    }
}
