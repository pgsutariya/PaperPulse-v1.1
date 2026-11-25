package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.news.MarketNewsArticle
import com.example.data.news.MarketNewsCategory
import com.example.data.news.MarketNewsFeedState
import com.example.data.news.MarketNewsRepository
import com.example.data.news.NewsSentiment
import com.example.ui.theme.LocalTradingColors

val PRESET_TRADE_TAGS = listOf(
    "Breakout",
    "Reversal",
    "News",
    "SMC",
    "Momentum",
    "Scalp",
    "Trend",
    "Pullback",
    "Earnings"
)

@Composable
fun TradeTagFilterBar(
    totalTradeCount: Int,
    tagCounts: Map<String, Int>,
    selectedTag: String?,
    onSelectTag: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val sortedTags = remember(tagCounts) {
        tagCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("journal_tag_filter_bar"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LocalOffer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "FILTER BY TRADE TAG",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selectedTag != null) {
                    TextButton(
                        onClick = { onSelectTag(null) },
                        modifier = Modifier.testTag("clear_tag_filter_button")
                    ) {
                        Text("Clear (#$selectedTag)", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selectedTag == null,
                    onClick = { onSelectTag(null) },
                    label = { Text("All ($totalTradeCount)", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("tag_filter_all"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )

                val displayTags = if (sortedTags.isNotEmpty()) {
                    sortedTags
                } else {
                    listOf("Breakout", "Reversal", "News")
                }

                displayTags.forEach { tag ->
                    val count = tagCounts[tag] ?: 0
                    val isSelected = selectedTag.equals(tag, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectTag(if (isSelected) null else tag) },
                        label = {
                            Text(
                                text = if (count > 0) "#$tag ($count)" else "#$tag",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.testTag("tag_filter_${tag.lowercase().replace(" ", "_")}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TradeTagEditorDialog(
    symbol: String,
    currentTags: List<String>,
    suggestedTags: List<String>,
    onToggleTag: (String) -> Unit,
    onAddCustomTag: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var customTagInput by remember { mutableStateOf("") }
    val mergedSuggestions = remember(suggestedTags, currentTags) {
        val seen = LinkedHashSet<String>()
        val list = mutableListOf<String>()
        (PRESET_TRADE_TAGS + suggestedTags + currentTags).forEach { tag ->
            val clean = tag.trim()
            if (clean.isNotEmpty() && seen.add(clean.lowercase())) {
                list.add(clean)
            }
        }
        list
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("trade_tag_editor_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Rounded.LocalOffer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Tag $symbol Trade", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Tap preset labels or add your own custom strategy tag:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    mergedSuggestions.forEach { tag ->
                        val isAssigned = currentTags.any { it.equals(tag, ignoreCase = true) }
                        FilterChip(
                            selected = isAssigned,
                            onClick = { onToggleTag(tag) },
                            leadingIcon = if (isAssigned) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else null,
                            label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("tag_picker_chip_${tag.lowercase().replace(" ", "_")}"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customTagInput,
                        onValueChange = { if (it.length <= 28) customTagInput = it },
                        label = { Text("Custom Tag") },
                        placeholder = { Text("e.g. Gap Up, VWAP") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_tag_input")
                    )
                    Button(
                        onClick = {
                            val trimmed = customTagInput.trim()
                            if (trimmed.isNotEmpty()) {
                                onAddCustomTag(trimmed)
                                customTagInput = ""
                            }
                        },
                        enabled = customTagInput.trim().isNotEmpty(),
                        modifier = Modifier.testTag("custom_tag_add_button")
                    ) {
                        Text("Add")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("trade_tag_dialog_done")
            ) {
                Text("Done")
            }
        }
    )
}

@Composable
fun MarketNewsHeaderStrip(
    feedState: MarketNewsFeedState,
    selectedCategory: MarketNewsCategory,
    onSelectCategory: (MarketNewsCategory) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("market_news_header_strip"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
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
                        imageVector = Icons.Rounded.Newspaper,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "LIVE MARKET NEWS FEED",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val isBusy = feedState.isLoading || feedState.isGroundingLoading
                        val groundedCount = feedState.articles.count { it.isSearchGrounded }
                        val statusText = when {
                            feedState.isGroundingLoading -> "Running Google Search Grounding for your Watchlist..."
                            feedState.isLoading -> "Fetching live NSE, MCX & Crypto headlines..."
                            feedState.lastUpdatedMillis > 0L ->
                                "${feedState.articles.size} headlines ($groundedCount grounded) • Updated ${MarketNewsRepository.formatRelativeTime(feedState.lastUpdatedMillis)}"
                            else -> "Google Search Grounded + Live Financial Feeds"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val isBusy = feedState.isLoading || feedState.isGroundingLoading
                IconButton(
                    onClick = onRefresh,
                    enabled = !isBusy,
                    modifier = Modifier.testTag("market_news_refresh_button")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh Market News"
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MarketNewsCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { onSelectCategory(cat) },
                        label = { Text(cat.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("news_category_${cat.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            feedState.errorMessage?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.labelSmall,
                    color = tradingColors.amber
                )
            }
        }
    }
}

@Composable
fun MarketNewsArticleCard(
    article: MarketNewsArticle,
    onOpenSymbol: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tradingColors = LocalTradingColors.current

    fun openArticleUrl() {
        if (!MarketNewsRepository.isSafeHttpUrl(article.url)) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(article.url))
            context.startActivity(intent)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = ::openArticleUrl)
            .testTag("news_article_card_${article.id}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (article.isSearchGrounded) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = tradingColors.bullishContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.TravelExplore,
                                    contentDescription = "Google Search Grounded",
                                    tint = tradingColors.bullish,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "Grounded",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tradingColors.bullish,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = article.category.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (article.isSearchGrounded && article.sentiment != NewsSentiment.NEUTRAL) {
                        val (sentBg, sentFg) = when (article.sentiment) {
                            NewsSentiment.BULLISH -> tradingColors.bullishContainer to tradingColors.bullish
                            NewsSentiment.BEARISH -> tradingColors.bearishContainer to tradingColors.bearish
                            NewsSentiment.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = sentBg
                        ) {
                            Text(
                                text = article.sentiment.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = sentFg,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = article.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = article.publishedLabel ?: MarketNewsRepository.formatRelativeTime(article.publishedAtMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (MarketNewsRepository.isSafeHttpUrl(article.url)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = "Open full article",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Text(
                text = article.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (article.summary.isNotBlank()) {
                Text(
                    text = article.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (article.relatedSymbols.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    article.relatedSymbols.forEach { sym ->
                        AssistChip(
                            onClick = { onOpenSymbol(sym) },
                            label = {
                                Text(
                                    text = "Trade $sym",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            modifier = Modifier.testTag("news_symbol_chip_${sym.lowercase()}")
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TradeTagPill(
    tag: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
        ) {
            Text(
                text = "#$tag",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove tag $tag",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onRemove)
                    .padding(1.dp)
            )
        }
    }
}

@Composable
fun AddTradeTagButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, LocalTradingColors.current.border)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "Tag",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
