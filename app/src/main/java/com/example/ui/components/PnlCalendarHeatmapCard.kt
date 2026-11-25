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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.domain.engine.RiskMath
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CalendarDaySentiment {
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
    NO_TRADE
}

data class CalendarHeatmapDay(
    val date: LocalDate,
    val netPnl: BigDecimal,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val sentiment: CalendarDaySentiment,
    val intensity: Float // 0f..1f magnitude relative to month max
)

data class MonthlyHeatmapData(
    val yearMonth: YearMonth = YearMonth.now(),
    val monthLabel: String = "",
    val leadingEmptyCells: Int = 0, // 0 = Monday .. 6 = Sunday
    val days: List<CalendarHeatmapDay> = emptyList(),
    val positiveDays: Int = 0,
    val negativeDays: Int = 0,
    val neutralDays: Int = 0,
    val monthNetPnl: BigDecimal = BigDecimal.ZERO
) {
    val activeTradingDays: Int get() = positiveDays + negativeDays + neutralDays
    val consistencyPercent: Int
        get() = if (activeTradingDays == 0) 0 else (positiveDays * 100) / activeTradingDays
}

private val fullDateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
private val weekdayHeaders = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
fun PnlCalendarHeatmapCard(
    indiaHeatmap: MonthlyHeatmapData,
    cryptoHeatmap: MonthlyHeatmapData,
    canGoNextMonth: Boolean,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current

    var userSelectedWallet by remember { mutableStateOf<Boolean?>(null) }
    val showCrypto = userSelectedWallet
        ?: (indiaHeatmap.activeTradingDays == 0 && cryptoHeatmap.activeTradingDays > 0)
    val heatmap = if (showCrypto) cryptoHeatmap else indiaHeatmap

    var selectedDate by remember(heatmap.yearMonth, showCrypto) {
        mutableStateOf(
            heatmap.days.lastOrNull { it.tradeCount > 0 }?.date
                ?: heatmap.days.lastOrNull()?.date
        )
    }

    fun formatMoney(v: BigDecimal, signed: Boolean = true): String =
        if (showCrypto) RiskMath.formatPortfolioUsd(v, signed = signed)
        else RiskMath.formatPortfolioInr(v, signed = signed)

    val selectedDay = remember(selectedDate, heatmap.days) {
        selectedDate?.let { target -> heatmap.days.firstOrNull { it.date == target } }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("journal_calendar_heatmap_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Title + Month Net P&L + INR/USD toggle
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
                            imageVector = Icons.Rounded.CalendarMonth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "P&L CALENDAR HEATMAP",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val netColor = when {
                        heatmap.monthNetPnl > BigDecimal.ZERO -> tradingColors.bullish
                        heatmap.monthNetPnl < BigDecimal.ZERO -> tradingColors.bearish
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = formatMoney(heatmap.monthNetPnl, signed = true),
                        style = MonoPriceMedium,
                        fontWeight = FontWeight.Bold,
                        color = netColor
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !showCrypto,
                        onClick = { userSelectedWallet = false },
                        label = { Text("₹ INR", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("heatmap_wallet_inr"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    FilterChip(
                        selected = showCrypto,
                        onClick = { userSelectedWallet = true },
                        label = { Text("$ USD", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("heatmap_wallet_usd"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // Month Navigation Bar
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onPreviousMonth,
                        modifier = Modifier.testTag("heatmap_prev_month")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = "Previous Month"
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = heatmap.monthLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (heatmap.activeTradingDays > 0) {
                                "Consistency: ${heatmap.consistencyPercent}% green (${heatmap.positiveDays}W / ${heatmap.negativeDays}L)"
                            } else {
                                "No closed trades this month"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onNextMonth,
                        enabled = canGoNextMonth,
                        modifier = Modifier.testTag("heatmap_next_month")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Next Month"
                        )
                    }
                }
            }

            // Weekday Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                weekdayHeaders.forEach { dayName ->
                    Text(
                        text = dayName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 7-Column Calendar Heatmap Grid
            val totalCells = heatmap.leadingEmptyCells + heatmap.days.size
            val rowCount = (totalCells + 6) / 7

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (rowIdx in 0 until rowCount) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (colIdx in 0 until 7) {
                            val cellIndex = rowIdx * 7 + colIdx
                            val dayIndex = cellIndex - heatmap.leadingEmptyCells
                            val day = heatmap.days.getOrNull(dayIndex)
                            if (day == null) {
                                Spacer(modifier = Modifier.weight(1f).height(38.dp))
                            } else {
                                HeatmapDayCell(
                                    day = day,
                                    isSelected = selectedDate == day.date,
                                    formattedPnl = formatMoney(day.netPnl, signed = true),
                                    onClick = { selectedDate = day.date },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Selected Day Details Strip
            if (selectedDay != null) {
                val dayColor = when (selectedDay.sentiment) {
                    CalendarDaySentiment.POSITIVE -> tradingColors.bullish
                    CalendarDaySentiment.NEGATIVE -> tradingColors.bearish
                    CalendarDaySentiment.NEUTRAL -> tradingColors.amber
                    CalendarDaySentiment.NO_TRADE -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, tradingColors.border.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("heatmap_selected_day_details")
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
                                text = selectedDay.date.format(fullDateFmt),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when (selectedDay.sentiment) {
                                    CalendarDaySentiment.NO_TRADE -> "No closed trades recorded"
                                    CalendarDaySentiment.NEUTRAL -> "${selectedDay.tradeCount} trade(s) • Break-even"
                                    else -> "${selectedDay.tradeCount} trade(s) • ${selectedDay.wins}W / ${selectedDay.losses}L"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = if (selectedDay.tradeCount == 0) "—" else formatMoney(selectedDay.netPnl, signed = true),
                            style = MonoPriceSmall,
                            color = dayColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Color Legend Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeatmapLegendChip(tradingColors.bullish.copy(alpha = 0.72f), "Positive (${heatmap.positiveDays})")
                HeatmapLegendChip(tradingColors.bearish.copy(alpha = 0.72f), "Negative (${heatmap.negativeDays})")
                HeatmapLegendChip(tradingColors.amber.copy(alpha = 0.45f), "Neutral (${heatmap.neutralDays})")
                HeatmapLegendChip(MaterialTheme.colorScheme.surfaceContainerHigh, "No Trade")
            }
        }
    }
}

@Composable
private fun HeatmapDayCell(
    day: CalendarHeatmapDay,
    isSelected: Boolean,
    formattedPnl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val alpha = (0.24f + day.intensity.coerceIn(0f, 1f) * 0.62f).coerceIn(0.24f, 0.86f)

    val bgColor = when (day.sentiment) {
        CalendarDaySentiment.POSITIVE -> tradingColors.bullish.copy(alpha = alpha)
        CalendarDaySentiment.NEGATIVE -> tradingColors.bearish.copy(alpha = alpha)
        CalendarDaySentiment.NEUTRAL -> tradingColors.amber.copy(alpha = 0.34f)
        CalendarDaySentiment.NO_TRADE -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    val textColor = when {
        day.sentiment == CalendarDaySentiment.POSITIVE && day.intensity > 0.45f -> Color.White
        day.sentiment == CalendarDaySentiment.NEGATIVE && day.intensity > 0.45f -> Color.White
        day.sentiment == CalendarDaySentiment.NO_TRADE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    val dotColor = when (day.sentiment) {
        CalendarDaySentiment.POSITIVE -> if (day.intensity > 0.45f) Color.White else tradingColors.bullish
        CalendarDaySentiment.NEGATIVE -> if (day.intensity > 0.45f) Color.White else tradingColors.bearish
        CalendarDaySentiment.NEUTRAL -> tradingColors.amber
        CalendarDaySentiment.NO_TRADE -> Color.Transparent
    }

    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else if (day.tradeCount > 0) {
        Color.Transparent
    } else {
        tradingColors.border.copy(alpha = 0.35f)
    }

    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(
                width = if (isSelected) 2.dp else 0.75.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${day.date.format(fullDateFmt)}, ${day.sentiment.name.lowercase()}, ${if (day.tradeCount > 0) formattedPnl else "no trades"}"
            }
            .testTag("heatmap_day_${day.date}"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                fontWeight = if (day.tradeCount > 0 || isSelected) FontWeight.Bold else FontWeight.Normal,
                color = textColor
            )
            if (day.tradeCount > 0) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }
    }
}

@Composable
private fun HeatmapLegendChip(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
