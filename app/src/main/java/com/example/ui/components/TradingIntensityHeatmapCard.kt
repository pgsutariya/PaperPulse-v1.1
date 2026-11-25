package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.analytics.HourlyTradingIntensity
import com.example.domain.analytics.TradingIntensityHeatmapData
import com.example.domain.engine.RiskMath
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal

@Composable
fun TradingIntensityHeatmapCard(
    indiaHeatmap: TradingIntensityHeatmapData,
    cryptoHeatmap: TradingIntensityHeatmapData,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current

    var userSelectedWallet by remember { mutableStateOf<Boolean?>(null) }
    val showCrypto = userSelectedWallet
        ?: (indiaHeatmap.totalTrades == 0 && cryptoHeatmap.totalTrades > 0)
    val data = if (showCrypto) cryptoHeatmap else indiaHeatmap

    var selectedHour by remember(showCrypto, data.totalTrades) {
        mutableStateOf(data.peakHour?.hour ?: 9)
    }

    val selectedCell = remember(selectedHour, data.hours) {
        data.hours.firstOrNull { it.hour == selectedHour } ?: data.hours.firstOrNull()
    }

    fun formatMoney(v: BigDecimal): String =
        if (showCrypto) RiskMath.formatPortfolioUsd(v, signed = true)
        else RiskMath.formatPortfolioInr(v, signed = true)

    val colorScheme = MaterialTheme.colorScheme

    fun heatColorForLevel(level: Int): Color {
        val accent = colorScheme.primary
        return when (level) {
            0 -> colorScheme.surfaceContainerLow
            1 -> accent.copy(alpha = 0.22f)
            2 -> accent.copy(alpha = 0.45f)
            3 -> tradingColors.amber.copy(alpha = 0.72f)
            else -> tradingColors.bullish.copy(alpha = 0.88f)
        }
    }

    fun textColorForLevel(level: Int): Color {
        return when (level) {
            0 -> colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            1 -> colorScheme.onSurface
            2 -> colorScheme.onSurface
            3 -> Color.Black.copy(alpha = 0.88f)
            else -> Color.White
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("journal_trading_intensity_heatmap_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header + INR/USD toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocalFireDepartment,
                            contentDescription = null,
                            tint = tradingColors.amber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "TRADING INTENSITY HEATMAP",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (data.totalTrades > 0) {
                            "${data.totalTrades} trades across ${data.activeHoursCount} active hours (IST)"
                        } else {
                            "Hourly trade frequency (00:00–23:00 IST)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = !showCrypto,
                        onClick = { userSelectedWallet = false },
                        label = { Text("₹ INR", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("intensity_wallet_inr"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    FilterChip(
                        selected = showCrypto,
                        onClick = { userSelectedWallet = true },
                        label = { Text("$ USD", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("intensity_wallet_usd"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // Peak Active Window highlight strip
            if (data.totalTrades > 0) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Peak Window: ${data.peakWindowLabel}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "${data.peakWindowTradeCount} trades (${data.peakWindowSharePercent}%)",
                            style = MonoPriceSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // 4 rows x 6 columns = 24-hour color-coded grid
            val rows = remember(data.hours) { data.hours.chunked(6) }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                rows.forEach { rowCells ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        rowCells.forEach { cell ->
                            val isSelected = selectedHour == cell.hour
                            val bg = heatColorForLevel(cell.level)
                            val fg = textColorForLevel(cell.level)
                            val borderColor = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                tradingColors.border.copy(alpha = 0.6f)
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.75.dp,
                                        color = borderColor,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedHour = cell.hour }
                                    .semantics {
                                        contentDescription =
                                            "Hour ${cell.hourLabel}, ${cell.tradeCount} trades"
                                    }
                                    .testTag("intensity_hour_${cell.hour}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = cell.shortLabel,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = fg,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = if (cell.tradeCount > 0) "${cell.tradeCount}" else "·",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = fg,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Selected hour detail inspector
            if (selectedCell != null) {
                SelectedHourDetailStrip(
                    cell = selectedCell,
                    formattedPnl = formatMoney(selectedCell.netPnl)
                )
            }

            // Legend (Less -> More intensity)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (data.peakHour != null) {
                        "Busiest: ${data.peakHour.hourLabel} (${data.peakHour.tradeCount} trades)"
                    } else {
                        "Close trades to map active hours"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Less",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    (0..4).forEach { lvl ->
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(heatColorForLevel(lvl))
                                .border(0.5.dp, tradingColors.border, RoundedCornerShape(3.dp))
                        )
                    }
                    Text(
                        text = "More",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedHourDetailStrip(
    cell: HourlyTradingIntensity,
    formattedPnl: String
) {
    val tradingColors = LocalTradingColors.current
    val nextHour = (cell.hour + 1) % 24
    val rangeLabel = String.format(java.util.Locale.US, "%02d:00–%02d:00 IST", cell.hour, nextHour)
    val pnlColor = when {
        cell.netPnl > BigDecimal.ZERO -> tradingColors.bullish
        cell.netPnl < BigDecimal.ZERO -> tradingColors.bearish
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, tradingColors.border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("intensity_selected_hour_detail")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = rangeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (cell.tradeCount > 0) {
                        "${cell.tradeCount} trades • ${cell.wins}W / ${cell.losses}L (${cell.winRatePercent}% Win)"
                    } else {
                        "No trades initiated in this hour"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (cell.tradeCount > 0) {
                Text(
                    text = formattedPnl,
                    style = MonoPriceSmall,
                    fontWeight = FontWeight.Bold,
                    color = pnlColor
                )
            }
        }
    }
}
