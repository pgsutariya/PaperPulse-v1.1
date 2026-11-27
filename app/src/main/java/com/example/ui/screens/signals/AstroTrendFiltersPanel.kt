package com.example.ui.screens.signals

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.analysis.AstroCycleSnapshot
import com.example.domain.analysis.AstroTrendFilterConfig
import com.example.domain.analysis.LunarCycleFilter
import com.example.domain.analysis.PlanetaryAspectFilter
import com.example.ui.theme.LocalTradingColors
import java.util.Locale

@Composable
fun AstroTrendFiltersPanel(
    astro: AstroCycleSnapshot,
    config: AstroTrendFilterConfig,
    astroMatchedCount: Int,
    isRefreshing: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectLunarFilter: (LunarCycleFilter) -> Unit,
    onSelectPlanetaryFilter: (PlanetaryAspectFilter) -> Unit,
    onToggleAlignDirection: () -> Unit,
    onToggleAvoidMercuryRx: () -> Unit,
    onSelectMinTurnScore: (Int) -> Unit,
    onApplyAndRescan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    val activeBorder = if (config.enabled) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    } else {
        tradingColors.border
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, activeBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("astro_trend_filters_panel")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Compact Header (always visible, tap to expand/collapse)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .testTag("astro_panel_header"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = "Astrological Trend Filters",
                        tint = if (config.enabled) MaterialTheme.colorScheme.primary else tradingColors.amber,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Astrological Trend Filters",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (config.enabled) tradingColors.bullishContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = if (config.enabled) "ACTIVE • $astroMatchedCount" else "OFF",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (config.enabled) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = "${astro.lunarPhaseWindow.label} (${astro.moonIlluminationPct.toInt()}% Moon) • ${astro.nakshatraName} • Turn ${astro.turningPointScore}/100",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse Astrological Trend Filters" else "Expand Astrological Trend Filters",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = tradingColors.border)

                    // Live Planetary Positions Strip (Sun, Moon, Mercury, Venus, Mars, Jupiter, Saturn)
                    Text(
                        text = "LIVE PLANETARY POSITIONS & ASPECTS (${astro.bias.label})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        astro.planetaryPositions.forEach { pos ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (pos.isRetrograde) tradingColors.amberContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = pos.compactLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (pos.isRetrograde) tradingColors.amber else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    if (astro.activeAspects.isNotEmpty()) {
                        val aspectsLine = astro.activeAspects.take(3).joinToString("  •  ") {
                            "${it.planetA} ${it.aspectName} ${it.planetB} (${String.format(Locale.US, "%.1f", it.orbDeg)}°)"
                        }
                        Text(
                            text = aspectsLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Master Enable Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gate Signal Engine with Astro Filters",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Applies Lunar & Planetary rules to live signal generation & feed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = onToggleEnabled,
                            modifier = Modifier.testTag("astro_filter_master_switch")
                        )
                    }

                    // Lunar Cycle Filter Chips
                    Text(
                        text = "Lunar Cycle Trend Filter",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LunarCycleFilter.entries.forEach { lunar ->
                            FilterChip(
                                selected = config.enabled && config.lunarCycleFilter == lunar,
                                onClick = { onSelectLunarFilter(lunar) },
                                label = { Text(lunar.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("astro_lunar_${lunar.name.lowercase()}"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }

                    // Planetary Aspect & Transit Rules
                    Text(
                        text = "Planetary Aspect & Transit Rules",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PlanetaryAspectFilter.entries.forEach { pf ->
                            FilterChip(
                                selected = config.enabled && config.planetaryFilter == pf,
                                onClick = { onSelectPlanetaryFilter(pf) },
                                label = { Text(pf.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("astro_planet_${pf.name.lowercase()}")
                            )
                        }
                        FilterChip(
                            selected = config.enabled && config.alignSignalDirectionWithAstro,
                            onClick = onToggleAlignDirection,
                            label = { Text("Align Direction", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("astro_align_direction_chip")
                        )
                        FilterChip(
                            selected = config.enabled && config.avoidMercuryRetrograde,
                            onClick = onToggleAvoidMercuryRx,
                            label = { Text("Avoid Mercury Rx", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("astro_avoid_mercury_rx_chip")
                        )
                    }

                    // Turning Point Score Threshold + Apply & Rescan CTA
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(40, 55, 65, 75).forEach { score ->
                                FilterChip(
                                    selected = config.enabled && config.minTurningPointScore == score,
                                    onClick = { onSelectMinTurnScore(score) },
                                    label = { Text("Turn $score+", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("astro_turn_$score")
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onApplyAndRescan,
                            enabled = !isRefreshing,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("astro_apply_rescan_button")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Rescan", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
