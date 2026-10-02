package com.meditrack.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meditrack.core.theme.doseColors
import com.meditrack.core.theme.prefs
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.ui.components.StatusDot
import com.meditrack.ui.components.doseStatusVisuals
import java.time.YearMonth

/**
 * Month grid with one coloured dot per day.
 *
 * Rendered as a plain Column of Rows rather than a lazy grid: the whole month is at most six rows,
 * so virtualisation would be pure overhead (and, as it turned out, a source of composition bugs
 * when nested inside the screen's scroll container).
 */
@Composable
fun CalendarCard(state: HistoryUiState, viewModel: HistoryViewModel) {
    val isCurrentMonth = state.month == YearMonth.now()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::showPreviousMonth) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "上个月")
                }
                Text(
                    text = state.month.year.toString() + " 年 " + state.month.monthValue + " 月",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                if (!isCurrentMonth) {
                    TextButton(onClick = viewModel::showCurrentMonth) { Text("本月") }
                }
                IconButton(onClick = viewModel::showNextMonth) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "下个月")
                }
            }

            // Weekday header, Monday first to match the ISO numbering used everywhere else.
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            state.days.chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        CalendarCell(
                            day = day,
                            selected = day.epochDay == state.selectedEpochDay,
                            onClick = { viewModel.selectDay(day.epochDay) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(7 - week.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))
            CalendarLegend()
        }
    }
}

/** One day cell: the number plus a status dot. */
@Composable
private fun CalendarCell(
    day: CalendarDay,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dotColor = adherenceColor(day.adherence)
    // A minimum cell height instead of Modifier.aspectRatio: aspectRatio on a weight(1f) child asks
    // the parent Row to derive the cross-axis size from a main-axis size it has not resolved yet,
    // which is ambiguous during the first measure. A minimum keeps the grid even while still
    // letting the day number grow with the font scale.
    val cellHeight = if (MaterialTheme.prefs.simplifiedMode) 52.dp else 44.dp

    Column(
        modifier = modifier
            // heightIn, not height: the cell must keep its minimum grid size, but at a large font
            // scale the day number is taller than 44dp and an exact height would clip it against
            // the next week.
            .heightIn(min = cellHeight)
            .padding(2.dp)
            .background(
                color = when {
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    day.isToday -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = day.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
            color = when {
                !day.isCurrentMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                day.isToday -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(modifier = Modifier.height(2.dp))
        // A plain Box, not the shared StatusDot: StatusDot calls clearAndSetSemantics, and doing
        // that once per cell across ~42 cells in a single composition was implicated in the
        // slot-table corruption seen on this screen. The dot is decorative, so it simply carries no
        // semantics at all here.
        if (day.hasRecords) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor, CircleShape),
            )
        } else {
            Spacer(modifier = Modifier.size(6.dp))
        }
    }
}

/** Always-visible key for the calendar colours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarLegend() {
    val doseColors = MaterialTheme.doseColors
    // Five dot+label pairs are far too wide to guarantee on one line once the labels grow.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LegendItem("已服", doseColors.taken)
        LegendItem("部分", doseColors.partial)
        LegendItem("未服药", doseColors.missed)
        LegendItem("跳过", doseColors.skipped)
        LegendItem("无计划", MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = color, size = 8.dp)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Adherence colour for a calendar cell. */
@Composable
private fun adherenceColor(adherence: DayAdherence): Color {
    val doseColors = MaterialTheme.doseColors
    return when (adherence) {
        DayAdherence.TAKEN -> doseColors.taken
        DayAdherence.PARTIAL -> doseColors.partial
        DayAdherence.MISSED -> doseColors.missed
        DayAdherence.SKIPPED -> doseColors.skipped
        DayAdherence.NONE -> MaterialTheme.colorScheme.outlineVariant
    }
}

/** Adherence, streak and totals for the selected range. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatisticsCard(state: HistoryUiState, viewModel: HistoryViewModel) {
    val stats = state.stats

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Wrapping, not a Row: the range chips already fill the card at 1.0x with the default
            // labels, and a Row would cut the last one off at any larger font scale.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HistoryRange.entries.forEach { range ->
                    FilterChip(
                        selected = stats.range == range,
                        onClick = { viewModel.setRange(range) },
                        label = { Text(range.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "依从率",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stats.adherencePercent.toString() + "%",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = adherenceTextColor(stats.adherencePercent),
                    )
                }
                // Both halves carry a weight: otherwise the streak block is measured first and
                // can take the whole width, squeezing the adherence figure into a 0dp column.
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = "连续服药",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stats.currentStreak.toString() + " 天",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (stats.longestStreak > stats.currentStreak) {
                        Text(
                            text = "最长 " + stats.longestStreak + " 天",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!stats.hasData) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "这个时间段还没有记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                StatRow("总计划次数", stats.totalDoses.toString() + " 次")
                StatRow("已服次数", stats.takenDoses.toString() + " 次", MaterialTheme.doseColors.taken)
                if (stats.partialDoses > 0) {
                    StatRow("部分服用", stats.partialDoses.toString() + " 次", MaterialTheme.doseColors.partial)
                }
                StatRow("未服药次数", stats.missedDoses.toString() + " 次", MaterialTheme.doseColors.missed)
                if (stats.skippedDoses > 0) {
                    StatRow("跳过次数", stats.skippedDoses.toString() + " 次", MaterialTheme.doseColors.skipped)
                }
                StatRow("累计数量", stats.takenLabel)
            }
        }
    }
}

/** Colour for the headline adherence figure. */
@Composable
private fun adherenceTextColor(percent: Int): Color {
    val doseColors = MaterialTheme.doseColors
    return when {
        percent >= 90 -> doseColors.taken
        percent >= 70 -> doseColors.partial
        percent > 0 -> doseColors.missed
        else -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Everything recorded on the selected day, with the ability to correct it. */
@Composable
fun DayDetailCard(
    state: HistoryUiState,
    viewModel: HistoryViewModel,
    onOpenMedication: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = DateTimeUtils.formatDateLong(state.selectedEpochDay) + " 的记录",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (state.selectedDayDoses.isEmpty()) {
                Text(
                    text = "这一天没有记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.selectedDayDoses.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    val visuals = doseStatusVisuals(row.status)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(visuals.accent, size = 8.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.medicationName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = row.timeLabel + " · " + visuals.label + " · " +
                                    com.meditrack.core.util.QuantityFormatter.format(row.takenQuantity) +
                                    "/" +
                                    com.meditrack.core.util.QuantityFormatter.format(row.plannedQuantity) +
                                    " " + row.unitLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Correcting a mis-tap from days ago must be possible without deleting history.
                        if (row.status == DoseStatus.MISSED || row.status == DoseStatus.SKIPPED) {
                            TextButton(onClick = { viewModel.markTaken(row.doseId) }) { Text("补记") }
                        } else if (row.status == DoseStatus.TAKEN) {
                            IconButton(onClick = { viewModel.reset(row.doseId) }) {
                                Icon(
                                    Icons.Filled.Undo,
                                    contentDescription = "撤销这条记录",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
