package com.meditrack.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditrack.data.update.UpdateInfo

/**
 * "有新版药准时 2.1.0" - offered, never imposed.
 *
 * ## What changed in 2.1.0, and why
 *
 * The v2.0 dialog offered one button that opened a browser. That fails exactly the user this dialog exists
 * for: on the networks where GitHub is unreachable the browser shows a connection error, and even when it
 * works the package lands in Downloads and the user has to find and tap it. So the dialog now downloads
 * in-app, verifies the package, and hands it to the system installer - and when GitHub itself is the
 * problem, a dropdown offers other hosts.
 *
 * ## Why the release notes are shown
 *
 * Because "what changed" is the question a user has before spending data and time on an update, and the
 * answer is already published. The body is rendered as plain text with its Markdown left intact rather than
 * parsed: a Markdown renderer is a lot of surface for a dialog, and headings and `**` are perfectly
 * readable.
 *
 * ## Why the dialog is not cancellable mid-install
 *
 * Dismissing is fine at every step, and the two ways out are neither punishing - 以后再说 remembers the
 * version so it is not offered again. Only the *verifying* step hides the buttons for a moment, because a
 * half-cancelled hand-off to the installer is how a user ends up staring at a file chooser.
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    viewModel: UpdateViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mirrors by viewModel.mirrors.collectAsStateWithLifecycle()
    val selectedMirrorId by viewModel.selectedMirrorId.collectAsStateWithLifecycle()
    var dropdownOpen by remember { mutableStateOf(false) }

    val selectedMirror = mirrors.firstOrNull { it.id == selectedMirrorId } ?: mirrors.firstOrNull()
    val busy = state is UpdateFlowState.Downloading || state is UpdateFlowState.Verifying

    AlertDialog(
        onDismissRequest = { if (!busy) viewModel.dismiss(info) },
        title = { Text("有新版药准时 ${info.version}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // The dialog must stay usable on a small screen with the accessibility font scale, so
                    // the notes scroll inside a bounded box instead of pushing the buttons off-screen.
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                UpdateBody(info = info, state = state)

                if (state !is UpdateFlowState.Downloading && state !is UpdateFlowState.Verifying) {
                    if (mirrors.size > 1) {
                        MirrorPicker(
                            mirrors = mirrors,
                            selectedLabel = selectedMirror?.label ?: "默认",
                            open = dropdownOpen,
                            onToggle = { dropdownOpen = !dropdownOpen },
                            onSelect = { id ->
                                dropdownOpen = false
                                viewModel.selectMirror(id)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (val current = state) {
                is UpdateFlowState.Downloading -> TextButton(onClick = { viewModel.cancelDownload() }) {
                    Text("取消下载")
                }

                is UpdateFlowState.Verifying -> Unit

                is UpdateFlowState.NeedsInstallPermission -> TextButton(
                    onClick = { viewModel.openInstallPermissionSettings() },
                ) { Text("去授权") }

                is UpdateFlowState.Failed -> TextButton(onClick = { viewModel.startDownload() }) {
                    Text("重试")
                }

                is UpdateFlowState.Idle -> TextButton(onClick = { viewModel.startDownload() }) {
                    Text(if (mirrors.isEmpty()) "前往发布页" else "下载并安装")
                }
            }
        },
        dismissButton = {
            if (!busy) {
                TextButton(onClick = { viewModel.dismiss(info) }) { Text("以后再说") }
            }
        },
    )
}

/** The dialog's body: notes, progress, or the reason it did not work. */
@Composable
private fun UpdateBody(info: UpdateInfo, state: UpdateFlowState) {
    when (state) {
        is UpdateFlowState.Downloading -> {
            Text(
                text = if (state.percent >= 0) "正在下载 ${state.percent}%" else "正在下载…",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (state.percent >= 0) {
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = if (state.totalBytes > 0L) {
                    "${formatBytes(state.bytesRead)} / ${formatBytes(state.totalBytes)}"
                } else {
                    formatBytes(state.bytesRead)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is UpdateFlowState.Verifying -> {
            Text("正在校验安装包…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is UpdateFlowState.NeedsInstallPermission -> {
            Text(
                text = "安装前需要先允许「药准时」安装应用。这是系统的安全限制，应用无法自己设置。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "授权后回到这里，再点一次「下载并安装」即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is UpdateFlowState.Failed -> {
            Text(
                text = state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "可以换一个下载方式重试；如果都不行，请到发布页手动下载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is UpdateFlowState.Idle -> {
            Text(
                text = "更新是可选的，现在不更新也不影响使用，提醒会照常工作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (info.body.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "本次更新内容",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                // Verbatim, Markdown untouched. See the class note for why it is not rendered.
                Text(
                    text = info.body.trim(),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = "这个版本没有提供更新说明。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The download-source dropdown.
 *
 * A dropdown rather than a row of chips because the list is variable-length (the manifest decides how many
 * mirrors exist) and because a chip row of six identical-looking hosts is harder to read than one line that
 * says which is selected. The selected label is shown even when the menu is closed, so the user can always
 * see which host they are about to download from.
 */
@Composable
private fun MirrorPicker(
    mirrors: List<com.meditrack.data.update.DownloadMirror>,
    selectedLabel: String,
    open: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(
            text = "下载方式",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = selectedLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择下载方式")
            }
            DropdownMenu(expanded = open, onDismissRequest = onToggle) {
                mirrors.forEach { mirror ->
                    DropdownMenuItem(
                        text = { Text(mirror.label, style = MaterialTheme.typography.bodyMedium) },
                        onClick = { onSelect(mirror.id) },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "除 GitHub 官方地址外，其余为第三方加速服务。下载后会校验安装包签名，" +
                "签名与当前版本不一致时不会安装。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Human file sizes; the dialog should never say "4262626 字节". */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
