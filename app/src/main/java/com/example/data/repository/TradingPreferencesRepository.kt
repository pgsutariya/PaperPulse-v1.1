package com.example.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.FeedSourceType
import com.example.domain.model.TradingConfig
import com.example.domain.strategy.WorkbenchState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "paperpulse_prefs")

@Singleton
class TradingPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SLIPPAGE_BPS = intPreferencesKey("slippage_bps")
        val FLAT_BROKERAGE = stringPreferencesKey("flat_brokerage")
        val CRYPTO_FEE_PCT = stringPreferencesKey("crypto_fee_pct")
        val FEED_SOURCE = stringPreferencesKey("feed_source")
        val TICK_INTERVAL_MS = longPreferencesKey("tick_interval_ms")
        val BROKER_WS_URL = stringPreferencesKey("broker_ws_url")
        val BROKER_CLIENT_ID = stringPreferencesKey("broker_client_id")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val SIGNAL_NOTIFICATIONS_ENABLED = booleanPreferencesKey("signal_notifications_enabled")
        val USE_FREE_PUBLIC_FEED = booleanPreferencesKey("use_free_public_feed")
        val CHART_ENGINE = stringPreferencesKey("chart_engine")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val STRATEGY_WORKBENCH = stringPreferencesKey("strategy_workbench")
    }

    // Lenient on decode so a blob written by an older build - or a half-written one -
    // degrades to an empty workbench instead of taking the screen down.
    private val workbenchJson = Json { ignoreUnknownKeys = true }

    // Feed token kept strictly in memory only so sensitive credentials are never persisted
    @Volatile
    private var sessionFeedToken: String = ""

    val configFlow: Flow<TradingConfig> = context.dataStore.data.map { prefs ->
        TradingConfig(
            slippageBps = prefs[Keys.SLIPPAGE_BPS] ?: 5,
            flatBrokerage = prefs[Keys.FLAT_BROKERAGE]?.toBigDecimalOrNull() ?: BigDecimal("20.00"),
            cryptoFeePercent = prefs[Keys.CRYPTO_FEE_PCT]?.toBigDecimalOrNull() ?: BigDecimal("0.10"),
            activeFeedSource = prefs[Keys.FEED_SOURCE]?.let {
                runCatching { FeedSourceType.valueOf(it) }.getOrNull()
            } ?: FeedSourceType.SIMULATED,
            tickIntervalMs = prefs[Keys.TICK_INTERVAL_MS] ?: 1000L,
            brokerWsUrl = prefs[Keys.BROKER_WS_URL] ?: "",
            brokerClientId = prefs[Keys.BROKER_CLIENT_ID] ?: "",
            brokerFeedToken = sessionFeedToken,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            signalNotificationsEnabled = prefs[Keys.SIGNAL_NOTIFICATIONS_ENABLED] ?: true,
            useFreePublicMarketFeed = prefs[Keys.USE_FREE_PUBLIC_FEED] ?: true
        )
    }

    val chartEngineFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.CHART_ENGINE] ?: "CANVAS"
    }

    suspend fun updateChartEngine(engineName: String) {
        context.dataStore.edit { it[Keys.CHART_ENGINE] = engineName }
    }

    /** Dark is the terminal default; the shell toggle persists the user's choice here. */
    val darkThemeFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DARK_THEME] ?: true
    }

    suspend fun updateDarkTheme(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    /** Hand-built strategy workbench state: tax lots, rebalance targets, screen rules. */
    val workbenchFlow: Flow<WorkbenchState> = context.dataStore.data.map { prefs ->
        prefs[Keys.STRATEGY_WORKBENCH]
            ?.let { raw ->
                runCatching {
                    workbenchJson.decodeFromString(WorkbenchState.serializer(), raw)
                }.getOrNull()
            }
            ?: WorkbenchState()
    }

    suspend fun updateWorkbench(state: WorkbenchState) {
        val encoded = workbenchJson.encodeToString(WorkbenchState.serializer(), state)
        context.dataStore.edit { it[Keys.STRATEGY_WORKBENCH] = encoded }
    }

    suspend fun getConfig(): TradingConfig = configFlow.first()

    suspend fun updateSlippageBps(bps: Int) {
        context.dataStore.edit { it[Keys.SLIPPAGE_BPS] = bps.coerceIn(0, 100) }
    }

    suspend fun updateCharges(flatBrokerage: BigDecimal, cryptoFeePct: BigDecimal) {
        context.dataStore.edit {
            it[Keys.FLAT_BROKERAGE] = flatBrokerage.max(BigDecimal.ZERO).toPlainString()
            it[Keys.CRYPTO_FEE_PCT] = cryptoFeePct.max(BigDecimal.ZERO).toPlainString()
        }
    }

    suspend fun updateNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun updateSignalNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SIGNAL_NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun updateUseFreePublicMarketFeed(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USE_FREE_PUBLIC_FEED] = enabled }
    }

    suspend fun updateFeedSettings(
        source: FeedSourceType,
        tickIntervalMs: Long,
        wsUrl: String,
        clientId: String,
        feedToken: String
    ) {
        sessionFeedToken = feedToken
        context.dataStore.edit {
            it[Keys.FEED_SOURCE] = source.name
            it[Keys.TICK_INTERVAL_MS] = tickIntervalMs.coerceIn(300L, 5000L)
            it[Keys.BROKER_WS_URL] = wsUrl
            it[Keys.BROKER_CLIENT_ID] = clientId
        }
    }
}
