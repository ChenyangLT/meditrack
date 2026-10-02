package com.meditrack.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A titled single-choice chip group that wraps instead of running off the screen.
 *
 * A plain [androidx.compose.foundation.layout.Row] of [FilterChip]s lays its children out on a
 * single line and hands the last ones whatever width is left - which collapses to zero as soon as
 * the label metrics grow. That is not a corner case in this app: the in-app font preset scales the
 * type scale up to 1.5x on top of the system font scale, so a three-chip filter row that fits at
 * 1.0x is already overcrowded at 1.3x.
 *
 * [FlowRow] keeps every option reachable by letting the chips flow onto a second line, and every
 * option keeps its own natural size.
 *
 * @param title section label rendered above the chips.
 * @param options every selectable value, in display order.
 * @param selected the value currently in effect; compared with ==
 * @param labelOf maps a value to the text shown on its chip.
 * @param onSelect invoked with the tapped value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> AdaptiveChipRow(
    title: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(labelOf(option), style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

/**
 * A wrapping container for a row of buttons (and the labels that sit next to them).
 *
 * Use this instead of a plain [androidx.compose.foundation.layout.Row] wherever two or more
 * buttons - or a button plus a caption - share a line: a fixed-width row clips the last child once
 * the text grows, while this one moves it to the next line.
 *
 * Note that [FlowRow] aligns the items of a line to the top of that line, so items of very
 * different heights are not vertically centred the way
 * [androidx.compose.foundation.layout.Row] with [androidx.compose.ui.Alignment.CenterVertically]
 * centres them.
 *
 * @param horizontalArrangement spacing between items on the same line.
 * @param verticalArrangement spacing between lines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdaptiveButtonRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(4.dp),
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
