package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.news.GroundedNewsSource
import com.example.data.news.MarketNewsArticle
import com.example.data.news.MarketNewsFeedState
import com.example.data.news.MarketNewsRepository
import com.example.ui.theme.LocalTradingColors

/**
 * Horizontal filter strip that lets traders filter news strictly to the symbols
 * currently in their Watchlist, or drill into a specific watchlist ticker with
 * one-tap Google Search Grounding.
 */
@Composable
fun WatchlistSymbolNewsFilterBar(
    watchlistSymbols: List<String>,
    watchlistOnly: Boolean,
    selectedSymbol: String?,
    onToggleWatchlistOnly: ((Boolean) -> Unit)? = null,
    onSelectSymbol: (String?) -> Unit,
    onSearchGroundSymbol: ((String?) -> Unit)? = null,
    isGroundingLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("watchlist_news_symbol_filter_bar"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onToggleWatchlistOnly != null) {
                FilterChip(
                    selected = watchlistOnly,
                    onClick = { onToggleWatchlistOnly(!watchlistOnly) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Bookmark,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Watchlist Only (${watchlistSymbols.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    modifier = Modifier.testTag("news_watchlist_only_toggle"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            FilterChip(
                selected = selectedSymbol == null,
                onClick = { onSelectSymbol(null) },
                label = {
                    Text(
                        text = if (watchlistOnly) "All Watchlist" else "All Symbols",
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                modifier = Modifier.testTag("news_symbol_filter_all"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )

            watchlistSymbols.take(18).forEach { sym ->
                val isSelected = selectedSymbol.equals(sym, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectSymbol(if (isSelected) null else sym) },
                    label = {
                        Text(
                            text = sym,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    modifier = Modifier.testTag("news_symbol_filter_${sym.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tradingColors.bullishContainer,
                        selectedLabelColor = tradingColors.bullish
                    )
                )
            }
        }

        if (selectedSymbol != null && onSearchGroundSymbol != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Filtered to $selectedSymbol from your Watchlist",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = { onSearchGroundSymbol(selectedSymbol) },
                    enabled = !isGroundingLoading,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.TravelExplore,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    label = {
                        Text(
                            text = "Ground $selectedSymbol",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    modifier = Modifier.testTag("ground_selected_symbol_chip")
                )
            }
        }
    }
}

/**
 * Displays the real-time Google Search Grounding briefing, the actual Google Search queries
 * executed by Gemini, clickable verified source links, or transparent loading/error state.
 */
@Composable
fun SearchGroundingBriefingCard(
    feedState: MarketNewsFeedState,
    onRetryGrounding: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tradingColors = LocalTradingColors.current

    val hasContent = !feedState.groundingBriefing.isNullOrBlank() ||
        feedState.groundingSearchQueries.isNotEmpty() ||
        feedState.groundingSources.isNotEmpty() ||
        feedState.isGroundingLoading ||
        !feedState.groundingError.isNullOrBlank()

    if (!hasContent) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("search_grounding_briefing_card"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.TravelExplore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "GOOGLE SEARCH GROUNDED BRIEF",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (feedState.isGroundingLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Searching web...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (feedState.lastGroundedAtMillis > 0L) {
                    Text(
                        text = MarketNewsRepository.formatRelativeTime(feedState.lastGroundedAtMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            feedState.groundingBriefing?.let { briefing ->
                Text(
                    text = briefing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (compact) 3 else 6,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (feedState.groundingError != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = tradingColors.bearishContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = tradingColors.bearish,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = feedState.groundingError,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(
                            onClick = onRetryGrounding,
                            modifier = Modifier.testTag("retry_search_grounding_button")
                        ) {
                            Text("Retry", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (feedState.groundingSearchQueries.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Google Search queries",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    feedState.groundingSearchQueries.take(6).forEach { q ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, tradingColors.border)
                        ) {
                            Text(
                                text = q,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            if (feedState.groundingSources.isNotEmpty()) {
                GroundingSourcesRow(
                    sources = feedState.groundingSources.take(if (compact) 4 else 8),
                    onOpenSource = { src ->
                        if (MarketNewsRepository.isSafeHttpUrl(src.url)) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(src.url)))
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun GroundingSourcesRow(
    sources: List<GroundedNewsSource>,
    onOpenSource: (GroundedNewsSource) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Sources:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        sources.forEach { src ->
            Surface(
                modifier = Modifier
                    .clickable { onOpenSource(src) }
                    .testTag("grounding_source_${src.domain.ifBlank { src.title }.take(20)}"),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = src.domain.ifBlank { src.title }.take(28),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = "Open grounded source ${src.title}",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

/**
 * Integrated Search-Grounded Watchlist News Feed section embedded directly inside
 * the Watchlist screen. Automatically filters news to the symbols currently in the
 * user's Watchlist and lets traders switch symbols, trigger Google Search Grounding,
 * open full articles, or jump straight to trading a mentioned symbol.
 */
@Composable
fun WatchlistNewsFeedSection(
    feedState: MarketNewsFeedState,
    watchlistSymbols: List<String>,
    selectedNewsSymbol: String?,
    articles: List<MarketNewsArticle>,
    onSelectNewsSymbol: (String?) -> Unit,
    onRefreshGrounded: (String?) -> Unit,
    onOpenSymbol: (String) -> Unit,
    onOpenFullFeed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    var expanded by rememberSaveable { mutableStateOf(true) }
    val groundedCount = articles.count { it.isSearchGrounded }
    val isBusy = feedState.isGroundingLoading || feedState.isLoading

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("watchlist_news_feed_section"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded }
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(7.dp)
                                .size(18.dp)
                        )
                    }
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "WATCHLIST NEWS FEED",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = tradingColors.bullishContainer
                            ) {
                                Text(
                                    text = "SEARCH GROUNDED",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = tradingColors.bullish,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        val subtitle = when {
                            feedState.isGroundingLoading -> "Grounding live news for ${selectedNewsSymbol ?: "${watchlistSymbols.size} watchlist symbols"}..."
                            articles.isNotEmpty() -> "${articles.size} matched stories ($groundedCount search-grounded) • ${watchlistSymbols.size} symbols"
                            else -> "Filtered to your ${watchlistSymbols.size} watchlist symbols"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onRefreshGrounded(selectedNewsSymbol) },
                        enabled = !isBusy,
                        modifier = Modifier.testTag("watchlist_news_refresh_button")
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Pull search-grounded watchlist news"
                            )
                        }
                    }
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.testTag("watchlist_news_section_toggle")
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (expanded) "Collapse Watchlist News" else "Expand Watchlist News"
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WatchlistSymbolNewsFilterBar(
                        watchlistSymbols = watchlistSymbols,
                        watchlistOnly = true,
                        selectedSymbol = selectedNewsSymbol,
                        onSelectSymbol = onSelectNewsSymbol,
                        onSearchGroundSymbol = onRefreshGrounded,
                        isGroundingLoading = feedState.isGroundingLoading
                    )

                    SearchGroundingBriefingCard(
                        feedState = feedState,
                        onRetryGrounding = { onRefreshGrounded(selectedNewsSymbol) },
                        compact = true
                    )

                    if (articles.isEmpty() && !isBusy) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Newspaper,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (selectedNewsSymbol != null) {
                                            "No headlines yet for $selectedNewsSymbol"
                                        } else {
                                            "Ready to ground watchlist news"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Tap Refresh above to query Google Search for your current watchlist symbols.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        articles.take(3).forEach { article ->
                            MarketNewsArticleCard(
                                article = article,
                                onOpenSymbol = onOpenSymbol
                            )
                        }
                    }

                    HorizontalDivider(color = tradingColors.border)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onRefreshGrounded(selectedNewsSymbol) },
                            enabled = !isBusy,
                            modifier = Modifier.testTag("watchlist_news_ground_now_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.TravelExplore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (selectedNewsSymbol != null) "Ground $selectedNewsSymbol" else "Search-Ground All",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        TextButton(
                            onClick = onOpenFullFeed,
                            modifier = Modifier.testTag("watchlist_news_open_all_button")
                        ) {
                            Text(
                                text = if (articles.size > 3) "View All (${articles.size})" else "Full News Feed",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
