package com.meditrack.ui.medications

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.core.util.MedicationVisuals
import com.meditrack.core.util.QuantityFormatter
import com.meditrack.data.local.entity.DosageForm
import com.meditrack.data.local.entity.DosageUnit
import com.meditrack.data.local.entity.FoodTiming
import com.meditrack.data.local.entity.MedicationColorTag
import com.meditrack.data.local.entity.MedicationIcon
import com.meditrack.data.local.entity.MedicationReviewConfig
import com.meditrack.data.local.entity.MedicationReviewCycle
import com.meditrack.data.local.entity.RepeatRuleType
import com.meditrack.data.local.entity.ReviewCountMode
import com.meditrack.data.local.entity.ReviewSearchQuery
import com.meditrack.core.theme.prefs
import com.meditrack.domain.review.ReviewProgress
import com.meditrack.ui.components.AdaptiveButtonRow
import com.meditrack.ui.components.AdaptiveChipRow
import com.meditrack.ui.components.TimePickerDialog
import kotlinx.coroutines.launch

/**
 * 添加 / 编辑药品.
 *
 * The form is grouped into four cards, ordered by how often a user touches them:
 *
 *  1. **基本信息** - name, icon, colour. These are what the today list and the widget display.
 *  2. **剂量** - form, unit, strength, per-dose amount, over-dose ceiling.
 *  3. **服药时间** - the reminder slots, each with its own repeat rule.
 *  4. **更多设置** - food timing, note, stock, start/end dates.
 *
 * Validation is deliberately forgiving: the only hard requirements are a name and at least one
 * time. Everything else has a working default, because a half-configured medication that reminds
 * you is better than a form the user abandoned.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MedicationEditorScreen(
    medicationId: Long,
    onDone: () -> Unit,
    viewModel: MedicationEditorViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val reviewProgress by viewModel.reviewProgress.collectAsStateWithLifecycle()
    val reviewHistory by viewModel.reviewHistory.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(saved) { if (saved) onDone() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorShown()
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个药品？") },
            text = { Text("该药品的历史服药记录也会一起删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditing) "编辑药品" else "添加药品") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (viewModel.isEditing) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(
                        onClick = viewModel::save,
                        enabled = form.isValid,
                    ) {
                        Text(
                            text = "保存",
                            fontWeight = FontWeight.SemiBold,
                            color = if (form.isValid) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Tagged so tests can scroll the list to a section with performScrollToNode.
                .testTag(com.meditrack.ui.MediTrackTestTags.EDITOR_LIST),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BasicInfoCard(
                    form = form,
                    onName = viewModel::setName,
                    onIcon = viewModel::setIcon,
                    onColor = viewModel::setColorTag,
                )
            }
            item {
                DoseCard(
                    form = form,
                    onForm = viewModel::setDosageForm,
                    onUnit = viewModel::setUnit,
                    onStrength = viewModel::setStrength,
                    onDoseAmount = viewModel::setDoseAmount,
                    onMaxDose = viewModel::setMaxDoseAmount,
                )
            }
            item {
                ScheduleCard(
                    form = form,
                    onAddSlot = viewModel::addSlot,
                    onRemoveSlot = viewModel::removeSlot,
                    onTime = viewModel::setSlotTime,
                    onRepeatType = viewModel::setSlotRepeatType,
                    onInterval = viewModel::setSlotInterval,
                    onToggleWeekday = viewModel::toggleSlotWeekday,
                    onToggleMonthDay = viewModel::toggleSlotMonthDay,
                    onCycle = viewModel::setSlotCycle,
                    onReminderEnabled = viewModel::setSlotReminderEnabled,
                )
            }
            item {
                ReviewCard(
                    form = form,
                    progress = reviewProgress,
                    history = reviewHistory,
                    onReminderEnabled = viewModel::setReviewReminderEnabled,
                    onNote = viewModel::setReviewNote,
                    onSearchQuery = viewModel::setReviewSearchQuery,
                    onCountMode = viewModel::setReviewCountMode,
                    onThreshold = viewModel::setReviewThreshold,
                    onStartNewRound = viewModel::startNewReviewRound,
                    onMessage = { text -> scope.launch { snackbarHostState.showSnackbar(text) } },
                )
            }
            item {
                AdvancedCard(
                    form = form,
                    onFoodTiming = viewModel::setFoodTiming,
                    onNote = viewModel::setNote,
                    onStock = viewModel::setStockAmount,
                    onStockAlert = viewModel::setStockAlertThreshold,
                    onStartDate = viewModel::setStartEpochDay,
                    onEndDate = viewModel::setEndEpochDay,
                    onReminderEnabled = viewModel::setReminderEnabled,
                    onActive = viewModel::setIsActive,
                )
            }
        }
    }
}

/** A titled card; every section uses this so the page has one visual rhythm. */
@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BasicInfoCard(
    form: MedicationForm,
    onName: (String) -> Unit,
    onIcon: (MedicationIcon) -> Unit,
    onColor: (MedicationColorTag) -> Unit,
) {
    SectionCard(title = "基本信息") {
        OutlinedTextField(
            value = form.name,
            onValueChange = onName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("药品名称") },
            placeholder = { Text("例如：阿司匹林肠溶片") },
            isError = form.nameError && form.name.isNotEmpty(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )

        Spacer(modifier = Modifier.height(14.dp))
        Text("图标", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MedicationIcon.entries.forEach { icon ->
                val selected = form.icon == icon
                Box(
                    modifier = Modifier
                        .size(if (MaterialTheme.prefs.simplifiedMode) 52.dp else 44.dp)
                        .background(
                            color = if (selected) {
                                MedicationVisuals.container(form.colorTag)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                            shape = CircleShape,
                        )
                        .clickable { onIcon(icon) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MedicationVisuals.icon(icon, filled = selected),
                        contentDescription = icon.label,
                        tint = if (selected) MedicationVisuals.color(form.colorTag)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text("颜色标签", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MedicationColorTag.entries.forEach { tag ->
                val selected = form.colorTag == tag
                Box(
                    modifier = Modifier
                        .size(if (MaterialTheme.prefs.simplifiedMode) 44.dp else 36.dp)
                        .background(MedicationVisuals.color(tag), CircleShape)
                        .clickable { onColor(tag) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "${tag.label}（已选择）",
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoseCard(
    form: MedicationForm,
    onForm: (DosageForm) -> Unit,
    onUnit: (DosageUnit) -> Unit,
    onStrength: (String) -> Unit,
    onDoseAmount: (String) -> Unit,
    onMaxDose: (String) -> Unit,
) {
    SectionCard(title = "剂量") {
        AdaptiveChipRow(
            title = "剂型",
            options = DosageForm.entries,
            selected = form.dosageForm,
            labelOf = { it.label },
            onSelect = onForm,
        )

        AdaptiveChipRow(
            title = "单位",
            options = DosageUnit.entries,
            selected = form.unit,
            labelOf = { it.label },
            onSelect = onUnit,
        )

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = form.strength,
            onValueChange = onStrength,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("规格（可选）") },
            placeholder = { Text("例如：100mg/片") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.doseAmount,
                onValueChange = onDoseAmount,
                modifier = Modifier.weight(1f),
                label = { Text("每次剂量") },
                suffix = { Text(form.unit.label) },
                isError = form.doseInvalid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (form.unit.allowsFraction) KeyboardType.Decimal
                    else KeyboardType.Number,
                ),
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = form.maxDoseAmount,
                onValueChange = onMaxDose,
                modifier = Modifier.weight(1f),
                label = { Text("单次上限") },
                suffix = { Text(form.unit.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (form.unit.allowsFraction) KeyboardType.Decimal
                    else KeyboardType.Number,
                ),
                shape = RoundedCornerShape(14.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "单次上限填 0 表示不限制；超过时会先弹窗确认「多服」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(
    form: MedicationForm,
    onAddSlot: () -> Unit,
    onRemoveSlot: (Long) -> Unit,
    onTime: (Long, Int) -> Unit,
    onRepeatType: (Long, RepeatRuleType) -> Unit,
    onInterval: (Long, Int) -> Unit,
    onToggleWeekday: (Long, Int) -> Unit,
    onToggleMonthDay: (Long, Int) -> Unit,
    onCycle: (Long, Int, Int) -> Unit,
    onReminderEnabled: (Long, Boolean) -> Unit,
) {
    var editingSlotKey by remember { mutableStateOf<Long?>(null) }

    editingSlotKey?.let { key ->
        val slot = form.slots.firstOrNull { it.key == key }
        if (slot != null) {
            TimePickerDialog(
                title = "选择服药时间",
                initialMinuteOfDay = slot.minuteOfDay,
                onConfirm = { minute ->
                    onTime(key, minute)
                    editingSlotKey = null
                },
                onDismiss = { editingSlotKey = null },
            )
        } else {
            editingSlotKey = null
        }
    }

    SectionCard(
        title = "服药时间",
        trailing = {
            TextButton(onClick = onAddSlot) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("添加时间")
            }
        },
    ) {
        if (form.slots.isEmpty()) {
            Text(
                text = "请至少添加一个服药时间，到点才会提醒。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        form.slots.sortedBy { it.minuteOfDay }.forEachIndexed { index, slot ->
            if (index > 0) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The time is the primary affordance: tapping it opens the wheel picker.
                    Card(
                        onClick = { editingSlotKey = slot.key },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = DateTimeUtils.formatMinuteOfDay(
                                    slot.minuteOfDay,
                                    MaterialTheme.prefs.use24HourFormat,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Icon(
                        imageVector = Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = if (slot.reminderEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Switch(
                        checked = slot.reminderEnabled,
                        onCheckedChange = { onReminderEnabled(slot.key, it) },
                    )
                    if (form.slots.size > 1) {
                        IconButton(onClick = { onRemoveSlot(slot.key) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "删除这个时间",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                AdaptiveChipRow(
                    title = "重复",
                    options = RepeatRuleType.entries,
                    selected = slot.repeatType,
                    labelOf = { it.label },
                    onSelect = { onRepeatType(slot.key, it) },
                )

                when (slot.repeatType) {
                    RepeatRuleType.WEEKLY -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        // Seven day chips wrap rather than squeeze: the row has no title of its
                        // own, so it stays a bare FlowRow.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, name ->
                                val iso = i + 1
                                FilterChip(
                                    selected = slot.daysOfWeek.contains(iso),
                                    onClick = { onToggleWeekday(slot.key, iso) },
                                    label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                    RepeatRuleType.EVERY_N_DAYS -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        NumberStepperRow(
                            label = "间隔天数",
                            value = slot.intervalDays,
                            range = 1..30,
                            onChange = { onInterval(slot.key, it) },
                        )
                    }
                    RepeatRuleType.CYCLE -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        NumberStepperRow(
                            label = "吃几天",
                            value = slot.cycleOnDays,
                            range = 1..90,
                            onChange = { onCycle(slot.key, it, slot.cycleOffDays) },
                        )
                        NumberStepperRow(
                            label = "停几天",
                            value = slot.cycleOffDays,
                            range = 0..90,
                            onChange = { onCycle(slot.key, slot.cycleOnDays, it) },
                        )
                    }
                    RepeatRuleType.MONTHLY_DATES -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "选择每月的哪几天服药（可多选）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        MonthDayGrid(
                            selected = slot.daysOfMonth,
                            onToggle = { day -> onToggleMonthDay(slot.key, day) },
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        var pickingDate by remember { mutableStateOf(false) }
                        if (pickingDate) {
                            CalendarDateSheet(
                                onConfirm = { date ->
                                    // Picking a calendar date selects that day-of-month; the month
                                    // itself repeats, which is what "每月几号" means.
                                    onToggleMonthDay(slot.key, date.dayOfMonth)
                                    pickingDate = false
                                },
                                onDismiss = { pickingDate = false },
                            )
                        }
                        TextButton(onClick = { pickingDate = true }) {
                            Icon(
                                Icons.Filled.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("从日历选择日期")
                        }
                        Text(
                            text = "例如选了 31 号，2 月没有 31 号时会在 2 月最后一天提醒。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

/**
 * Month-day selector: a compact 1..31 grid.
 *
 * Sized as a wrapping grid rather than a row so all 31 entries stay reachable on a phone without
 * horizontal scrolling - the earlier weekday row works only because there are exactly seven.
 */
@Composable
private fun MonthDayGrid(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    // 40dp-wide targets, laid out in rows of seven to echo a calendar month. The width stays
    // exact so the columns line up; the height is a minimum rather than a fixed 40dp so a larger
    // font makes the rows taller instead of letting the numbers spill into the row below.
    val days = remember { (1..31).toList() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        days.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    val isSelected = selected.contains(day)
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .heightIn(min = 40.dp)
                            .background(
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                },
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { onToggle(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = day.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                // Pad the last row (31 days = 4 rows of 7 + 3) so columns stay aligned.
                repeat(7 - week.size) { Box(modifier = Modifier.width(40.dp)) }
            }
        }
    }
}

/** Compact +/- numeric row used by the repeat-rule options. */
@Composable
private fun NumberStepperRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    // The label and the two buttons share one line on purpose, so this is a wrapping row rather
    // than a fixed Row: at a large font scale the label plus two buttons no longer fit, and a Row
    // hands the trailing "+" whatever is left - which can be nothing.
    AdaptiveButtonRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        TextButton(
            onClick = { if (value > range.first) onChange(value - 1) },
            enabled = value > range.first,
        ) { Text("−") }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        TextButton(
            onClick = { if (value < range.last) onChange(value + 1) },
            enabled = value < range.last,
        ) { Text("+") }
    }
}

/**
 * «复查提醒» - "这个药吃多久要去复查".
 *
 * ## Why the section exists at all
 *
 * The app knows the schedule and the dose, and it knows nothing about what the prescription is *for*.
 * "多长时间复查" is a clinical judgement, so the section offers the two honest answers instead of
 * inventing a third: write down what the doctor said ([MedicationReviewConfig.note]) or look it up
 * ([ReviewSearchQuery], opened in the user's own browser - nothing about the prescription leaves the
 * app). Only then does a threshold mean anything, and only then does the reminder count.
 *
 * ## Why the progress bar reads the draft, not the database
 *
 * The bar is the feedback for the number being typed: a user who enters 30 should see "已 0 / 30 次"
 * immediately, before saving. That is why the ViewModel pairs the *stored* round's count with the
 * *draft's* mode and threshold, and why this composable must not re-read the medication row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(
    form: MedicationForm,
    progress: ReviewProgress?,
    history: List<MedicationReviewCycle>,
    onReminderEnabled: (Boolean) -> Unit,
    onNote: (String) -> Unit,
    onSearchQuery: (String) -> Unit,
    onCountMode: (ReviewCountMode) -> Unit,
    onThreshold: (String) -> Unit,
    onStartNewRound: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = MaterialTheme.prefs
    val quantityUnit = form.unit.label
    val unitLabel = if (form.reviewCountMode == ReviewCountMode.QUANTITY) {
        quantityUnit
    } else {
        form.reviewCountMode.unitShort
    }

    // The drug name is what makes the query worth searching. Before it is typed, a readable
    // placeholder is better than a preview that starts with a blank.
    val medicationName = form.name.ifBlank { "这个药" }
    val question = form.reviewSearchQuery.ifBlank { ReviewSearchQuery.DEFAULT_QUESTION }
    val queryText = ReviewSearchQuery.text(medicationName, question, prefs.reviewSearchSuffix)
    val searchUrl = ReviewSearchQuery.url(
        engine = prefs.reviewEngine,
        medicationName = medicationName,
        question = question,
        suffix = prefs.reviewSearchSuffix,
    )

    SectionCard(
        title = "复查提醒",
        modifier = Modifier.testTag(com.meditrack.ui.MediTrackTestTags.REVIEW_SECTION),
    ) {
        Text(
            text = "这个药吃多久要去复查，应用没法自己判断——只有开药的医生知道。" +
                "你可以把医生说的话写下来，也可以先搜一下。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ------------------------------------------------------------- 搜索

        Spacer(modifier = Modifier.height(12.dp))
        Text("关于搜索", style = MaterialTheme.typography.labelLarge)

        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = {
                // The browser is the one component that answers this question, and it is also the one
                // this app does not control: a phone without any browser installed (or with the
                // intent filter removed by its ROM) must produce a sentence, not a crash.
                val opened = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)))
                }.isSuccess
                if (!opened) onMessage("这台手机没有可用的浏览器")
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MaterialTheme.prefs.minTouchTargetDp.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Deliberately the full query on the button: the user is about to hand a sentence to a
            // search engine, and "去搜索" alone would not tell them what it is.
            Text("去搜索：$queryText")
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "将用「${prefs.reviewEngine.label}」搜索：$queryText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "在浏览器里打开，应用不会上传你的用药信息。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = form.reviewSearchQuery,
            onValueChange = onSearchQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索问句") },
            placeholder = { Text(ReviewSearchQuery.DEFAULT_QUESTION) },
            supportingText = {
                Text("最多 ${MedicationEditorViewModel.MAX_SEARCH_QUERY_LENGTH} 个字，药品名会自动加在最前面。")
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )

        Spacer(modifier = Modifier.height(6.dp))
        // Wrapping, not a Row: six suggested questions never fit one line at the elderly font scale.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReviewSearchQuery.SUGGESTED.forEach { suggestion ->
                AssistChip(
                    onClick = { onSearchQuery(suggestion) },
                    label = {
                        Text(suggestion, style = MaterialTheme.typography.labelMedium)
                    },
                    leadingIcon = if (suggestion == ReviewSearchQuery.DEFAULT_QUESTION) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        // ------------------------------------------------------------- 备注

        Spacer(modifier = Modifier.height(14.dp))
        Text("关于备注", style = MaterialTheme.typography.labelLarge)

        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = form.reviewNote,
            onValueChange = onNote,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("医生怎么说（复查备注）") },
            placeholder = { Text("例如：3 个月后复查肝功能") },
            minLines = 3,
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "这段话会原样显示在红色的复查通知和锁屏上，所以请写得简短、准确，" +
                "例如复查项目和大概时间。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------------------------------------------------------- 计数方式

        AdaptiveChipRow(
            title = "计数方式",
            options = ReviewCountMode.entries,
            selected = form.reviewCountMode,
            labelOf = { it.label },
            onSelect = onCountMode,
        )
        Text(
            text = when (form.reviewCountMode) {
                ReviewCountMode.DOSES -> "只统计真正记录为「已服」的次数；跳过和漏服不算。"
                ReviewCountMode.DAYS -> "从开始计数那天算起，按自然日计算，第一天算第 1 天。"
                ReviewCountMode.QUANTITY -> "把每次服用的量累加起来，按${quantityUnit}计算。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ------------------------------------------------------------- 阈值

        Spacer(modifier = Modifier.height(10.dp))
        Text("复查阈值", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))

        val presets = when (form.reviewCountMode) {
            ReviewCountMode.DOSES -> MedicationReviewConfig.DOSE_PRESETS
            ReviewCountMode.DAYS -> MedicationReviewConfig.DAY_PRESETS
            // An accumulated amount is expressed in whatever the medication is measured in, so there
            // is no list of plausible values to offer - the field below is the only input.
            ReviewCountMode.QUANTITY -> emptyList()
        }
        if (presets.isEmpty()) {
            Text(
                text = "累计剂量没有常用值，请直接填写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
        } else {
            // AdaptiveButtonRow rather than a Row: seven presets wrap onto a second line instead of
            // squeezing the last chip down to nothing.
            AdaptiveButtonRow(modifier = Modifier.fillMaxWidth()) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = form.reviewThresholdValue == preset.toDouble(),
                        onClick = { onThreshold(preset.toString()) },
                        label = {
                            Text("$preset $unitLabel", style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        OutlinedTextField(
            value = form.reviewThreshold,
            onValueChange = onThreshold,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("达到多少就提醒复查") },
            suffix = { Text(unitLabel) },
            placeholder = { Text("0") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "填 0 表示不提醒复查。保存后如果改了这个数字，正在进行的这一轮会自动按新数字计算。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ------------------------------------------------------------- 进度

        Spacer(modifier = Modifier.height(12.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.fraction.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            AdaptiveButtonRow(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = progress.progressLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = progress.remainingLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (progress.isReached) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Text(
                text = "上面填一个大于 0 的数字后，这里会显示还差多少。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // -------------------------------------------------- 到达阈值后的处理

        if (progress?.isReached == true) {
            Spacer(modifier = Modifier.height(12.dp))
            // errorContainer, not a plain card: this is the one state in the editor that asks the user
            // to do something outside the app (go to the doctor), so it has to be the loudest thing here.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "该去复查了",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "已经数到 ${progress.threshold.toInt()} $unitLabel" +
                            "。复查后点下面的按钮，计数会从 0 重新开始，提醒也会重新打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onStartNewRound,
                        modifier = Modifier.heightIn(min = MaterialTheme.prefs.minTouchTargetDp.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.EventAvailable,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("开始新一轮")
                    }
                }
            }
        }

        // ------------------------------------------------------------- 开关

        Spacer(modifier = Modifier.height(6.dp))
        SwitchRow(
            title = "开启复查提醒",
            subtitle = "到时间会发一条醒目的通知。数到的当天会自动关闭，" +
                "点「开始新一轮」后会重新打开。",
            checked = form.reviewReminderEnabled,
            onCheckedChange = onReminderEnabled,
        )

        // ------------------------------------------------------------- 上一轮

        history.firstOrNull()?.let { last ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "上一轮：第 ${last.round} 轮，" +
                    "${last.acknowledgedEpochDay?.let { DateTimeUtils.formatDate(it) } ?: "已结束"} 复查，" +
                    "共 ${startedRoundCountLabel(last, quantityUnit)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "还没保存的内容，会跟这个药品一起保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "60 次" / "30 天" / "240 ml" - how much a finished round counted. */
private fun startedRoundCountLabel(cycle: MedicationReviewCycle, quantityUnit: String): String =
    when (cycle.countMode) {
        ReviewCountMode.DOSES -> "${cycle.count.toInt()} 次"
        ReviewCountMode.DAYS -> "${cycle.count.toInt()} 天"
        ReviewCountMode.QUANTITY -> "${QuantityFormatter.format(cycle.count)} $quantityUnit"
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedCard(
    form: MedicationForm,
    onFoodTiming: (FoodTiming) -> Unit,
    onNote: (String) -> Unit,
    onStock: (String) -> Unit,
    onStockAlert: (String) -> Unit,
    onStartDate: (Long) -> Unit,
    onEndDate: (Long?) -> Unit,
    onReminderEnabled: (Boolean) -> Unit,
    onActive: (Boolean) -> Unit,
) {
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }

    if (pickingStart) {
        DatePickerSheet(
            initialEpochDay = form.startEpochDay,
            onConfirm = {
                onStartDate(it)
                pickingStart = false
            },
            onDismiss = { pickingStart = false },
        )
    }
    if (pickingEnd) {
        DatePickerSheet(
            initialEpochDay = form.endEpochDay ?: form.startEpochDay,
            onConfirm = {
                onEndDate(it)
                pickingEnd = false
            },
            onDismiss = { pickingEnd = false },
        )
    }

    SectionCard(title = "更多设置") {
        AdaptiveChipRow(
            title = "服用时机",
            options = FoodTiming.entries,
            selected = form.foodTiming,
            labelOf = { it.label },
            onSelect = onFoodTiming,
        )

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = form.note,
            onValueChange = onNote,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备注") },
            placeholder = { Text("例如：温水送服，不要空腹") },
            minLines = 2,
            shape = RoundedCornerShape(14.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.stockAmount,
                onValueChange = onStock,
                modifier = Modifier.weight(1f),
                label = { Text("库存") },
                suffix = { Text(form.unit.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = form.stockAlertThreshold,
                onValueChange = onStockAlert,
                modifier = Modifier.weight(1f),
                label = { Text("库存提醒") },
                suffix = { Text(form.unit.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(14.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "库存填 0 表示不追踪；吃药后会自动扣减，低于提醒值时提示补货。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DateField(
                label = "开始日期",
                epochDay = form.startEpochDay,
                modifier = Modifier.weight(1f),
                onClick = { pickingStart = true },
            )
            DateField(
                label = "结束日期",
                epochDay = form.endEpochDay,
                modifier = Modifier.weight(1f),
                onClick = { pickingEnd = true },
                onClear = { onEndDate(null) },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        SwitchRow(
            title = "开启提醒",
            subtitle = "关闭后仍会出现在今日列表，但不会发通知",
            checked = form.reminderEnabled,
            onCheckedChange = onReminderEnabled,
        )
        SwitchRow(
            title = "启用这个药品",
            subtitle = "停用后不再出现在今日计划中，历史记录保留",
            checked = form.isActive,
            onCheckedChange = onActive,
        )
    }
}

@Composable
private fun DateField(
    label: String,
    epochDay: Long?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = epochDay?.let { DateTimeUtils.formatDate(it) } ?: "不设结束",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (onClear != null && epochDay != null) {
                IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "清除结束日期",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Calendar sheet used by the "从日历选择日期" shortcut.
 *
 * Returns a full [java.time.LocalDate] so the caller can pick whichever part it needs - the monthly
 * rule takes the day-of-month, matching what "每月几号" means.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDateSheet(
    onConfirm: (java.time.LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = DateTimeUtils.startOfDayMillis(DateTimeUtils.todayEpochDay()),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        // The picker works in UTC millis; convert to the local date so the value
                        // matches what the calendar actually shows.
                        onConfirm(
                            java.time.Instant.ofEpochMilli(millis)
                                .atZone(java.time.ZoneOffset.UTC)
                                .toLocalDate()
                        )
                    } else {
                        onDismiss()
                    }
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}

/** Calendar picker for the start/end dates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initialEpochDay: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = DateTimeUtils.startOfDayMillis(initialEpochDay),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        // The picker works in UTC millis; convert to the local epoch day so the
                        // stored value matches what the calendar shows.
                        val date = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneOffset.UTC)
                            .toLocalDate()
                        onConfirm(date.toEpochDay())
                    } else {
                        onDismiss()
                    }
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}
