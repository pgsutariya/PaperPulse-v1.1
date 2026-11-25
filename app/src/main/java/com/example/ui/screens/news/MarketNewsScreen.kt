package com.example.ui.screens.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.data.news.MarketNewsArticle
import com.example.data.news.MarketNewsCategory
import com.example.data.news.MarketNewsFeedState
import com.example.data.news.MarketNewsRepository
import com.example.data.news.WatchlistNewsGroundingEngine
import com.example.data.repository.MarketDataRepository
import com.example.domain.model.Instrument
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.MarketNewsArticleCard
import com.example.ui.components.MarketNewsHeaderStrip
import com.example.ui.components.SearchGroundingBriefingCard
import com.example.ui.components.WatchlistSymbolNewsFilterBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MarketNewsScreenUiState(
    val feedState: MarketNewsFeedState = MarketNewsFeedState(),
    val selectedCategory: MarketNewsCategory = MarketNewsCategory.ALL,
    val searchQuery: String = "",
    val watchlistOnly: Boolean = true,
    val watchlistSymbols: List<String> = emptyList(),
    val selectedSymbol: String? = null,
    val filteredArticles: List<MarketNewsArticle> = emptyList()
)

@HiltViewModel
class MarketNewsViewModel @Inject constructor(
    private val newsRepository: MarketNewsRepository,
    private val marketDataRepository: MarketDataRepository
) : ViewModel() {

    private val _selectedCategory = MutableStateFlow(MarketNewsCategory.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _watchlistOnly = MutableStateFlow(true)
    private val _selectedSymbol = MutableStateFlow<String?>(null)

    @Volatile
    private var latestWatchlist: List<Instrument> = emptyList()

    private val filterBundle = combine(
        _selectedCategory,
        _searchQuery,
        _watchlistOnly,
        _selectedSymbol
    ) { cat, query, watchlistOnly, symbol ->
        NewsFilterSnapshot(cat, query, watchlistOnly, symbol)
    }

    val uiState: StateFlow<MarketNewsScreenUiState> = combine(
        newsRepository.state,
        marketDataRepository.instruments,
        filterBundle
    ) { feed, instruments, filters ->
        latestWatchlist = instruments
        val watchlistSymbols = WatchlistNewsGroundingEngine.extractWatchlistSymbols(instruments)
        val effectiveSymbol = filters.selectedSymbol?.takeIf { sym ->
            watchlistSymbols.any { it.equals(sym, ignoreCase = true) }
        }

        val watchlistFiltered = WatchlistNewsGroundingEngine.filterArticlesForWatchlist(
            articles = feed.articles,
            watchlist = instruments,
            selectedSymbol = effectiveSymbol,
            watchlistOnly = filters.watchlistOnly
        )

        val trimmed = filters.searchQuery.trim()
        val filtered = watchlistFiltered.filter { art ->
            val catMatch = filters.category == MarketNewsCategory.ALL || art.category == filters.category
            val queryMatch = trimmed.isEmpty() ||
                art.title.contains(trimmed, ignoreCase = true) ||
                art.summary.contains(trimmed, ignoreCase = true) ||
                art.source.contains(trimmed, ignoreCase = true) ||
                art.relatedSymbols.any { it.contains(trimmed, ignoreCase = true) }
            catMatch && queryMatch
        }

        MarketNewsScreenUiState(
            feedState = feed,
            selectedCategory = filters.category,
            searchQuery = filters.searchQuery,
            watchlistOnly = filters.watchlistOnly,
            watchlistSymbols = watchlistSymbols,
            selectedSymbol = effectiveSymbol,
            filteredArticles = filtered
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MarketNewsScreenUiState()
    )

    init {
        viewModelScope.launch {
            val instruments = marketDataRepository.instruments.first { it.isNotEmpty() }
            latestWatchlist = instruments
            newsRepository.refreshForWatchlist(
                instruments = instruments,
                focusSymbol = null,
                force = false
            )
        }
    }

    fun selectCategory(category: MarketNewsCategory) {
        _selectedCategory.value = category
    }

    fun onSearchChange(query: String) {
        _searchQuery.value = query
    }

    fun setWatchlistOnly(enabled: Boolean) {
        _watchlistOnly.value = enabled
        if (!enabled) {
            _selectedSymbol.value = null
        }
    }

    fun selectSymbol(symbol: String?) {
        _selectedSymbol.value = symbol?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun refresh(focusSymbol: String? = _selectedSymbol.value, force: Boolean = true) {
        viewModelScope.launch {
            val instruments = latestWatchlist.ifEmpty {
                marketDataRepository.instruments.first()
            }
            if (instruments.isNotEmpty()) {
                newsRepository.refreshForWatchlist(
                    instruments = instruments,
                    focusSymbol = focusSymbol,
                    force = force
                )
            } else {
                newsRepository.refresh(force = force)
            }
        }
    }

    private data class NewsFilterSnapshot(
        val category: MarketNewsCategory,
        val searchQuery: String,
        val watchlistOnly: Boolean,
        val selectedSymbol: String?
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketNewsScreen(
    onBack: () -> Unit,
    onOpenSymbol: (String) -> Unit,
    viewModel: MarketNewsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Watchlist Market News", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Google Search Grounded • ${state.watchlistSymbols.size} Watchlist Symbols",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("news_screen_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh(focusSymbol = state.selectedSymbol, force = true) },
                        enabled = !state.feedState.isGroundingLoading,
                        modifier = Modifier.testTag("news_screen_ground_action")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.TravelExplore,
                            contentDescription = "Pull Search-Grounded News",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchChange,
                placeholder = { Text("Search Nifty, Reliance, Gold, Bitcoin...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("news_search_input")
            )

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "news_watchlist_filter_bar") {
                    WatchlistSymbolNewsFilterBar(
                        watchlistSymbols = state.watchlistSymbols,
                        watchlistOnly = state.watchlistOnly,
                        selectedSymbol = state.selectedSymbol,
                        onToggleWatchlistOnly = viewModel::setWatchlistOnly,
                        onSelectSymbol = viewModel::selectSymbol,
                        onSearchGroundSymbol = { sym -> viewModel.refresh(focusSymbol = sym, force = true) },
                        isGroundingLoading = state.feedState.isGroundingLoading
                    )
                }

                item(key = "news_grounding_briefing") {
                    SearchGroundingBriefingCard(
                        feedState = state.feedState,
                        onRetryGrounding = { viewModel.refresh(focusSymbol = state.selectedSymbol, force = true) },
                        compact = false
                    )
                }

                item(key = "news_header_strip") {
                    MarketNewsHeaderStrip(
                        feedState = state.feedState,
                        selectedCategory = state.selectedCategory,
                        onSelectCategory = viewModel::selectCategory,
                        onRefresh = { viewModel.refresh(focusSymbol = state.selectedSymbol, force = true) }
                    )
                }

                val isBusy = state.feedState.isLoading || state.feedState.isGroundingLoading
                if (state.filteredArticles.isEmpty() && !isBusy) {
                    item(key = "news_empty_state") {
                        EmptyStatePanel(
                            icon = Icons.Rounded.Newspaper,
                            title = when {
                                state.selectedSymbol != null -> "No news loaded yet for ${state.selectedSymbol}"
                                state.searchQuery.isNotBlank() -> "No matching news articles"
                                state.watchlistOnly -> "No headlines matched your Watchlist yet"
                                else -> "No market headlines loaded"
                            },
                            subtitle = state.feedState.groundingError
                                ?: state.feedState.errorMessage
                                ?: "Tap Ground or Refresh above to pull live Google Search-grounded headlines for your watchlist symbols."
                        )
                    }
                } else {
                    items(state.filteredArticles, key = { it.id }) { article ->
                        MarketNewsArticleCard(
                            article = article,
                            onOpenSymbol = onOpenSymbol
                        )
                    }
                }
            }
        }
    }
}
