package com.meditrack.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meditrack.core.theme.doseColors
import com.meditrack.core.theme.prefs
import com.meditrack.core.util.Haptics
import com.meditrack.core.util.QuantityFormatter

/**
 * The "+" / "-" quantity control.
 *
 * Design notes that came out of the "used four times a day for years" requirement:
 *  - **The number is the biggest thing in the control.** It is what the user came to check.
 *  - **The value animates, the layout does not.** A small scale pop on change confirms the tap
 *    without shifting the neighbours; the digits themselves are monospaced so they do not jitter.
 *  - **Touch targets are at least 48dp** and grow to 60dp under the accessibility preset.
 *  - **Haptics on every tap.** A user with reduced vision needs the confirmation that the tap
 *    registered; the vibration is the feedback that does not require looking.
 *
 * @param taken the recorded amount
 * @param planned the target amount, used to decide the emphasis colour
 * @param unitLabel "片" / "ml" / ...
 * @param step the increment, 1 for countable forms and 0.5 for liquids
 * @param onIncrease invoked on "+"
 * @param onDecrease invoked on "-"; disabled at zero
 */
@Composable
fun QuantityStepper(
    taken: Double,
    planned: Double,
    unitLabel: String,
    step: Double,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val doseColors = MaterialTheme.doseColors
    val prefs = MaterialTheme.prefs

    val isComplete = QuantityFormatter.isComplete(taken, planned)
    val isZero = QuantityFormatter.isZero(taken)

    // Completed doses read green; anything still open keeps the neutral surface colour.
    val valueColor by animateColorAsState(
        targetValue = when {
            isComplete -> doseColors.taken
            !isZero -> doseColors.partial
            else -> MaterialTheme.colorScheme.onSurface
        },
        label = "stepperValueColor",
    )

    // A short pop that settles immediately - long animations feel sluggish on a repeated action.
    val scale by animateFloatAsState(
        targetValue = if (isComplete) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "stepperScale",
    )

    val targetSize = prefs.minTouchTargetDp.dp

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StepperButton(
            icon = Icons.Filled.Remove,
            enabled = enabled && !isZero,
            size = targetSize,
            contentDescription = "减少 ${QuantityFormatter.format(step)} $unitLabel",
            onClick = {
                Haptics.tick(context)
                onDecrease()
            },
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .defaultMinSize(minWidth = 64.dp)
                .clearAndSetSemantics {
                    contentDescription = "已服用 ${QuantityFormatter.format(taken)} " +
                        "$unitLabel，计划 ${QuantityFormatter.format(planned)} $unitLabel"
                },
        ) {
            Text(
                text = QuantityFormatter.format(taken),
                style = MaterialTheme.typography.titleLarge,
                color = valueColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.scale(scale),
            )
            Text(
                text = "/ ${QuantityFormatter.format(planned)} $unitLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        StepperButton(
            icon = Icons.Filled.Add,
            enabled = enabled,
            size = targetSize,
            contentDescription = "增加 ${QuantityFormatter.format(step)} $unitLabel",
            onClick = {
                Haptics.tick(context)
                onIncrease()
            },
        )
    }
}

/** Circular icon button sized for the accessibility preset. */
@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    val content = if (enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Surface(
        shape = CircleShape,
        color = container,
        modifier = Modifier.size(size),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(size)
                .clearAndSetSemantics { this.contentDescription = contentDescription },
        ) {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(container, CircleShape)
                    .padding(size / 5),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(size / 2.4f),
                )
            }
        }
    }
}

/**
 * Read-only progress used by the history list and the widget preview, where the user is looking at
 * a record rather than changing it.
 */
@Composable
fun QuantityProgress(
    taken: Double,
    planned: Double,
    unitLabel: String,
    modifier: Modifier = Modifier,
    statusColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = QuantityFormatter.format(taken),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = statusColor,
        )
        Text(
            text = "/ ${QuantityFormatter.format(planned)} $unitLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
