package com.meditrack.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditrack.R
import com.meditrack.core.theme.doseColors
import com.meditrack.core.theme.prefs
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.core.util.MedicationVisuals
import com.meditrack.core.util.QuantityFormatter
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.local.entity.MedicationIcon
import com.meditrack.domain.plan.DoseView
import com.meditrack.domain.plan.TodaySummary
import com.meditrack.ui.components.DoseStatusChip
import com.meditrack.ui.components.QuantityStepper
import com.meditrack.ui.components.doseStatusVisuals

/**
 * 今日 - the default tab and the screen the user actually lives in.
 *
 * Structure, top to bottom:
 *  1. **Progress header** - "已完成 3/5" plus a progress bar and the next dose. This is the single
 *     answer to "do I still need to take anything?", so it is the largest element on screen.
 *  2. **Dose cards** - one per scheduled time, sorted by clock time. Each card carries the status
 *     chip, the time, the note and the +/- stepper.
 *  3. **Day navigation** - yesterday/today/tomorrow, because a user who forgot to record last
 *     night's dose needs to fix it without hunting through the calendar.
 *
 * When the day is complete the screen says so explicitly ("今日用药已完成 ✅") rather than showing an
 * empty page, which is the same promise the widget makes.
 */
@Composable
fun TodayScreen(
    onAddMedication: () -> Unit,
    onOpenMedicationId: (Long) -> Unit,
    /** Dose to scroll to and highlight, supplied when the screen is opened from a reminder. */
    initialFocusDoseId: Long = -1L,
    /** Day to open, supplied by the widget when the user taps a row from a past day. */
    initialFocusEpochDay: Long = Long.MIN_VALUE,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Honour the deep-link arguments exactly once; a recomposition must not re-snap the list
    // back to the notified dose after the user has scrolled away.
    LaunchedEffect(initialFocusDoseId, initialFocusEpochDay) {
        if (initialFocusDoseId > 0L) {
            viewModel.focusOn(initialFocusDoseId, initialFocusEpochDay)
        }
    }
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val focusDoseId by viewModel.focusDoseId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Toast-style events.
    LaunchedEffect(message) {
        when (val current = message) {
            is TodayMessage.Toast -> {
                snackbarHostState.showSnackbar(current.text)
                viewModel.onMessageShown()
            }
            else -> Unit
        }
    }

    // Scroll to the dose the notification or widget asked for.
    LaunchedEffect(focusDoseId, state.doses) {
        if (focusDoseId > 0L) {
            val index = state.doses.indexOfFirst { it.doseId == focusDoseId }
            if (index >= 0) {
                listState.animateScrollToItem(index)
                viewModel.clearFocus()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.isEmpty) {
                ExtendedFloatingActionButton(
                    onClick = onAddMedication,
                    modifier = Modifier.testTag(com.meditrack.ui.MediTrackTestTags.ADD_MEDICATION_FAB),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("添加药品") },
                )
            }
        },
    ) { padding ->
        // Over-dose confirmation, raised by the repository when a maximum is configured.
        // Rendered inside the Scaffold content so it participates in normal composition.
        val overDose = message as? TodayMessage.ConfirmOverDose
        if (overDose != null) {
            OverDoseDialog(
                dose = overDose.dose,
                attempted = overDose.attemptedQuantity,
                max = overDose.maxQuantity,
                onConfirm = { viewModel.confirmOverDose(overDose.dose, true) },
                onDismiss = { viewModel.confirmOverDose(overDose.dose, false) },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DayNavigator(
                epochDay = state.day?.epochDay ?: DateTimeUtils.todayEpochDay(),
                onPrevious = viewModel::showPreviousDay,
                onNext = viewModel::showNextDay,
                onToday = viewModel::showToday,
            )

            state.summary?.let { summary ->
                if (!summary.isEmpty) {
                    TodayProgressHeader(summary = summary, doses = state.doses)
                }
            }

            if (state.isEmpty) {
                EmptyTodayState(onAddMedication = onAddMedication)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = state.doses,
                        key = { dose -> "${dose.scheduleId}-${dose.epochDay}" },
                    ) { dose ->
                        DoseCard(
                            dose = dose,
                            highlighted = dose.doseId == focusDoseId,
                            onIncrease = { viewModel.increase(dose) },
                            onDecrease = { viewModel.decrease(dose) },
                            onMarkTaken = { viewModel.markTaken(dose) },
                            onSkip = { viewModel.skip(dose) },
                            onUnskip = { viewModel.unskip(dose) },
                            onSnooze = { minutes -> viewModel.snooze(dose, minutes) },
                            onUndo = { viewModel.undo(dose) },
                            onReset = { viewModel.reset(dose) },
                            onOpenMedication = { onOpenMedicationId(dose.medicationId) },
                        )
                    }
                }
            }
        }
    }
}

/** Yesterday / today / tomorrow switcher with a friendly date label. */
@Composable
private fun DayNavigator(
    epochDay: Long,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val isToday = epochDay == DateTimeUtils.todayEpochDay()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "前一天")
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = DateTimeUtils.friendlyDateLabel(epochDay),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = DateTimeUtils.formatDateLong(epochDay),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!isToday) {
            TextButton(onClick = onToday) { Text("回到今天") }
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "后一天")
        }
    }
}

/**
 * The progress header.
 *
 * Two numbers matter and nothing else: how many doses are done, and when the next one is. The big
 * monospaced "3/5" is the hero, and the countdown to the next dose answers the question the user
 * actually opened the app with.
 */
@Composable
private fun TodayProgressHeader(summary: TodaySummary, doses: List<DoseView>) {
    val doseColors = MaterialTheme.doseColors
    val progress by animateFloatAsState(
        targetValue = summary.quantityFraction,
        label = "todayProgress",
    )

    val nextDose = remember(doses) {
        doses.filter { it.status == DoseStatus.DUE || it.status == DoseStatus.UPCOMING }
            .minByOrNull { it.plannedTimeMillis }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (summary.allDone) {
                doseColors.takenContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${summary.completedDoses}/${summary.totalDoses}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (summary.allDone) doseColors.taken else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.encouragement,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!summary.allDone) {
                        Text(
                            text = summary.quantityLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (summary.allDone) {
                    Icon(
                        imageVector = Icons.Filled.DoneAll,
                        contentDescription = null,
                        tint = doseColors.taken,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(CircleShape),
                color = if (summary.allDone) doseColors.taken else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            if (nextDose != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "下一次：${nextDose.timeLabel} ${nextDose.medicationName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (summary.missedDoses > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "有 ${summary.missedDoses} 次未服药记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = doseColors.missed,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * One dose card.
 *
 * Layout mirrors the reading order of the question "what, when, how much, did I take it":
 * colour rail + icon, then name and time, then the stepper and the status chip.
 */
@Composable
private fun DoseCard(
    dose: DoseView,
    highlighted: Boolean,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onMarkTaken: () -> Unit,
    onSkip: () -> Unit,
    onUnskip: () -> Unit,
    /** Called with the chosen delay in minutes. */
    onSnooze: (Int) -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onOpenMedication: () -> Unit,
) {
    // isOverdue turns a late-but-not-yet-swept dose red straight away; see DoseView.isOverdue.
    val visuals = doseStatusVisuals(dose.status, dose.isOverdue)
    val identityColor = MedicationVisuals.color(dose.medication.colorTag)
    var menuOpen by remember { mutableStateOf(false) }
    var snoozeOpen by remember { mutableStateOf(false) }

    if (snoozeOpen) {
        SnoozeDurationDialog(
            dose = dose,
            defaultMinutes = MaterialTheme.prefs.snoozeMinutes,
            onDismiss = { snoozeOpen = false },
            onConfirm = { minutes ->
                snoozeOpen = false
                onSnooze(minutes)
            },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlighted) 4.dp else 1.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Identity rail: the colour tag, so a user scanning the list can find "the blue one".
            // Fills the row's height so the rail runs the full card edge regardless of how
            // many context lines the medication has.
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(identityColor),
            )

            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Icon: filled once resolved, outlined while still open (shape = state cue).
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MedicationVisuals.container(dose.medication.colorTag), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MedicationVisuals.icon(dose.medication.icon, dose.status.isResolved),
                            contentDescription = null,
                            tint = identityColor,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = dose.medicationName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = dose.timeLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "  ·  ${dose.timingHint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("编辑药品") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenMedication()
                                },
                            )
                            if (dose.status == DoseStatus.SKIPPED) {
                                DropdownMenuItem(
                                    text = { Text("取消跳过") },
                                    leadingIcon = { Icon(Icons.Filled.Undo, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onUnskip()
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("跳过本次") },
                                    leadingIcon = { Icon(Icons.Filled.SkipNext, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onSkip()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("稍后提醒") },
                                leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    snoozeOpen = true
                                },
                            )
                            if (dose.isPersisted) {
                                DropdownMenuItem(
                                    text = { Text("撤销上一步") },
                                    leadingIcon = { Icon(Icons.Filled.Undo, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onUndo()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("重置记录") },
                                    leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onReset()
                                    },
                                )
                            }
                        }
                    }
                }

                // Context chips: strength, food timing, note. Hidden in simplified mode.
                val chips = remember(dose) {
                    buildList {
                        if (dose.medication.strength.isNotBlank()) add(dose.medication.strength)
                        if (dose.medication.foodTiming.shortLabel.isNotBlank()) {
                            add(dose.medication.foodTiming.label)
                        }
                        if (dose.medication.note.isNotBlank()) add(dose.medication.note)
                    }
                }
                if (chips.isNotEmpty() && !MaterialTheme.prefs.simplifiedMode) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = chips.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    QuantityStepper(
                        taken = dose.takenQuantity,
                        planned = dose.plannedQuantity,
                        unitLabel = dose.unitLabel,
                        step = dose.step,
                        onIncrease = onIncrease,
                        onDecrease = onDecrease,
                    )

                    Column(horizontalAlignment = Alignment.End) {
                        DoseStatusChip(status = dose.status)
                        AnimatedVisibility(visible = dose.status != DoseStatus.TAKEN) {
                            TextButton(onClick = onMarkTaken) {
                                Text("标记已服", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Empty state: guidance, not a dead end. */
@Composable
private fun EmptyTodayState(onAddMedication: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(88.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = MedicationVisuals.icon(MedicationIcon.TABLET, filled = true),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "今天没有用药计划",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "添加一个药品并设置服药时间，到点就会提醒你。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // NOTE: the call to action itself is the Scaffold's single FloatingActionButton. An earlier
        // revision also rendered an ExtendedFloatingActionButton here, which produced two identical
        // "添加药品" buttons stacked on top of each other on the empty today screen.
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "点击右下角的「添加药品」开始",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * "是否确认多服？"
 *
 * Shown only when the medication has a configured maximum and the next tap would exceed it. The
 * wording is deliberately factual and non-advisory: the app records what the user decides, it does
 * not tell them what to do.
 */
@Composable
private fun OverDoseDialog(
    dose: DoseView,
    attempted: Double,
    max: Double,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = null,
                tint = MaterialTheme.doseColors.partial,
            )
        },
        title = { Text("是否确认多服？") },
        text = {
            Text(
                "本次记录将达到 " +
                    QuantityFormatter.format(attempted, dose.unitLabel) +
                    "，超过你设置的每次最大剂量 " +
                    QuantityFormatter.format(max, dose.unitLabel) +
                    "。\n\n确认后会如实记录，如需帮助请咨询医生或药师。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认多服", color = MaterialTheme.doseColors.partial)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * "推迟多久提醒？" - lets the user pick the delay per reminder.
 *
 * The preview times are computed from the dose's **scheduled** time, matching what
 * DoseRepository.snooze actually applies. Rendering the resulting time next to each option is what
 * makes the rule self-explanatory: an earlier version measured the delay from the moment the button
 * was tapped, so tapping "10 分钟" on a 17:55 dose at 17:06 moved the reminder to 17:16 - earlier
 * than its own schedule. With the time shown, that class of mistake is visible rather than silent.
 *
 * The base is clamped forward to now exactly as the repository does, so the preview can never
 * promise a time in the past.
 */
@Composable
private fun SnoozeDurationDialog(
    dose: DoseView,
    defaultMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val options = listOf(5, 10, 15, 30, 60, 120)
    val now = System.currentTimeMillis()
    val scheduledBase = maxOf(dose.plannedTimeMillis, dose.snoozedUntilMillis ?: Long.MIN_VALUE)
    val base = maxOf(scheduledBase, now)

    // Selection and commit are separate steps on purpose. Committing on the first tap means a
    // mis-tap silently reschedules the reminder, and the only visible button saying "取消" makes
    // that worse. Here a tap only moves the radio; nothing changes until 推迟 is pressed.
    var selected by remember { mutableIntStateOf(defaultMinutes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.snooze_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.snooze_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                options.forEach { minutes ->
                    val at = DateTimeUtils.formatDateTime(base + minutes * 60_000L).takeLast(5)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selected = minutes }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = minutes == selected, onClick = { selected = minutes })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.snooze_option_at, minutes, at),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.snooze_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
