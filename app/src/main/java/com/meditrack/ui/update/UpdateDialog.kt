package com.meditrack.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.meditrack.data.update.UpdateInfo

/**
 * "有 1.8.0 了" - offered, never imposed.
 *
 * Two ways out and neither is punishing: 以后再说 remembers this version so it does not come back,
 * and dismissing by tapping outside does the same. Nothing here blocks the app, because a reminder
 * app that will not let you reach your medication until you update is a broken reminder app.
 */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("有新版药准时 ${info.version}") },
        text = {
            Text(
                "你正在使用旧版本。新版本通常修好了上一版发现的问题。" +
                    "更新是可选的：现在不更新也不影响使用，提醒会照常工作。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // The download happens in the browser: the app itself only ever asks GitHub for a
                    // version number, which keeps its network surface to one GET.
                    val target = info.apkUrl ?: info.releaseUrl
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    onDismiss()
                },
            ) { Text("前往下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("以后再说") }
        },
    )
}
