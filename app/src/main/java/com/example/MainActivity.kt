package com.example

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.ai.configureAppCheck
import com.example.data.engine.ExecutionEngine
import com.example.data.market.MarketIntelligenceRepository
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingRepository
import com.example.data.signal.SignalRepository
import com.example.domain.market.MarketSentimentCalculator
import com.example.domain.market.MarketSentimentSnapshot
import com.example.domain.model.Instrument
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderStatus
import com.example.domain.voice.ParsedVoiceOrder
import com.example.ui.components.GlobalHotkeyGuard
import com.example.ui.components.KeyboardHotkeysDialog
import com.example.ui.components.LocalGlobalHotkeyGuard
import com.example.ui.components.MarketSentimentDashboardBar
import com.example.ui.components.MarketSentimentDetailDialog
import com.example.ui.components.VoiceOrderBottomSheet
import com.example.ui.components.VoiceOrderExecutionFeedback
import com.example.ui.components.observeGlobalImeVisibility
import com.example.ui.navigation.ChartRoute
import com.example.ui.navigation.FeedSettingsRoute
import com.example.ui.navigation.HeatmapsRoute
import com.example.ui.navigation.InstrumentDetailRoute
import com.example.ui.navigation.JournalRoute
import com.example.ui.navigation.MarketPulseRoute
import com.example.ui.navigation.NewsRoute
import com.example.ui.navigation.OptionChainRoute
import com.example.ui.navigation.OrdersRoute
import com.example.ui.navigation.PositionsRoute
import com.example.ui.navigation.SignalsRoute
import com.example.ui.navigation.StrategyCoachesRoute
import com.example.ui.navigation.StrategyWorkbenchRoute
import com.example.ui.navigation.WatchlistRoute
import com.example.ui.screens.chart.ChartScreen
import com.example.ui.screens.coach.AiStrategyCoachesScreen
import com.example.ui.screens.strategy.StrategyWorkbenchScreen
import com.example.ui.screens.detail.InstrumentDetailScreen
import com.example.ui.screens.heatmaps.HeatmapsScreen
import com.example.ui.screens.journal.JournalScreen
import com.example.ui.screens.market.MarketPulseScreen
import com.example.ui.screens.news.MarketNewsScreen
import com.example.ui.screens.optionchain.OptionChainScreen
import com.example.ui.screens.orders.OrdersScreen
import com.example.ui.screens.positions.PositionsScreen
import com.example.ui.screens.settings.FeedSettingsScreen
import com.example.ui.screens.signals.SignalsScreen
import com.example.ui.screens.watchlist.WatchlistScreen
import com.example.ui.theme.PaperPulseTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TradingHotkey {
    BUY,
    SELL,
    VOICE,
    CHART,
    SHOW_HELP,
    TAB_1,
    TAB_2,
    TAB_3,
    TAB_4,
    TAB_5,
    TAB_6
}

data class ShellBadgeState(
    val openPositionsCount: Int = 0,
    val pendingOrdersCount: Int = 0,
    val activeSignalsCount: Int = 0
)

@HiltViewModel
class MainShellViewModel @Inject constructor(
    private val tradingRepository: TradingRepository,
    marketDataRepository: MarketDataRepository,
    private val marketIntelligenceRepository: MarketIntelligenceRepository,
    signalRepository: SignalRepository,
    executionEngine: ExecutionEngine,
    priceAlertRepository: com.example.data.alerts.PriceAlertRepository
) : ViewModel() {
    val engineEvents = executionEngine.engineEvents
    val priceAlertEvents = priceAlertRepository.triggeredEvents

    val instruments: StateFlow<List<Instrument>> = marketDataRepository.instruments
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val marketSentiment: StateFlow<MarketSentimentSnapshot> = marketIntelligenceRepository.stocks
        .map { stocks -> MarketSentimentCalculator.compute(stocks) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MarketSentimentCalculator.compute(marketIntelligenceRepository.stocks.value)
        )

    val isRefreshingSentiment: StateFlow<Boolean> = marketIntelligenceRepository.isRefreshing
    val sentimentSyncLabel: StateFlow<String> = marketIntelligenceRepository.lastSyncLabel

    init {
        viewModelScope.launch {
            marketDataRepository.instruments.collect { liveList ->
                if (liveList.isNotEmpty()) {
                    marketIntelligenceRepository.mergeWatchlistInstruments(liveList)
                }
            }
        }
    }

    private val _activeSymbol = MutableStateFlow("RELIANCE")
    val activeSymbol: StateFlow<String> = _activeSymbol.asStateFlow()

    private val _voiceOrderFeedback = MutableStateFlow<VoiceOrderExecutionFeedback>(VoiceOrderExecutionFeedback.Idle)
    val voiceOrderFeedback: StateFlow<VoiceOrderExecutionFeedback> = _voiceOrderFeedback.asStateFlow()

    val badges: StateFlow<ShellBadgeState> = combine(
        tradingRepository.positions,
        tradingRepository.orders,
        signalRepository.activeSignals
    ) { positions, orders, signals ->
        ShellBadgeState(
            openPositionsCount = positions.size,
            pendingOrdersCount = orders.count { it.status == OrderStatus.PENDING },
            activeSignalsCount = signals.size
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ShellBadgeState()
    )

    fun refreshMarketSentiment() {
        viewModelScope.launch {
            marketIntelligenceRepository.refreshAll()
        }
    }

    fun setActiveSymbol(symbol: String) {
        if (symbol.isNotBlank()) {
            _activeSymbol.value = symbol
        }
    }

    fun resetVoiceOrderFeedback() {
        _voiceOrderFeedback.value = VoiceOrderExecutionFeedback.Idle
    }

    fun placeVoiceOrder(order: ParsedVoiceOrder) {
        _voiceOrderFeedback.value = VoiceOrderExecutionFeedback.Submitting
        viewModelScope.launch {
            val result = tradingRepository.placeOrder(order.toPlacementRequest())
            result.fold(
                onSuccess = { msg ->
                    _activeSymbol.value = order.instrument.symbol
                    _voiceOrderFeedback.value = VoiceOrderExecutionFeedback.Confirmed(msg)
                },
                onFailure = { err ->
                    _voiceOrderFeedback.value = VoiceOrderExecutionFeedback.Rejected(
                        err.message ?: "Unable to place voice order"
                    )
                }
            )
        }
    }
}

private data class TopLevelDestination(
    val label: String,
    val route: Any,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var credentialsStore: com.example.data.feed.live.SecureBrokerCredentialsStore

    @Inject
    lateinit var marketDataRepository: com.example.data.repository.MarketDataRepository

    @Inject
    lateinit var aiMarketRepository: com.example.data.ai.AiMarketRepository

    @Inject
    lateinit var preferencesRepository: com.example.data.repository.TradingPreferencesRepository

    private var brokerCallbackMessage by mutableStateOf<String?>(null)
    private val _hotkeyEvents = MutableSharedFlow<TradingHotkey>(extraBufferCapacity = 8)
    private val hotkeyGuard = GlobalHotkeyGuard()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        lifecycleScope.launch {
            // Allow App Check provider and local database initialization to settle before cold AI ping.
            delay(1200L)
            val cfg = aiMarketRepository.activeConfig.value
            if (cfg.autoConnect && (!cfg.provider.requiresApiKey || cfg.apiKey.isNotBlank())) {
                aiMarketRepository.autoConnectAndFetchModels(cfg)
            }
        }
        handleBrokerDeepLink(intent)
        enableEdgeToEdge()
        setContent {
            // Persisted so the chosen scheme survives process death and app restarts;
            // rememberSaveable only kept it for the lifetime of a single launch.
            val isDarkTheme by preferencesRepository.darkThemeFlow
                .collectAsStateWithLifecycle(initialValue = true)
            PaperPulseTheme(darkTheme = isDarkTheme) {
                CompositionLocalProvider(LocalGlobalHotkeyGuard provides hotkeyGuard) {
                    PaperPulseApp(
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = {
                            lifecycleScope.launch { preferencesRepository.updateDarkTheme(!isDarkTheme) }
                        },
                        brokerCallbackMessage = brokerCallbackMessage,
                        onConsumeBrokerMessage = { brokerCallbackMessage = null },
                        hotkeyEvents = _hotkeyEvents.asSharedFlow(),
                        hotkeyGuard = hotkeyGuard
                    )
                }
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
            return super.onKeyUp(keyCode, event)
        }
        // Never intercept mobile soft-keyboard / virtual-keyboard key events (e.g. typing 1-5 in SL/Target/Price inputs)
        val isSoftOrVirtualKeyboard =
            (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) != 0 ||
                event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD ||
                event.deviceId <= 0 ||
                !event.isFromSource(InputDevice.SOURCE_KEYBOARD)
        if (isSoftOrVirtualKeyboard) {
            return super.onKeyUp(keyCode, event)
        }
        // Never fire single-key hotkeys while the on-screen keyboard (IME) is visible
        val imeVisible = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) {
            return super.onKeyUp(keyCode, event)
        }
        // Never fire single-key hotkeys while any text field is focused or accepting input
        val imm = getSystemService(InputMethodManager::class.java)
        if (imm?.isAcceptingText == true || currentFocus?.onCheckIsTextEditor() == true) {
            return super.onKeyUp(keyCode, event)
        }
        // Never fire while on a secondary route (Order Ticket, Chart, Settings), modal, or focused Compose text input
        if (hotkeyGuard.isSuppressed) {
            return super.onKeyUp(keyCode, event)
        }
        val hotkey = when (keyCode) {
            KeyEvent.KEYCODE_B -> TradingHotkey.BUY
            KeyEvent.KEYCODE_S -> TradingHotkey.SELL
            KeyEvent.KEYCODE_V -> TradingHotkey.VOICE
            KeyEvent.KEYCODE_C -> TradingHotkey.CHART
            KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_SLASH -> TradingHotkey.SHOW_HELP
            KeyEvent.KEYCODE_1 -> TradingHotkey.TAB_1
            KeyEvent.KEYCODE_2 -> TradingHotkey.TAB_2
            KeyEvent.KEYCODE_3 -> TradingHotkey.TAB_3
            KeyEvent.KEYCODE_4 -> TradingHotkey.TAB_4
            KeyEvent.KEYCODE_5 -> TradingHotkey.TAB_5
            KeyEvent.KEYCODE_6 -> TradingHotkey.TAB_6
            else -> null
        }
        if (hotkey != null) {
            _hotkeyEvents.tryEmit(hotkey)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBrokerDeepLink(intent)
    }

    private fun handleBrokerDeepLink(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "paperpulse" || data.host != "broker-callback") return
        val broker = data.getQueryParameter("broker")?.lowercase().orEmpty()
        val authToken = data.getQueryParameter("auth_token")
            ?: data.getQueryParameter("access_token")
            ?: data.getQueryParameter("request_token")
            ?: data.getQueryParameter("code")
        val clientCode = data.getQueryParameter("client_code") ?: data.getQueryParameter("clientcode")
        if (authToken.isNullOrBlank()) return

        val current = credentialsStore.getCurrent()
        val updated = when {
            broker.contains("upstox") -> current.copy(upstoxAccessToken = authToken)
            broker.contains("kite") || broker.contains("zerodha") -> current.copy(kiteAccessToken = authToken)
            else -> current.copy(
                angelOneClientCode = clientCode?.takeIf { it.isNotBlank() } ?: current.angelOneClientCode
            )
        }
        credentialsStore.updateSettings(updated)
        brokerCallbackMessage = "Broker login callback received (${broker.ifBlank { "Live Feed" }}) — token saved"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperPulseApp(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    brokerCallbackMessage: String? = null,
    onConsumeBrokerMessage: () -> Unit = {},
    hotkeyEvents: Flow<TradingHotkey> = emptyFlow(),
    hotkeyGuard: GlobalHotkeyGuard = remember { GlobalHotkeyGuard() },
    shellViewModel: MainShellViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val badges by shellViewModel.badges.collectAsStateWithLifecycle()
    val instruments by shellViewModel.instruments.collectAsStateWithLifecycle()
    val activeSymbol by shellViewModel.activeSymbol.collectAsStateWithLifecycle()
    val voiceOrderFeedback by shellViewModel.voiceOrderFeedback.collectAsStateWithLifecycle()
    val marketSentiment by shellViewModel.marketSentiment.collectAsStateWithLifecycle()
    val isRefreshingSentiment by shellViewModel.isRefreshingSentiment.collectAsStateWithLifecycle()
    val sentimentSyncLabel by shellViewModel.sentimentSyncLabel.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var showVoiceModal by rememberSaveable { mutableStateOf(false) }
    var showHotkeysDialog by rememberSaveable { mutableStateOf(false) }
    var showSentimentDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        shellViewModel.engineEvents.collect { msg ->
            launch { snackbarHostState.showSnackbar(msg) }
        }
    }

    LaunchedEffect(Unit) {
        shellViewModel.priceAlertEvents.collect { alertEvent ->
            launch {
                val res = snackbarHostState.showSnackbar(
                    message = "Alert: ${alertEvent.message}",
                    actionLabel = "Trade",
                    duration = SnackbarDuration.Long
                )
                if (res == SnackbarResult.ActionPerformed && !hotkeyGuard.isNavigationLocked) {
                    shellViewModel.setActiveSymbol(alertEvent.alert.symbol)
                    navController.navigate(InstrumentDetailRoute(alertEvent.alert.symbol))
                }
            }
        }
    }

    LaunchedEffect(brokerCallbackMessage) {
        brokerCallbackMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onConsumeBrokerMessage()
        }
    }

    val destinations = remember {
        listOf(
            TopLevelDestination(
                label = "Watchlist",
                route = WatchlistRoute,
                selectedIcon = Icons.AutoMirrored.Filled.TrendingUp,
                unselectedIcon = Icons.AutoMirrored.Outlined.TrendingUp,
                testTag = "nav_watchlist"
            ),
            TopLevelDestination(
                label = "Heatmaps",
                route = HeatmapsRoute,
                selectedIcon = Icons.Filled.GridView,
                unselectedIcon = Icons.Outlined.GridView,
                testTag = "nav_heatmaps"
            ),
            TopLevelDestination(
                label = "Signals",
                route = SignalsRoute,
                selectedIcon = Icons.Filled.Bolt,
                unselectedIcon = Icons.Outlined.Bolt,
                testTag = "nav_signals"
            ),
            TopLevelDestination(
                label = "Positions",
                route = PositionsRoute,
                selectedIcon = Icons.Filled.AccountBalanceWallet,
                unselectedIcon = Icons.Outlined.AccountBalanceWallet,
                testTag = "nav_positions"
            ),
            TopLevelDestination(
                label = "Orders",
                route = OrdersRoute,
                selectedIcon = Icons.AutoMirrored.Filled.ReceiptLong,
                unselectedIcon = Icons.AutoMirrored.Outlined.ReceiptLong,
                testTag = "nav_orders"
            ),
            TopLevelDestination(
                label = "Journal",
                route = JournalRoute,
                selectedIcon = Icons.Filled.Book,
                unselectedIcon = Icons.Outlined.Book,
                testTag = "nav_journal"
            )
        )
    }

    val showTopLevelChrome = currentDestination?.let { dest ->
        dest.hasRoute<WatchlistRoute>() ||
            dest.hasRoute<HeatmapsRoute>() ||
            dest.hasRoute<SignalsRoute>() ||
            dest.hasRoute<PositionsRoute>() ||
            dest.hasRoute<OrdersRoute>() ||
            dest.hasRoute<JournalRoute>()
    } ?: true

    val isImeVisible = observeGlobalImeVisibility(hotkeyGuard)
    val guardState by hotkeyGuard.state.collectAsStateWithLifecycle()

    SideEffect {
        hotkeyGuard.isRouteOrModalSuppressed =
            !showTopLevelChrome || showVoiceModal || showHotkeysDialog || showSentimentDialog
    }

    val navigateTopLevel: (Any, Boolean) -> Unit = { target, force ->
        // Keyboard-aware navigation lock: never switch bottom tabs while soft keyboard is open,
        // a price input is focused, or an order/SL/Target modal is open (unless explicitly forced after order submit).
        if (force || !hotkeyGuard.isNavigationLocked) {
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    LaunchedEffect(hotkeyEvents) {
        hotkeyEvents.collect { hotkey ->
            if (hotkeyGuard.isSuppressed) return@collect
            val currentSym = shellViewModel.activeSymbol.value
            when (hotkey) {
                TradingHotkey.BUY -> {
                    showVoiceModal = false
                    showHotkeysDialog = false
                    navController.navigate(
                        InstrumentDetailRoute(
                            symbol = currentSym,
                            prefillSide = OrderSide.BUY.name
                        )
                    )
                    launch { snackbarHostState.showSnackbar("Hotkey [B]: BUY $currentSym") }
                }
                TradingHotkey.SELL -> {
                    showVoiceModal = false
                    showHotkeysDialog = false
                    navController.navigate(
                        InstrumentDetailRoute(
                            symbol = currentSym,
                            prefillSide = OrderSide.SELL.name
                        )
                    )
                    launch { snackbarHostState.showSnackbar("Hotkey [S]: SELL $currentSym") }
                }
                TradingHotkey.VOICE -> {
                    showHotkeysDialog = false
                    shellViewModel.resetVoiceOrderFeedback()
                    showVoiceModal = true
                }
                TradingHotkey.CHART -> {
                    showVoiceModal = false
                    showHotkeysDialog = false
                    navController.navigate(ChartRoute(currentSym))
                }
                TradingHotkey.SHOW_HELP -> {
                    showHotkeysDialog = !showHotkeysDialog
                }
                TradingHotkey.TAB_1 -> navigateTopLevel(WatchlistRoute, false)
                TradingHotkey.TAB_2 -> navigateTopLevel(HeatmapsRoute, false)
                TradingHotkey.TAB_3 -> navigateTopLevel(SignalsRoute, false)
                TradingHotkey.TAB_4 -> navigateTopLevel(PositionsRoute, false)
                TradingHotkey.TAB_5 -> navigateTopLevel(OrdersRoute, false)
                TradingHotkey.TAB_6 -> navigateTopLevel(JournalRoute, false)
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 640.dp
        val showPaperBadge = maxWidth >= 400.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (showTopLevelChrome) {
                    Column {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "PaperPulse",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (showPaperBadge) {
                                        Surface(
                                            modifier = Modifier.padding(start = 6.dp),
                                            shape = MaterialTheme.shapes.extraSmall,
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = "PAPER",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        shellViewModel.resetVoiceOrderFeedback()
                                        showVoiceModal = true
                                    },
                                    modifier = Modifier.testTag("topbar_voice_order_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Mic,
                                        contentDescription = "Voice Command Order"
                                    )
                                }
                                IconButton(
                                    onClick = { showHotkeysDialog = true },
                                    modifier = Modifier.testTag("topbar_hotkeys_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Keyboard,
                                        contentDescription = "Trading Keyboard Shortcuts"
                                    )
                                }
                                IconButton(
                                    onClick = { navController.navigate(StrategyCoachesRoute) },
                                    modifier = Modifier.testTag("topbar_ai_coaches_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Psychology,
                                        contentDescription = "AI Strategy Coaches"
                                    )
                                }
                                IconButton(
                                    onClick = { navController.navigate(StrategyWorkbenchRoute) },
                                    modifier = Modifier.testTag("topbar_strategy_workbench_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Science,
                                        contentDescription = "Strategy Workbench: scanner, simulator, tax lots, rebalancer, screener"
                                    )
                                }
                                IconButton(
                                    onClick = { navController.navigate(MarketPulseRoute(initialTab = 0)) },
                                    modifier = Modifier.testTag("topbar_market_pulse_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Insights,
                                        contentDescription = "Market Movers, Market Flow, Global & Screener"
                                    )
                                }
                                IconButton(
                                    onClick = { navController.navigate(OptionChainRoute("NIFTY")) },
                                    modifier = Modifier.testTag("topbar_option_chain_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.GridOn,
                                        contentDescription = "Live Option Chain"
                                    )
                                }
                                IconButton(
                                    onClick = onToggleTheme,
                                    modifier = Modifier.testTag("toggle_theme_button")
                                ) {
                                    Icon(
                                        imageVector = if (isDarkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                        contentDescription = "Toggle Dark/Light Theme"
                                    )
                                }
                                IconButton(
                                    onClick = { navController.navigate(FeedSettingsRoute) },
                                    modifier = Modifier.testTag("topbar_feed_settings_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Tune,
                                        contentDescription = "Feed & Simulation Settings"
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                        MarketSentimentDashboardBar(
                            sentiment = marketSentiment,
                            isRefreshing = isRefreshingSentiment,
                            onClick = { showSentimentDialog = true }
                        )
                    }
                }
            },
            bottomBar = {
                // Hide bottom tab bar while soft keyboard or order modal is active so thumb taps
                // near the number row can never accidentally switch to Watchlist or Journal.
                if (showTopLevelChrome && !useRail && !isImeVisible && !guardState.isOrderModalOpen) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        destinations.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            val badgeCount = when (dest.route) {
                                SignalsRoute -> badges.activeSignalsCount
                                PositionsRoute -> badges.openPositionsCount
                                OrdersRoute -> badges.pendingOrdersCount
                                else -> 0
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route, false) },
                                modifier = Modifier.testTag(dest.testTag),
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (badgeCount > 0) {
                                                Badge { Text(badgeCount.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    }
                                },
                                label = {
                                    Text(
                                        text = dest.label,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showTopLevelChrome && useRail) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        destinations.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            val badgeCount = when (dest.route) {
                                SignalsRoute -> badges.activeSignalsCount
                                PositionsRoute -> badges.openPositionsCount
                                OrdersRoute -> badges.pendingOrdersCount
                                else -> 0
                            }
                            NavigationRailItem(
                                selected = selected,
                                enabled = !guardState.isNavigationLocked,
                                onClick = { navigateTopLevel(dest.route, false) },
                                modifier = Modifier.testTag(dest.testTag),
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (badgeCount > 0) {
                                                Badge { Text(badgeCount.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    }
                                },
                                label = { Text(dest.label) }
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = WatchlistRoute,
                    modifier = Modifier.weight(1f).fillMaxSize()
                ) {
                    composable<WatchlistRoute> {
                        WatchlistScreen(
                            onInstrumentClick = { symbol ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(InstrumentDetailRoute(symbol))
                            },
                            onOpenFeedSettings = {
                                navController.navigate(FeedSettingsRoute)
                            },
                            onOpenOptionChain = { underlying ->
                                navController.navigate(OptionChainRoute(underlying))
                            },
                            onOpenNews = {
                                navController.navigate(NewsRoute)
                            },
                            onOpenMarketPulse = { initialTab ->
                                if (initialTab == 1) {
                                    navigateTopLevel(HeatmapsRoute, false)
                                } else {
                                    navController.navigate(MarketPulseRoute(initialTab = initialTab))
                                }
                            },
                            onOpenStrategyCoaches = {
                                navController.navigate(StrategyCoachesRoute)
                            },
                            onOpenChart = { symbol ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(ChartRoute(symbol))
                            }
                        )
                    }
                    composable<StrategyWorkbenchRoute> {
                        StrategyWorkbenchScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTicket = { symbol, side, quantity ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(
                                    InstrumentDetailRoute(
                                        symbol = symbol,
                                        prefillSide = side,
                                        // The rebalancer deals in delivery stock; the scanner
                                        // sends no quantity and leaves the product alone.
                                        prefillProduct = if (quantity > 0) "CARRY_FORWARD" else null,
                                        prefillQuantity = quantity.takeIf { it > 0 }?.toString()
                                    )
                                )
                            },
                            onSendPlannedOrder = { symbol, entry, stop, target, quantity ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(
                                    InstrumentDetailRoute(
                                        symbol = symbol,
                                        prefillSide = OrderSide.BUY.name,
                                        prefillEntry = entry,
                                        prefillSl = stop,
                                        prefillTarget = target,
                                        prefillProduct = "INTRADAY",
                                        prefillQuantity = quantity.takeIf { it > 0 }?.toString()
                                    )
                                )
                            }
                        )
                    }
                    composable<StrategyCoachesRoute> {
                        AiStrategyCoachesScreen(
                            onBack = { navController.popBackStack() },
                            onOpenAiSettings = {
                                navController.navigate(FeedSettingsRoute)
                            }
                        )
                    }
                    composable<HeatmapsRoute> {
                        HeatmapsScreen(
                            onOpenInstrument = { symbol ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(InstrumentDetailRoute(symbol))
                            },
                            onOpenChart = { symbol ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(ChartRoute(symbol))
                            }
                        )
                    }
                    composable<MarketPulseRoute> {
                        MarketPulseScreen(
                            onBack = { navController.popBackStack() },
                            onOpenInstrument = { symbol ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(InstrumentDetailRoute(symbol))
                            },
                            onOpenChart = { symbol ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(ChartRoute(symbol))
                            }
                        )
                    }
                    composable<NewsRoute> {
                        MarketNewsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenSymbol = { symbol ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(InstrumentDetailRoute(symbol))
                            }
                        )
                    }
                    composable<OptionChainRoute> {
                        OptionChainScreen(
                            onBack = { navController.popBackStack() },
                            onOpenInstrumentDetail = { symbol ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(InstrumentDetailRoute(symbol))
                            }
                        )
                    }
                    composable<SignalsRoute> {
                        SignalsScreen(
                            onCreateOrderFromSignal = { route ->
                                shellViewModel.setActiveSymbol(route.symbol)
                                navController.navigate(route)
                            },
                            onOpenChart = { symbol, timeframe ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(ChartRoute(symbol, timeframe))
                            },
                            onOpenFeedSettings = {
                                navController.navigate(FeedSettingsRoute)
                            }
                        )
                    }
                    composable<InstrumentDetailRoute> {
                        InstrumentDetailScreen(
                            onBack = { navController.popBackStack() },
                            onOrderPlacedNavigate = { message, goToOrdersTab ->
                                navController.popBackStack()
                                navigateTopLevel(if (goToOrdersTab) OrdersRoute else PositionsRoute, true)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                            onOpenOptionChain = { underlying ->
                                navController.navigate(OptionChainRoute(underlying))
                            },
                            onOpenChart = { symbol ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(ChartRoute(symbol))
                            },
                            onOpenFeedSettings = {
                                navController.navigate(FeedSettingsRoute)
                            }
                        )
                    }
                    composable<ChartRoute> {
                        ChartScreen(
                            onBack = { navController.popBackStack() },
                            onTrade = { route ->
                                shellViewModel.setActiveSymbol(route.symbol)
                                navController.navigate(route)
                            },
                            onOpenFeedSettings = {
                                navController.navigate(FeedSettingsRoute)
                            }
                        )
                    }
                    composable<PositionsRoute> {
                        PositionsScreen(
                            onOpenDetail = { symbol ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(InstrumentDetailRoute(symbol))
                            },
                            onOpenFeedSettings = {
                                navController.navigate(FeedSettingsRoute)
                            }
                        )
                    }
                    composable<OrdersRoute> {
                        OrdersScreen()
                    }
                    composable<JournalRoute> {
                        JournalScreen(
                            onOpenFeedSettings = {
                                navController.navigate(FeedSettingsRoute)
                            },
                            onOpenInstrument = { symbol ->
                                shellViewModel.setActiveSymbol(symbol)
                                navController.navigate(InstrumentDetailRoute(symbol))
                            }
                        )
                    }
                    composable<FeedSettingsRoute> {
                        FeedSettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }

        if (showVoiceModal) {
            VoiceOrderBottomSheet(
                instruments = instruments,
                activeSymbol = activeSymbol,
                executionFeedback = voiceOrderFeedback,
                onConfirmOrder = { parsedOrder ->
                    shellViewModel.placeVoiceOrder(parsedOrder)
                },
                onOpenInOrderTicket = { parsedOrder ->
                    shellViewModel.setActiveSymbol(parsedOrder.instrument.symbol)
                    shellViewModel.resetVoiceOrderFeedback()
                    showVoiceModal = false
                    navController.navigate(
                        InstrumentDetailRoute(
                            symbol = parsedOrder.instrument.symbol,
                            prefillSide = parsedOrder.side.name,
                            prefillEntry = parsedOrder.limitPrice?.toPlainString(),
                            prefillSl = parsedOrder.stopLossPrice?.toPlainString(),
                            prefillTarget = parsedOrder.targetPrice?.toPlainString(),
                            prefillProduct = parsedOrder.productType.name
                        )
                    )
                },
                onResetFeedback = { shellViewModel.resetVoiceOrderFeedback() },
                onDismiss = {
                    showVoiceModal = false
                    shellViewModel.resetVoiceOrderFeedback()
                }
            )
        }

        if (showHotkeysDialog) {
            KeyboardHotkeysDialog(
                activeSymbol = activeSymbol,
                onDismiss = { showHotkeysDialog = false }
            )
        }

        if (showSentimentDialog) {
            MarketSentimentDetailDialog(
                sentiment = marketSentiment,
                isRefreshing = isRefreshingSentiment,
                lastSyncLabel = sentimentSyncLabel,
                onRefresh = { shellViewModel.refreshMarketSentiment() },
                onOpenScreener = { navController.navigate(MarketPulseRoute(initialTab = 4)) },
                onDismiss = { showSentimentDialog = false }
            )
        }
    }
}
