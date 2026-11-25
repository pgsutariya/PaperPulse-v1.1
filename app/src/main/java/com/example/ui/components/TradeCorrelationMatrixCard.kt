package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.analytics.CorrelationCell
import com.example.domain.analytics.TradeCorrelationMatrix
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.util.Locale
import kotlin.math.abs

@Composable
fun TradeCorrelationMatrixCard(
    indiaMatrix: TradeCorrelationMatrix,
    cryptoMatrix: TradeCorrelationMatrix,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val colorScheme = MaterialTheme.colorScheme

    var userSelectedWallet by remember { mutableStateOf<Boolean?>(null) }
    val showCrypto = userSelectedWallet
        ?: (indiaMatrix.totalTradesAnalyzed == 0 && cryptoMatrix.totalTradesAnalyzed > 0)
    val matrix = if (showCrypto) cryptoMatrix else indiaMatrix

    var selectedCoords by remember(showCrypto, matrix.symbols) {
        val defaultPair = if (matrix.symbols.size >= 2) 0 to 1 else 0 to 0
        mutableStateOf(defaultPair)
    }

    val selectedCell: CorrelationCell? = remember(selectedCoords, matrix.cells) {
        matrix.cells.getOrNull(selectedCoords.first)?.getOrNull(selectedCoords.second)
    }

    fun shortLabel(symbol: String): String {
        val cleaned = symbol.substringAfter(":").replace("-EQ", "").replace("USDT", "")
        return if (cleaned.length <= 7) cleaned else cleaned.take(6) + "…"
    }

    fun cellBgColor(cell: CorrelationCell): Color {
        if (cell.isDiagonal) return colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
        val r = cell.correlation ?: return colorScheme.surfaceContainerLow
        return when {
            r >= 0.70 -> tradingColors.bearish.copy(alpha = 0.78f)
            r >= 0.35 -> tradingColors.amber.copy(alpha = 0.52f)
            r <= -0.65 -> tradingColors.bullish.copy(alpha = 0.72f)
            r <= -0.30 -> Color(0xFF06B6D4).copy(alpha = 0.42f)
            else -> colorScheme.surfaceContainerLow
        }
    }

    fun cellTextColor(cell: CorrelationCell): Color {
        if (cell.isDiagonal) return colorScheme.onSurfaceVariant
        val r = cell.correlation ?: return colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        return when {
            r >= 0.70 -> Color.White
            r <= -0.65 -> Color.White
            else -> colorScheme.onSurface
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("journal_trade_correlation_matrix_card"),
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surface,
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
                            imageVector = Icons.Rounded.GridOn,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "TRADE CORRELATION MATRIX",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (matrix.symbols.size >= 2) {
                            "${matrix.symbols.size} instruments • ${matrix.bucketModeLabel} co-movement"
                        } else {
                            "Trade 2+ symbols to map sector & asset correlation"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = !showCrypto,
                        onClick = { userSelectedWallet = false },
                        label = { Text("₹ INR", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("corr_wallet_inr"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorScheme.primaryContainer,
                            selectedLabelColor = colorScheme.primary
                        )
                    )
                    FilterChip(
                        selected = showCrypto,
                        onClick = { userSelectedWallet = true },
                        label = { Text("$ USD", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("corr_wallet_usd"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorScheme.primaryContainer,
                            selectedLabelColor = colorScheme.primary
                        )
                    )
                }
            }

            // Over-Exposure Warning Banner (Sector >= 50% or Highly Correlated Pair >= +0.65)
            val overExposedSector = matrix.sectorExposures.firstOrNull { it.isOverExposed }
            val topRiskPair = matrix.highCorrelationAlerts.firstOrNull { !it.isHedge }

            if (overExposedSector != null || topRiskPair != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = tradingColors.amber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, tradingColors.amber.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("corr_overexposure_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = "Concentration warning",
                            tint = tradingColors.amber,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            overExposedSector?.let { sec ->
                                Text(
                                    text = "Sector Concentration: ${sec.sector} (${sec.sharePercent}% of trades)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onSurface
                                )
                            }
                            topRiskPair?.let { pair ->
                                Text(
                                    text = "High Co-Movement: ${shortLabel(pair.symbolA)} & ${shortLabel(pair.symbolB)} (r = ${String.format(Locale.US, "%+.2f", pair.correlation)})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Interactive N x N Matrix Grid
            if (matrix.symbols.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Close trades across 2 or more instruments to visualize P&L correlations and detect sector over-exposure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                val cellSize = 48.dp
                val rowHeaderW = 68.dp

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Column headers
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(Modifier.width(rowHeaderW))
                            matrix.symbols.forEach { colSym ->
                                Text(
                                    text = shortLabel(colSym),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(cellSize)
                                )
                            }
                        }

                        // Rows
                        matrix.cells.forEachIndexed { rIdx, rowCells ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = shortLabel(matrix.symbols[rIdx]),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(rowHeaderW)
                                )
                                rowCells.forEachIndexed { cIdx, cell ->
                                    val isSelected = selectedCoords == (rIdx to cIdx)
                                    val label = when {
                                        cell.isDiagonal -> "1.00"
                                        cell.correlation != null -> String.format(Locale.US, "%+.2f", cell.correlation)
                                        else -> "—"
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(cellSize)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(cellBgColor(cell))
                                            .border(
                                                width = if (isSelected) 1.6.dp else 0.75.dp,
                                                color = if (isSelected) colorScheme.primary else tradingColors.border.copy(alpha = 0.65f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable { selectedCoords = rIdx to cIdx }
                                            .semantics {
                                                contentDescription = "${cell.rowSymbol} vs ${cell.colSymbol}, correlation $label"
                                            }
                                            .testTag("corr_cell_${rIdx}_${cIdx}"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (cell.isDiagonal) FontWeight.Medium else FontWeight.Bold,
                                            color = cellTextColor(cell),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Selected Cell Inspector
                selectedCell?.let { cell ->
                    val r = cell.correlation
                    val secA = matrix.sectorsBySymbol[cell.rowSymbol] ?: "Equities"
                    val secB = matrix.sectorsBySymbol[cell.colSymbol] ?: "Equities"
                    val interpretation = when {
                        cell.isDiagonal -> "Self-correlation • ${matrix.tradeCountBySymbol[cell.rowSymbol] ?: 0} trades in $secA"
                        r == null -> "Insufficient overlapping sessions (${cell.sharedPeriods}) to estimate correlation."
                        r >= 0.65 -> "Strong positive co-movement — avoid stacking full size in both simultaneously."
                        r >= 0.30 -> "Moderate positive correlation — watch combined risk exposure."
                        r <= -0.65 -> "Strong inverse movement — acts as a natural portfolio hedge."
                        r <= -0.30 -> "Mild inverse correlation — helps smooth equity curve drawdowns."
                        else -> "Low correlation — healthy diversification between these instruments."
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, tradingColors.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("corr_selected_cell_detail")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (cell.isDiagonal) cell.rowSymbol else "${shortLabel(cell.rowSymbol)} × ${shortLabel(cell.colSymbol)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onSurface
                                )
                                Text(
                                    text = r?.let { "r = ${String.format(Locale.US, "%+.2f", it)}" } ?: "r = N/A",
                                    style = MonoPriceSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        r == null || cell.isDiagonal -> colorScheme.onSurfaceVariant
                                        r >= 0.65 -> tradingColors.bearish
                                        r <= -0.30 -> tradingColors.bullish
                                        else -> colorScheme.primary
                                    }
                                )
                            }
                            Text(
                                text = "$secA${if (!cell.isDiagonal) " vs $secB" else ""} • ${cell.sharedPeriods} shared periods",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = interpretation,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurface
                            )
                        }
                    }
                }

                // Sector Exposure Concentration Bars
                if (matrix.sectorExposures.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "SECTOR EXPOSURE BREAKDOWN",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurfaceVariant
                        )
                        matrix.sectorExposures.take(4).forEach { sec ->
                            val barColor = if (sec.isOverExposed) tradingColors.amber else colorScheme.primary
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (sec.isOverExposed) "${sec.sector} (High)" else sec.sector,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (sec.isOverExposed) FontWeight.Bold else FontWeight.Medium,
                                        color = if (sec.isOverExposed) tradingColors.amber else colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${sec.tradeCount} trades (${sec.sharePercent}%)",
                                        style = MonoPriceSmall,
                                        color = colorScheme.onSurfaceVariant
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (sec.sharePercent / 100f).coerceIn(0.02f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = barColor,
                                    trackColor = colorScheme.surfaceContainerHighest
                                )
                            }
                        }
                    }
                }

                // Color Scale Legend
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = matrix.avgAbsCorrelation?.let {
                            "Avg |r|: ${String.format(Locale.US, "%.2f", it)}"
                        } ?: "Tap any cell to inspect",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LegendSwatch("-1 Hedge", tradingColors.bullish.copy(alpha = 0.72f))
                        LegendSwatch("0 Neutral", colorScheme.surfaceContainerLow)
                        LegendSwatch("+1 Crowded", tradingColors.bearish.copy(alpha = 0.78f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendSwatch(label: String, color: Color) {
    val tradingColors = LocalTradingColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
                .border(0.5.dp, tradingColors.border, RoundedCornerShape(2.dp))
        )
        Text(
            text = label,
            fontSize = 9.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
