package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.market.GlobalMarketQuote
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.util.Locale

private val HEADER_PRIORITY_INDICES = listOf(
    "NIFTY 50",
    "SENSEX",
    "GIFT NIFTY",
    "DOW JONES",
    "NASDAQ",
    "S&P 500",
    "FTSE 100",
    "NIKKEI 225"
)

/**
 * Compact, live-updating Global Markets ticker widget pinned to the dashboard top header,
 * surfacing NIFTY 50, SENSEX, GIFT NIFTY, DOW JONES, NASDAQ, S&P 500, FTSE 100, and NIKKEI 225
 * with color-coded percentage change badges.
 */
@Composable
fun GlobalMarketsHeaderStrip(
    quotes: List<GlobalMarketQuote>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenGlobalTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current

    val orderedQuotes = remember(quotes) {
        val bySym = quotes.associateBy { it.symbol.uppercase(Locale.US) }
        val primary = HEADER_PRIORITY_INDICES.mapNotNull { bySym[it] }
        if (primary.isNotEmpty()) primary else quotes.take(8)
    }

    if (orderedQuotes.isEmpty()) return

    val liveCount = orderedQuotes.count { it.isLive }
    val advancing = orderedQuotes.count { it.changePercent >= 0.0 }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("global_markets_header_widget"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Top bar: Title + Live Status + Refresh + "All Global ->"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenGlobalTab)
                        .padding(vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                        tint = tradingColors.cryptoCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "GLOBAL MARKETS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (liveCount > 0) tradingColors.bullish else tradingColors.amber)
                    )
                    Text(
                        text = "$advancing/${orderedQuotes.size} Up",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (advancing >= orderedQuotes.size / 2) tradingColors.bullish else tradingColors.bearish,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "All →",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpenGlobalTab)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("global_header_open_all")
                    )
                    IconButton(
                        onClick = onRefresh,
                        enabled = !isRefreshing,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("global_header_refresh")
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh Global Markets",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Horizontal Scrollable Index Tiles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                orderedQuotes.forEach { quote ->
                    GlobalHeaderIndexPill(
                        quote = quote,
                        onClick = onOpenGlobalTab
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalHeaderIndexPill(
    quote: GlobalMarketQuote,
    onClick: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val isUp = quote.changePercent >= 0.0
    val accent = if (isUp) tradingColors.bullish else tradingColors.bearish
    val container = if (isUp) tradingColors.bullishContainer else tradingColors.bearishContainer

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("global_header_tile_${quote.symbol.lowercase().replace(" ", "_").replace("&", "")}"),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = quote.symbol,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = container
                ) {
                    Text(
                        text = String.format(Locale.US, "%+.2f%%", quote.changePercent),
                        style = MonoPriceSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        maxLines = 1
                    )
                }
            }
            Text(
                text = "${quote.currency}${String.format(Locale.US, "%,.2f", quote.price)}",
                style = MonoPriceSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
