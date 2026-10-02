package com.meditrack.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.meditrack.core.theme.prefs
import com.meditrack.core.util.DateTimeUtils

/**
 * "选择时间", as a dialog over whatever screen needs a time-of-day.
 *
 * Shared between the medication editor's 服药时间 and the settings screen's 免打扰时段, so both agree
 * on how a time is chosen and formatted. It takes and returns **minutes from midnight**, which is how
 * every time in this app is stored; the caller never deals with hours or a formatted string.
 *
 * The value lives in the picker's own state and is read at confirm time, so what is committed cannot
 * drift from what the dial is showing. 确定 commits, 取消 abandons — a mis-tap costs nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    title: String,
    initialMinuteOfDay: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = DateTimeUtils.hourOf(initialMinuteOfDay),
        initialMinute = DateTimeUtils.minuteOf(initialMinuteOfDay),
        is24Hour = MaterialTheme.prefs.use24HourFormat,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
