package com.meditrack.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditrack.core.theme.prefs

/**
 * 权限引导页.
 *
 * Shown on first launch and reachable from settings. The copy explains *why* each permission is
 * needed in terms of what the user loses without it ("提醒可能延迟" rather than "android.permission
 * .SCHEDULE_EXACT_ALARM"), because a permission prompt with no justification is the main reason
 * people deny them and then blame the app for missing a dose.
 *
 * The user can skip: nothing here is required to open the app, and the settings screen keeps
 * offering the same options later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("欢迎使用药准时") },
                actions = {
                    TextButton(onClick = onFinished) { Text("暂时跳过") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(84.dp),
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Alarm,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "让提醒可靠地工作",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "药准时需要下面几项权限，才能保证准时提醒。缺少权限时提醒可能延迟或收不到。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item {
                PermissionCard(
                    icon = Icons.Filled.NotificationsActive,
                    title = "通知权限",
                    body = "没有它，应用无法在到时间时提醒你，也无法在通知里提供「已服用 / 稍后提醒 / 跳过」按钮。",
                    granted = permissions.notifications == PermissionStatus.GRANTED,
                    onGrant = {
                        viewModel.permissionIntent(context, SettingsViewModel.KEY_NOTIFICATIONS)
                            ?.let { runCatching { context.startActivity(it) } }
                    },
                )
            }
            item {
                PermissionCard(
                    icon = Icons.Filled.Alarm,
                    title = "精确闹钟",
                    body = "Android 12 起系统默认只允许「大致时间」的闹钟。开启后才能在 08:00 准时提醒，而不是 08:07。",
                    granted = permissions.exactAlarm != PermissionStatus.DENIED,
                    notApplicable = permissions.exactAlarm == PermissionStatus.NOT_APPLICABLE,
                    onGrant = {
                        viewModel.permissionIntent(context, SettingsViewModel.KEY_EXACT_ALARM)
                            ?.let { runCatching { context.startActivity(it) } }
                        viewModel.refreshPermissions(context)
                    },
                )
            }
            item {
                PermissionCard(
                    icon = Icons.Filled.BatteryAlert,
                    title = "电池优化白名单",
                    body = "手机长时间待机时会限制后台应用。加入白名单后，夜间和外出时的提醒依然准时。",
                    granted = permissions.batteryOptimization != PermissionStatus.DENIED,
                    notApplicable = permissions.batteryOptimization == PermissionStatus.NOT_APPLICABLE,
                    onGrant = {
                        viewModel.permissionIntent(context, SettingsViewModel.KEY_BATTERY)
                            ?.let { runCatching { context.startActivity(it) } }
                    },
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Widgets,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        Text(
                            text = "小提示：长按桌面空白处 → 添加小组件 → 选择「药准时」，就能随时看到今天还没有完成的用药。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // The paragraph owns the rest of the line next to the icon, so a long
                            // reminder wraps instead of being clipped by the row.
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item {
                Text(
                    text = "本应用只做用药记录与提醒，不提供任何诊断、治疗或用药建议。请严格按照医生或药师的处方服药。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Button(
                    onClick = onFinished,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("开始使用") }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    body: String,
    granted: Boolean,
    onGrant: () -> Unit,
    notApplicable: Boolean = false,
) {
    Card(
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (granted) Icons.Filled.CheckCircle else icon,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (granted) "已授权，可以正常提醒。" else body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!granted && !notApplicable) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(onClick = onGrant) { Text("去授权") }
                }
            }
        }
    }
}
