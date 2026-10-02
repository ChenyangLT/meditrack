package com.meditrack.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.unit.FixedColorProvider
import com.meditrack.MainActivity
import com.meditrack.R
import com.meditrack.domain.plan.WidgetContent
import com.meditrack.domain.plan.WidgetDoseItem
import com.meditrack.domain.plan.WidgetPriority

/**
 * The home-screen widget content, shared by every size variant.
 *
 * Ordering is owned by [com.meditrack.domain.plan.WidgetPlanner] and is the core product promise:
 *
 *   1. past its time and not taken  -> red
 *   2. due within 30 minutes        -> orange
 *   3. later today, still open      -> blue
 *   4. taken / skipped / missed     -> muted, pushed to the bottom
 *
 * ## Sizing
 *
 * The widget is offered as a family of **four columns wide, one to four rows tall** footprints, each
 * with its own entry in the launcher's widget picker and each freely resizable from 4x1 upwards. The
 * layout is therefore derived entirely from [LocalSize] through [WidgetSizing], never from a
 * hard-coded "which variant am I" flag - the same composition has to look right at 4x1, at 4x4, and
 * at every size a user drags in between.
 *
 * Two consequences of that are worth naming, because both were bugs before:
 *
 *  - **The row count follows the real height.** A 4x2 tile has room for exactly two rows; asking for
 *    three is what used to push content off the bottom of the widget.
 *  - **The title row yields to the doses.** At 4x1 there is not enough height for a title *and* a
 *    dose row, so the title is dropped. Showing one dose without a heading is strictly better than
 *    showing a heading above a clipped dose.
 *
 * This is also why the widgets are declared with `SizeMode.Exact`: with the default single-size mode
 * the composition is not redone when the user resizes, so a tile dragged smaller keeps rendering the
 * layout it was born with.
 */
@Composable
fun MediTrackWidgetContent(content: WidgetContent, options: WidgetDisplayOptions) {
    val size = LocalSize.current
    val context = LocalContext.current
    val layout = WidgetSizing.layoutFor(
        widthDp = size.width.value,
        heightDp = size.height.value,
        quickActionsEnabled = options.quickActions,
        itemCount = content.items.size,
        itemLimit = options.itemLimit,
        // 13sp is 13dp only at the default text size; the launcher renders this widget with the
        // system font scale, so the row budget has to follow it or a large-text user gets a clipped
        // tile at every size.
        fontScale = context.resources.configuration.fontScale,
    )

    val visible = content.items.take(layout.rowLimit)
    val hiddenCount = (content.items.size - visible.size).coerceAtLeast(0)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetPalette.background)
            .cornerRadius(WidgetDimens.corner)
            .padding(layout.outerPaddingDp.dp),
    ) {
        if (layout.showHeader) {
            WidgetHeader(content)
            Spacer(modifier = GlanceModifier.height(4.dp))
        }

        val nothingToDo = content.isEmpty || content.allDone
        when {
            nothingToDo -> WidgetCelebration(content, compact = layout.singleLineRows)

            layout.singleLineRows -> {
                // The bottom of the supported range: one dense line, no title, no chrome.
                visible.forEach { item -> WidgetStripRow(item) }
            }

            else -> {
                // Rows share the leftover height rather than each claiming a fixed one, so a taller
                // tile breathes instead of leaving a gap at the bottom.
                visible.forEach { item ->
                    WidgetDoseRow(
                        item = item,
                        showActions = layout.showQuickActions,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Spacer(modifier = GlanceModifier.height(layout.rowSpacingDp.dp))
                }
            }
        }

        if (layout.showOverflowHint && hiddenCount > 0) {
            Text(
                text = context.getString(R.string.widget_more, hiddenCount),
                style = TextStyle(
                    color = WidgetPalette.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
                modifier = GlanceModifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * Title row: the app glyph, "今日用药", and how many doses are still outstanding.
 *
 * Kept to ~20dp of content so it costs as little of a short tile as possible.
 */
@Composable
private fun WidgetHeader(content: WidgetContent) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(18.dp)
                .background(FixedColorProvider(Color(0x1A3DBFA0)))
                .cornerRadius(9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_clock),
                contentDescription = null,
                modifier = GlanceModifier.size(12.dp),
                colorFilter = ColorFilter.tint(WidgetPalette.laterToday),
            )
        }
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = context.getString(R.string.widget_title),
            style = TextStyle(
                color = WidgetPalette.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (content.outstanding > 0) {
            Text(
                text = context.getString(R.string.widget_outstanding, content.outstanding),
                style = TextStyle(
                    color = WidgetPalette.dueSoon,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        } else if (!content.isEmpty) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_check),
                contentDescription = null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(WidgetPalette.taken),
            )
        }
    }
}

/**
 * One dose row: status dot, medication name, time, and the taken/planned progress.
 *
 * The whole row opens the today screen focused on this dose; the optional "+" / "-" buttons write
 * straight to the database from the widget, so a user who is not going to unlock the phone can still
 * record the dose.
 */
@Composable
private fun WidgetDoseRow(
    item: WidgetDoseItem,
    showActions: Boolean,
    modifier: GlanceModifier = GlanceModifier,
) {
    val uiPriority = item.priority.toUi()
    val accent = WidgetPalette.forPriority(uiPriority)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(WidgetPalette.rowSurface(uiPriority))
            .cornerRadius(12.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(actionStartActivity<MainActivity>(openDoseParameters(item))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot: the fastest way to scan the list without reading a word.
        Box(
            modifier = GlanceModifier
                .size(WidgetDimens.dot)
                .background(accent)
                .cornerRadius(5.dp),
        ) {}
        Spacer(modifier = GlanceModifier.width(8.dp))

        WidgetIcons.MedicationGlyph(
            name = item.iconName,
            tint = accent,
            modifier = GlanceModifier.size(16.dp),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = item.name,
                style = TextStyle(
                    color = WidgetPalette.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Text(
                text = item.timeLabel + " · " + item.quantityLabel,
                style = TextStyle(
                    color = WidgetPalette.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
            )
        }

        if (showActions && item.actionable) {
            WidgetStepper(item, R.drawable.ic_widget_remove, -item.step)
            Spacer(modifier = GlanceModifier.width(4.dp))
            WidgetStepper(item, R.drawable.ic_widget_add, item.step)
        } else {
            Text(
                text = item.statusLabel,
                style = TextStyle(
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * The dense single-line form, used only when the tile is too short for the two-line row.
 *
 * It trades the icon and the quantity for the two facts that still matter at that size: which
 * medication, and when it is due.
 */
@Composable
private fun WidgetStripRow(item: WidgetDoseItem) {
    val uiPriority = item.priority.toUi()
    val accent = WidgetPalette.forPriority(uiPriority)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(WidgetPalette.rowSurface(uiPriority))
            .cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clickable(actionStartActivity<MainActivity>(openDoseParameters(item))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .background(accent)
                .cornerRadius(4.dp),
        ) {}
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = item.name,
            style = TextStyle(
                color = WidgetPalette.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = item.timeLabel,
            style = TextStyle(
                color = WidgetPalette.onSurfaceVariant,
                fontSize = 12.sp,
            ),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = item.statusLabel,
            style = TextStyle(
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/**
 * A single quick-action button.
 *
 * The dose id and the signed delta travel as action parameters - Glance forbids closures in
 * RemoteViews, so the callback reconstructs everything it needs from the parameters alone.
 */
@Composable
private fun WidgetStepper(item: WidgetDoseItem, iconRes: Int, delta: Double) {
    val accent = WidgetPalette.forPriority(item.priority.toUi())
    val params = actionParametersOf(
        WidgetActionCallbacks.KEY_DOSE_ID to item.doseId,
        WidgetActionCallbacks.KEY_DELTA to delta,
    )
    Box(
        modifier = GlanceModifier
            .size(WidgetDimens.stepperButton)
            .background(FixedColorProvider(Color(0x14000000)))
            .cornerRadius(16.dp)
            .clickable(actionRunCallback<WidgetActionCallbacks.AdjustQuantityCallback>(params)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            modifier = GlanceModifier.size(14.dp),
            colorFilter = ColorFilter.tint(accent),
        )
    }
}

/**
 * Shown when there is nothing left to do (or nothing planned at all).
 *
 * [compact] collapses it to one line for the shortest tiles, where a centred two-line block would be
 * clipped rather than centred.
 */
@Composable
private fun WidgetCelebration(content: WidgetContent, compact: Boolean) {
    val context: Context = LocalContext.current
    val label = if (content.isEmpty) {
        context.getString(R.string.widget_no_plan)
    } else {
        context.getString(R.string.widget_all_done)
    }
    val color = if (content.isEmpty) WidgetPalette.onSurfaceVariant else WidgetPalette.taken

    if (compact) {
        Text(
            text = label,
            style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = GlanceModifier.fillMaxSize().padding(start = 4.dp),
        )
        return
    }

    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = TextStyle(
                    color = color,
                    fontSize = if (content.isEmpty) 14.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            if (!content.isEmpty) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = content.summary.progressLabel,
                    style = TextStyle(
                        color = WidgetPalette.onSurfaceVariant,
                        fontSize = 12.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Parameter bundle that opens the today screen scrolled to one dose. */
private fun openDoseParameters(item: WidgetDoseItem): ActionParameters =
    actionParametersOf(
        ActionParameters.Key<Long>(MainActivity.EXTRA_FOCUS_DOSE_ID) to item.doseId,
        ActionParameters.Key<Long>(MainActivity.EXTRA_FOCUS_EPOCH_DAY) to item.epochDay,
    )

/** Domain priority -> widget rendering priority. */
fun WidgetPriority.toUi(): WidgetPriorityUi = when (this) {
    WidgetPriority.OVERDUE -> WidgetPriorityUi.OVERDUE
    WidgetPriority.DUE_SOON -> WidgetPriorityUi.DUE_SOON
    WidgetPriority.LATER_TODAY -> WidgetPriorityUi.LATER_TODAY
    WidgetPriority.DONE -> WidgetPriorityUi.TAKEN
}

/** Convenience so the receiver can build a tinted [ColorProvider] from a raw colour. */
internal fun colorProviderOf(color: Color): ColorProvider = FixedColorProvider(color)
