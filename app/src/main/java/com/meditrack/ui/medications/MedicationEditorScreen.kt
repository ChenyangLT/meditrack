package com.meditrack.ui.medications

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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.core.util.MedicationVisuals
import com.meditrack.data.local.entity.DosageForm
import com.meditrack.data.local.entity.DosageUnit
import com.meditrack.data.local.entity.FoodTiming
import com.meditrack.data.local.entity.MedicationColorTag
import com.meditrack.data.local.entity.MedicationIcon
import com.meditrack.data.local.entity.RepeatRuleType
import com.meditrack.core.theme.prefs
import com.meditrack.ui.components.AdaptiveButtonRow
import com.meditrack.ui.components.AdaptiveChipRow

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
    val snackbarHostState = remember { SnackbarHostState() }
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
            TimeWheelDialog(
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
 * Time picker in a dialog, using Material 3's circular dial.
 *
 * ## Why this replaced a custom wheel
 *
 * A vertically draggable wheel was written for this dialog and removed. It was worse in two ways:
 *
 *  - **It shipped bugs.** The wheel reported the wrong item as selected (the first *visible* row is
 *    not the row inside the highlight band when the list carries content padding), and the follow-up
 *    fix made the hour snap back to its initial value while the user was setting the minute. Custom
 *    scroll arithmetic in a two-way-bound picker is easy to get subtly wrong and hard to see.
 *  - **It was not obviously easier to use.** Two independently scrolling columns invite a drag to
 *    land on the wrong column, and the wheel exposes no typed input.
 *
 * The material dial is framework code with the state handled by [rememberTimePickerState], so hour
 * and minute cannot desynchronise. It also supports tap-to-select and typed entry, which the wheel
 * never did.
 *
 * The chosen time is held in the picker's own state and only committed on 确定, so a mis-tap can be
 * abandoned with 取消.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeWheelDialog(
    initialMinuteOfDay: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val use24Hour = MaterialTheme.prefs.use24HourFormat
    val state = rememberTimePickerState(
        initialHour = DateTimeUtils.hourOf(initialMinuteOfDay),
        initialMinute = DateTimeUtils.minuteOf(initialMinuteOfDay),
        is24Hour = use24Hour,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择服药时间") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            // Read straight from the picker state at confirm time: there is no second copy of the
            // value that could drift from what the dial is showing.
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
