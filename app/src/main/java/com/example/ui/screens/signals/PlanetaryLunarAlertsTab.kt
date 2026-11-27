package com.example.ui.screens.signals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.analysis.AstroCalendarDayCell
import com.example.domain.analysis.AstroCalendarEvent
import com.example.domain.analysis.AstroCycleSnapshot
import com.example.domain.analysis.AstroEventCategory
import com.example.domain.analysis.AstroEventPerformanceStat
import com.example.domain.analysis.AstroImpactLevel
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AstroAlertsUiState(
    val today: LocalDate = LocalDate.now(),
    val displayedMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedCategory: AstroEventCategory? = null,
    val calendarCells: List<AstroCalendarDayCell> = emptyList(),
    val selectedDateSnapshot: AstroCycleSnapshot? = null,
    val selectedDateEvents: List<AstroCalendarEvent> = emptyList(),
    val upcomingEvents: List<AstroCalendarEvent> = emptyList(),
    val nextImminentEvent: AstroCalendarEvent? = null,
    val performanceStats: List<AstroEventPerformanceStat> = emptyList(),
    val mutedEventIds: Set<String> = emptySet(),
    val upcomingCount: Int = 0
)

private val MONTH_YEAR_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val SHORT_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.US)

@Composable
private fun categoryAccentColor(cat: AstroEventCategory): Color {
    val tradingColors = LocalTradingColors.current
    return when (cat) {
        AstroEventCategory.FULL_MOON -> tradingColors.amber
        AstroEventCategory.NEW_MOON -> MaterialTheme.colorScheme.primary
        AstroEventCategory.MERCURY_RETROGRADE -> tradingColors.bearish
        AstroEventCategory.MAJOR_ASPECT -> tradingColors.violet
        AstroEventCategory.LUNAR_QUARTER -> tradingColors.cryptoCyan
    }
}

@Composable
fun PlanetaryLunarAlertsTab(
    state: AstroAlertsUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onJumpToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onSelectCategory: (AstroEventCategory?) -> Unit,
    onToggleEventAlert: (String) -> Unit,
    onApplyEventFilter: (AstroCalendarEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("planetary_lunar_alerts_tab"),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Imminent Planetary / Lunar Alert Banner
        state.nextImminentEvent?.let { nextEv ->
            item(key = "next_imminent") {
                NextAstroEventBanner(
                    event = nextEv,
                    today = state.today,
                    isArmed = nextEv.id !in state.mutedEventIds,
                    onToggleAlert = { onToggleEventAlert(nextEv.id) },
                    onApplyFilter = { onApplyEventFilter(nextEv) }
                )
            }
        }

        // 2. Interactive Monthly Astro Calendar View
        item(key = "astro_calendar") {
            AstroMonthlyCalendarCard(
                displayedMonth = state.displayedMonth,
                selectedDate = state.selectedDate,
                cells = state.calendarCells,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onJumpToday = onJumpToday,
                onSelectDate = onSelectDate
            )
        }

        // 3. Selected Day Ephemeris & Event Detail Card
        state.selectedDateSnapshot?.let { daySnap ->
            item(key = "selected_day_detail") {
                SelectedDateAstroCard(
                    date = state.selectedDate,
                    today = state.today,
                    snapshot = daySnap,
                    events = state.selectedDateEvents,
                    mutedEventIds = state.mutedEventIds,
                    onToggleAlert = onToggleEventAlert,
                    onApplyFilter = onApplyEventFilter
                )
            }
        }

        // 4. Historical Volatility & Signal Performance Mapping Matrix
        item(key = "volatility_performance_matrix") {
            AstroVolatilityPerformanceCard(stats = state.performanceStats)
        }

        // 5. Category Filter Strip for Upcoming Alerts Feed
        item(key = "category_filter_strip") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "UPCOMING PLANETARY & LUNAR ALERTS (${state.upcomingEvents.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Next 90 Days",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = state.selectedCategory == null,
                        onClick = { onSelectCategory(null) },
                        label = { Text("All Events", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("astro_cat_all")
                    )
                    AstroEventCategory.entries.forEach { cat ->
                        val accent = categoryAccentColor(cat)
                        FilterChip(
                            selected = state.selectedCategory == cat,
                            onClick = { onSelectCategory(cat) },
                            label = { Text("${cat.glyph} ${cat.shortLabel}", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("astro_cat_${cat.name.lowercase(Locale.US)}"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent.copy(alpha = 0.20f),
                                selectedLabelColor = accent
                            )
                        )
                    }
                }
            }
        }

        // 6. Chronological Upcoming Events Feed
        if (state.upcomingEvents.isEmpty()) {
            item(key = "empty_events") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, tradingColors.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No upcoming events in this category within the next 90 days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(state.upcomingEvents, key = { it.id }) { event ->
                UpcomingAstroEventCard(
                    event = event,
                    today = state.today,
                    isArmed = event.id !in state.mutedEventIds,
                    onToggleAlert = { onToggleEventAlert(event.id) },
                    onApplyFilter = { onApplyEventFilter(event) },
                    onFocusDate = { onSelectDate(event.date) }
                )
            }
        }
    }
}

@Composable
private fun NextAstroEventBanner(
    event: AstroCalendarEvent,
    today: LocalDate,
    isArmed: Boolean,
    onToggleAlert: () -> Unit,
    onApplyFilter: () -> Unit
) {
    val accent = categoryAccentColor(event.category)
    val days = event.daysFrom(today)
    val countdown = when {
        days <= 0L -> "TODAY"
        days == 1L -> "TOMORROW"
        else -> "IN ${days}D"
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.2.dp, accent.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("astro_next_event_banner")
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = 0.22f)) {
                        Text(
                            text = "${event.category.glyph} $countdown",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Column {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${event.date.format(SHORT_DATE_FMT)} • ${event.subtitle}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onToggleAlert,
                    modifier = Modifier.testTag("astro_next_event_bell")
                ) {
                    Icon(
                        imageVector = if (isArmed) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                        contentDescription = if (isArmed) "Mute event alert" else "Arm event alert",
                        tint = if (isArmed) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Est. Vol ${String.format(Locale.US, "%.2fx", event.expectedVolatilityX)} • Hist Win ${String.format(Locale.US, "%.0f", event.historicalWinRatePercent)}%",
                    style = MonoPriceSmall,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(
                    onClick = onApplyFilter,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.FilterAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sync Filter", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AstroMonthlyCalendarCard(
    displayedMonth: YearMonth,
    selectedDate: LocalDate,
    cells: List<AstroCalendarDayCell>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onJumpToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val dayHeaders = listOf("M", "T", "W", "T", "F", "S", "S")

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("astro_event_calendar")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Month Header Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Event,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = displayedMonth.format(MONTH_YEAR_FMT),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onJumpToday,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Today", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(
                        onClick = onPreviousMonth,
                        modifier = Modifier.size(36.dp).testTag("astro_cal_prev_month")
                    ) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month")
                    }
                    IconButton(
                        onClick = onNextMonth,
                        modifier = Modifier.size(36.dp).testTag("astro_cal_next_month")
                    ) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month")
                    }
                }
            }

            // Weekday Headers
            Row(modifier = Modifier.fillMaxWidth()) {
                dayHeaders.forEach { d ->
                    Text(
                        text = d,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Calendar Weeks
            val weeks = cells.chunked(7)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                weeks.forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        week.forEach { cell ->
                            val isSelected = cell.date == selectedDate
                            val primaryEvent = cell.events.firstOrNull()
                            val primaryColor = primaryEvent?.let { categoryAccentColor(it.category) }
                            val cellBg = when {
                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                primaryColor != null -> primaryColor.copy(alpha = 0.14f)
                                cell.isMercuryRetrogradeActive && cell.inCurrentMonth -> tradingColors.bearish.copy(alpha = 0.08f)
                                else -> Color.Transparent
                            }
                            val borderColor = when {
                                isSelected -> MaterialTheme.colorScheme.primary
                                cell.isToday -> tradingColors.amber
                                primaryColor != null -> primaryColor.copy(alpha = 0.45f)
                                else -> Color.Transparent
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(cellBg)
                                    .border(
                                        width = if (isSelected || cell.isToday) 1.3.dp else 0.8.dp,
                                        color = borderColor,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { onSelectDate(cell.date) }
                                    .testTag("astro_cal_day_${cell.date}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = cell.date.dayOfMonth.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected || cell.isToday || primaryEvent != null) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            !cell.inCurrentMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                    if (cell.events.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            modifier = Modifier.padding(top = 2.dp)
                                        ) {
                                            cell.events.take(3).forEach { ev ->
                                                Box(
                                                    modifier = Modifier
                                                        .size(5.dp)
                                                        .clip(CircleShape)
                                                        .background(categoryAccentColor(ev.category))
                                                )
                                            }
                                        }
                                    } else if (cell.isMercuryRetrogradeActive && cell.inCurrentMonth) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 3.dp)
                                                .width(12.dp)
                                                .height(2.dp)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(tradingColors.bearish.copy(alpha = 0.75f))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Legend Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CalendarLegendDot("Full Moon", tradingColors.amber)
                CalendarLegendDot("New Moon", MaterialTheme.colorScheme.primary)
                CalendarLegendDot("Mercury Rx", tradingColors.bearish)
                CalendarLegendDot("Major Aspect", tradingColors.violet)
                CalendarLegendDot("Quarter", tradingColors.cryptoCyan)
            }
        }
    }
}

@Composable
private fun CalendarLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SelectedDateAstroCard(
    date: LocalDate,
    today: LocalDate,
    snapshot: AstroCycleSnapshot,
    events: List<AstroCalendarEvent>,
    mutedEventIds: Set<String>,
    onToggleAlert: (String) -> Unit,
    onApplyFilter: (AstroCalendarEvent) -> Unit
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, tradingColors.border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("astro_selected_day_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = tradingColors.amber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (date == today) "Today • ${date.format(SHORT_DATE_FMT)}" else date.format(SHORT_DATE_FMT),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (snapshot.isMercuryRetrograde) tradingColors.bearishContainer else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = if (snapshot.isMercuryRetrograde) "☿ Rx Active • Turn ${snapshot.turningPointScore}" else "Turn ${snapshot.turningPointScore}/100",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (snapshot.isMercuryRetrograde) tradingColors.bearish else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "${snapshot.lunarPhaseWindow.label} (${snapshot.moonIlluminationPct.toInt()}% Moon) • ${snapshot.nakshatraName} (${snapshot.nakshatraTone}) • ${snapshot.bias.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (events.isNotEmpty()) {
                HorizontalDivider(color = tradingColors.border)
                events.forEach { ev ->
                    val accent = categoryAccentColor(ev.category)
                    val armed = ev.id !in mutedEventIds
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${ev.category.glyph} ${ev.title}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = accent
                            )
                            Text(
                                text = "Vol ${String.format(Locale.US, "%.2fx", ev.expectedVolatilityX)} • Win ${String.format(Locale.US, "%.0f", ev.historicalWinRatePercent)}% • ${ev.tacticalNote}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onToggleAlert(ev.id) }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = if (armed) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                                contentDescription = "Toggle alert",
                                tint = if (armed) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { onApplyFilter(ev) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Apply Event Filter to Signals", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                Text(
                    text = "No exact lunar peak or retrograde station on this date — standard transit volatility expected.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AstroVolatilityPerformanceCard(
    stats: List<AstroEventPerformanceStat>
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("astro_volatility_performance_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.ShowChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "HISTORICAL VOLATILITY & SIGNAL PERFORMANCE",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = "Maps each astrological event regime to volatility expansion (vs normal sessions) and resolved signal win-rate.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            stats.forEach { stat ->
                val accent = categoryAccentColor(stat.category)
                val winColor = when {
                    stat.winRatePercent >= 62.0 -> tradingColors.bullish
                    stat.winRatePercent >= 55.0 -> tradingColors.amber
                    else -> tradingColors.bearish
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${stat.category.glyph} ${stat.category.label}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (stat.usesLiveSample) tradingColors.bullishContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = if (stat.usesLiveSample) "Live (${stat.resolvedSignalCount})" else "Baseline",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (stat.usesLiveSample) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Vol ${String.format(Locale.US, "%.2fx", stat.volatilityMultiplier)} (${String.format(Locale.US, "%.2f", stat.avgDailyRangePercent)}% rng)",
                            style = MonoPriceSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${String.format(Locale.US, "%.1f", stat.winRatePercent)}% Win Rate",
                            style = MonoPriceSmall,
                            fontWeight = FontWeight.Bold,
                            color = winColor
                        )
                    }

                    LinearProgressIndicator(
                        progress = { ((stat.volatilityMultiplier - 0.8) / 0.9).toFloat().coerceIn(0.12f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )

                    Text(
                        text = stat.tacticalGuidance,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingAstroEventCard(
    event: AstroCalendarEvent,
    today: LocalDate,
    isArmed: Boolean,
    onToggleAlert: () -> Unit,
    onApplyFilter: () -> Unit,
    onFocusDate: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val accent = categoryAccentColor(event.category)
    val days = event.daysFrom(today)
    val countdown = when {
        days < 0L -> "${-days}d ago"
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        else -> "In ${days}d"
    }
    val impactBg = when (event.impact) {
        AstroImpactLevel.HIGH -> tradingColors.bearishContainer
        AstroImpactLevel.HARMONIC -> tradingColors.bullishContainer
        AstroImpactLevel.MODERATE -> tradingColors.amberContainer
    }
    val impactFg = when (event.impact) {
        AstroImpactLevel.HIGH -> tradingColors.bearish
        AstroImpactLevel.HARMONIC -> tradingColors.bullish
        AstroImpactLevel.MODERATE -> tradingColors.amber
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onFocusDate)
            .testTag("astro_event_card_${event.id}")
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = 0.16f)) {
                        Text(
                            text = event.category.glyph,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Column {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${event.date.format(SHORT_DATE_FMT)} • $countdown",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = impactBg) {
                        Text(
                            text = event.impact.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = impactFg,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    IconButton(
                        onClick = onToggleAlert,
                        modifier = Modifier.size(34.dp).testTag("astro_event_bell_${event.id}")
                    ) {
                        Icon(
                            imageVector = if (isArmed) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                            contentDescription = if (isArmed) "Mute alert" else "Enable alert",
                            tint = if (isArmed) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Volatility & Signal Performance Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("HIST. VOLATILITY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${String.format(Locale.US, "%.2fx", event.expectedVolatilityX)} (${String.format(Locale.US, "%.2f", event.historicalAvgMovePercent)}%)",
                        style = MonoPriceSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }
                Column {
                    Text("SIGNAL WIN RATE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${String.format(Locale.US, "%.1f", event.historicalWinRatePercent)}%${if (event.liveSignalSampleCount > 0) " (${event.liveSignalSampleCount})" else ""}",
                        style = MonoPriceSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (event.historicalWinRatePercent >= 60.0) tradingColors.bullish else tradingColors.amber
                    )
                }
                OutlinedButton(
                    onClick = onApplyFilter,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Apply Filter", style = MaterialTheme.typography.labelSmall)
                }
            }

            Text(
                text = event.tacticalNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
