package com.meditrack.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
import com.meditrack.MainActivity
import com.meditrack.R
import com.meditrack.domain.plan.WidgetContent
import com.meditrack.domain.plan.WidgetDoseItem
import com.meditrack.domain.plan.WidgetPriority

/**
 * The home-screen widget content.
 *
 * Ordering and colour are owned by [com.meditrack.domain.plan.WidgetPlanner] and are the core
 * product promise:
 *
 *   1. due within 30 minutes -> **yellow**, first
 *   2. its time has passed   -> **red**
 *   3. later today           -> neutral
 *   4. taken                 -> **green**
 *   5. skipped               -> muted
 *
 * When everything is done the list is replaced by the encouragement line, so a completed day reads
 * as an achievement rather than an empty box.
 *
 * ## Sizing
 *
 * There is exactly one widget in the launcher's picker. It lands at a comfortable size and the user
 * drags it to whatever shape they want; the layout derives everything from [LocalSize] through
 * [WidgetSizing], so every size between the declared floor and a full screen looks deliberate rather
 * than clipped.
 *
 * ## No buttons
 *
 * An earlier revision offered "+" / "-" per row. They are gone: recording a dose from a home-screen
 * tap is a worse interaction than opening the app (no confirmation, no way to correct a mis-tap, and
 * the over-dose guard cannot raise its dialog from a widget), and they consumed the width the
 * medication name needs. A row is now a single tap target that opens that dose in the app, which is
 * the one action a home-screen list is actually good at.
 */
@Composable
fun MediTrackWidgetContent(content: WidgetContent, options: WidgetDisplayOptions) {
    val size = LocalSize.current
    val context = LocalContext.current
    val layout = WidgetSizing.layoutFor(
        heightDp = size.height.value,
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
                    WidgetDoseRow(item, GlanceModifier.defaultWeight())
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
        WidgetIcons.MedicationGlyph(
            name = HEADER_GLYPH,
            tint = WidgetPalette.forPriority(WidgetPriority.LATER_TODAY),
            modifier = GlanceModifier.size(16.dp),
        )
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
                    color = WidgetPalette.onSurfaceVariant,
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
                colorFilter = ColorFilter.tint(WidgetPalette.forPriority(WidgetPriority.TAKEN)),
            )
        }
    }
}

/**
 * One dose row: status dot, medication name, time and progress, and the status label.
 *
 * The whole row opens the today screen focused on this dose - the single action a home-screen list
 * should offer.
 */
@Composable
private fun WidgetDoseRow(
    item: WidgetDoseItem,
    modifier: GlanceModifier = GlanceModifier,
) {
    val accent = WidgetPalette.forPriority(item.priority)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(WidgetPalette.rowSurface(item.priority))
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
            // The «复查» line rides with the medication it belongs to, rather than living in a second list
            // the user would have to reconcile against this one. It costs no extra row: the existing second
            // line already carries a time and a dose, and appending this would push the dose off the edge on
            // a narrow tile, so it gets its own - only when there is something to say, which for most
            // medications most of the time is nothing.
            item.reviewLabel?.let { review ->
                Text(
                    text = review,
                    style = TextStyle(
                        // Red once the review is due, because that is the one state the user has to act on;
                        // the ordinary countdown stays the same muted grey as the rest of the metadata.
                        color = if (item.reviewDue) WidgetPalette.alert else WidgetPalette.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = if (item.reviewDue) FontWeight.Medium else FontWeight.Normal,
                    ),
                    maxLines = 1,
                )
            }
        }

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
 * The dense single-line form, used only when the tile is too short for the two-line row.
 *
 * It trades the icon and the quantity for the two facts that still matter at that size: which
 * medication, and when it is due.
 */
@Composable
private fun WidgetStripRow(item: WidgetDoseItem) {
    val accent = WidgetPalette.forPriority(item.priority)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(WidgetPalette.rowSurface(item.priority))
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
    val color = if (content.isEmpty) {
        WidgetPalette.onSurfaceVariant
    } else {
        WidgetPalette.forPriority(WidgetPriority.TAKEN)
    }

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

/**
 * The glyph shown beside the title.
 *
 * A fixed family rather than the day's first medication: the title is about the app, not about any
 * one dose, and borrowing a row's icon here would imply a relationship that does not exist.
 */
private const val HEADER_GLYPH = "HEART"
