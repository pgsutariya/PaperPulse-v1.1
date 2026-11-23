package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.LightMode
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.ai.configureAppCheck
import com.example.data.engine.ExecutionEngine
import com.example.data.repository.TradingRepository
import com.example.data.signal.SignalRepository
import com.example.domain.model.OrderStatus
import com.example.ui.navigation.ChartRoute
import com.example.ui.navigation.FeedSettingsRoute
import com.example.ui.navigation.InstrumentDetailRoute
import com.example.ui.navigation.JournalRoute
import com.example.ui.navigation.OptionChainRoute
import com.example.ui.navigation.OrdersRoute
import com.example.ui.navigation.PositionsRoute
import com.example.ui.navigation.SignalsRoute
import com.example.ui.navigation.WatchlistRoute
import com.example.ui.screens.chart.ChartScreen
import com.example.ui.screens.detail.InstrumentDetailScreen
import com.example.ui.screens.journal.JournalScreen
import com.example.ui.screens.optionchain.OptionChainScreen
import com.example.ui.screens.orders.OrdersScreen
import com.example.ui.screens.positions.PositionsScreen
import com.example.ui.screens.settings.FeedSettingsScreen
import com.example.ui.screens.signals.SignalsScreen
import com.example.ui.screens.watchlist.WatchlistScreen
import com.example.ui.theme.PaperPulseTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ShellBadgeState(
    val openPositionsCount: Int = 0,
    val pendingOrdersCount: Int = 0,
    val activeSignalsCount: Int = 0
)

@HiltViewModel
class MainShellViewModel @Inject constructor(
    tradingRepository: TradingRepository,
    signalRepository: SignalRepository,
    executionEngine: ExecutionEngine
) : ViewModel() {
    val engineEvents = executionEngine.engineEvents

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

    private var brokerCallbackMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        handleBrokerDeepLink(intent)
        enableEdgeToEdge()
        setContent {
            var isDarkTheme by rememberSaveable { mutableStateOf(true) }
            PaperPulseTheme(darkTheme = isDarkTheme) {
                PaperPulseApp(
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { isDarkTheme = !isDarkTheme },
                    brokerCallbackMessage = brokerCallbackMessage,
                    onConsumeBrokerMessage = { brokerCallbackMessage = null }
                )
            }
        }
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
    shellViewModel: MainShellViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val badges by shellViewModel.badges.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        shellViewModel.engineEvents.collect { msg ->
            launch { snackbarHostState.showSnackbar(msg) }
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
            dest.hasRoute<SignalsRoute>() ||
            dest.hasRoute<PositionsRoute>() ||
            dest.hasRoute<OrdersRoute>() ||
            dest.hasRoute<JournalRoute>()
    } ?: true

    val navigateTopLevel: (Any) -> Unit = { target ->
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 640.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (showTopLevelChrome) {
                    TopAppBar(
                        title = {
                            Row {
                                Text(
                                    text = "PaperPulse",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    modifier = Modifier.padding(start = 8.dp),
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = "PAPER",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        },
                        actions = {
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
                }
            },
            bottomBar = {
                if (showTopLevelChrome && !useRail) {
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
                                onClick = { navigateTopLevel(dest.route) },
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
                                onClick = { navigateTopLevel(dest.route) },
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
                                navController.navigate(InstrumentDetailRoute(symbol))
                            },
                            onOpenFeedSettings = {
                                navController.navigate(FeedSettingsRoute)
                            },
                            onOpenOptionChain = { underlying ->
                                navController.navigate(OptionChainRoute(underlying))
                            }
                        )
                    }
                    composable<OptionChainRoute> {
                        OptionChainScreen(
                            onBack = { navController.popBackStack() },
                            onOpenInstrumentDetail = { symbol ->
                                navController.navigate(InstrumentDetailRoute(symbol))
                            }
                        )
                    }
                    composable<SignalsRoute> {
                        SignalsScreen(
                            onCreateOrderFromSignal = { route ->
                                navController.navigate(route)
                            },
                            onOpenChart = { symbol, timeframe ->
                                navController.navigate(ChartRoute(symbol, timeframe))
                            }
                        )
                    }
                    composable<InstrumentDetailRoute> {
                        InstrumentDetailScreen(
                            onBack = { navController.popBackStack() },
                            onOrderPlacedNavigate = { message, goToOrdersTab ->
                                navController.popBackStack()
                                navigateTopLevel(if (goToOrdersTab) OrdersRoute else PositionsRoute)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                            onOpenOptionChain = { underlying ->
                                navController.navigate(OptionChainRoute(underlying))
                            },
                            onOpenChart = { symbol ->
                                navController.navigate(ChartRoute(symbol))
                            }
                        )
                    }
                    composable<ChartRoute> {
                        ChartScreen(
                            onBack = { navController.popBackStack() },
                            onTrade = { route -> navController.navigate(route) }
                        )
                    }
                    composable<PositionsRoute> {
                        PositionsScreen(
                            onOpenDetail = { symbol ->
                                navController.navigate(InstrumentDetailRoute(symbol))
                            }
                        )
                    }
                    composable<OrdersRoute> {
                        OrdersScreen()
                    }
                    composable<JournalRoute> {
                        JournalScreen()
                    }
                    composable<FeedSettingsRoute> {
                        FeedSettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
