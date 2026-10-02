package com.meditrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meditrack.core.theme.DoseColors
import com.meditrack.core.theme.doseColors
import com.meditrack.core.theme.prefs
import com.meditrack.data.local.entity.DoseStatus

/**
 * The visual identity of a dose status.
 *
 * The point of centralising this is accessibility: colour alone must never be the only carrier of
 * meaning, so every status also has a **distinct icon shape** and a **text label**. A user with
 * deuteranopia reads the check / slash / hourglass / exclamation marks, and a user with low vision
 * reads the words.
 */
data class DoseStatusVisuals(
    val label: String,
    val container: Color,
    val content: Color,
    val accent: Color,
    val icon: ImageVector,
)

/**
 * Resolves the semantic colours for a status from the current theme.
 *
 * Use this overload when the dose has already been actioned or when the status alone fully describes
 * it. For a dose that is simply *late*, use the [isOverdue] overload - without it a dose whose
 * scheduled time has passed renders as "待服用" in amber until the grace-period sweep catches up,
 * which reads as "you still have time" when the truth is "you have already missed this one".
 */
@Composable
fun doseStatusVisuals(status: DoseStatus): DoseStatusVisuals =
    doseStatusVisuals(status, isOverdue = status == DoseStatus.MISSED)

/**
 * Resolves the visuals, turning a merely-late dose red.
 *
 * @param isOverdue true when the scheduled instant is in the past and nothing has been recorded.
 *   Only affects the two open states; taken/partial/skipped keep their own meaning.
 */
@Composable
fun doseStatusVisuals(status: DoseStatus, isOverdue: Boolean): DoseStatusVisuals {
    val dose: DoseColors = MaterialTheme.doseColors
    return when (status) {
        DoseStatus.TAKEN -> DoseStatusVisuals(
            label = "已服用",
            container = dose.takenContainer,
            content = dose.onTakenContainer,
            accent = dose.taken,
            icon = Icons.Filled.Check,
        )
        DoseStatus.PARTIAL -> DoseStatusVisuals(
            label = "部分服用",
            container = dose.partialContainer,
            content = dose.onPartialContainer,
            accent = dose.partial,
            icon = Icons.Filled.Timelapse,
        )
        DoseStatus.MISSED -> DoseStatusVisuals(
            label = "未服药",
            container = dose.missedContainer,
            content = dose.onMissedContainer,
            accent = dose.missed,
            icon = Icons.Filled.Error,
        )
        DoseStatus.SKIPPED -> DoseStatusVisuals(
            label = "已跳过",
            container = dose.skippedContainer,
            content = dose.onSkippedContainer,
            accent = dose.skipped,
            icon = Icons.Filled.RemoveCircleOutline,
        )
        // Late but not yet swept to MISSED: already red, because the time to take it has gone.
        DoseStatus.DUE -> if (isOverdue) {
            DoseStatusVisuals(
                label = "未服药",
                container = dose.missedContainer,
                content = dose.onMissedContainer,
                accent = dose.missed,
                icon = Icons.Filled.Error,
            )
        } else {
            DoseStatusVisuals(
                label = "待服用",
                container = dose.dueSoonContainer,
                content = dose.onDueSoonContainer,
                accent = dose.dueSoon,
                icon = Icons.Filled.Schedule,
            )
        }
        DoseStatus.UPCOMING -> DoseStatusVisuals(
            label = "未到时间",
            container = dose.upcomingContainer,
            content = dose.onUpcomingContainer,
            accent = dose.upcoming,
            icon = Icons.Filled.HourglassEmpty,
        )
    }
}

/**
 * Status pill: icon + label on a tinted background.
 *
 * @param compact when true the label is dropped and only the icon remains, for dense rows
 */
@Composable
fun DoseStatusChip(
    status: DoseStatus,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    labelOverride: String? = null,
    /**
     * True when the scheduled time has passed with nothing recorded.
     *
     * Must be passed wherever the caller knows it. Defaulting it to `false` made a dose that is
     * already late - and whose reminder has already fired - render as amber 待服用, which reads as
     * "you still have time" when the truth is the opposite. The card it sits in was already painting
     * its accent red from the same fact, so the chip and the card disagreed.
     */
    isOverdue: Boolean = false,
) {
    val visuals = doseStatusVisuals(status, isOverdue)
    val label = labelOverride ?: visuals.label
    val borderWidth = if (MaterialTheme.prefs.highContrast) 1.5.dp else 0.dp

    Row(
        modifier = modifier
            .background(visuals.container, RoundedCornerShape(50))
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(borderWidth, visuals.accent.copy(alpha = 0.6f), RoundedCornerShape(50))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = 4.dp)
            // One concise announcement instead of "check icon, 已服用".
            .clearAndSetSemantics { contentDescription = "状态：$label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = visuals.icon,
            contentDescription = null,
            tint = visuals.accent,
            modifier = Modifier.size(14.dp),
        )
        if (!compact) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = visuals.content,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** A bare colour dot used in the history calendar and the widget preview. */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(color, CircleShape)
            .clearAndSetSemantics { },
    )
}
