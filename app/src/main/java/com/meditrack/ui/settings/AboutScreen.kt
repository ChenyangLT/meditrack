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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meditrack.BuildConfig
import com.meditrack.core.theme.prefs

/**
 * 关于与免责声明.
 *
 * The disclaimer is the point of this screen, so it is written to be read rather than skimmed: a
 * short, plainly-worded list of what the app does *not* do, followed by the concrete limits of the
 * reminder mechanism. An app that a person relies on for medication must not overstate its
 * reliability.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于与免责声明") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "药准时",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "MediTrack · 版本 $versionName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                AboutCard(
                    icon = Icons.Filled.Warning,
                    title = "免责声明",
                    accent = MaterialTheme.colorScheme.error,
                ) {
                    Text(
                        text = "本应用只做用药记录与提醒，不提供任何诊断、治疗或用药建议。",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "请严格按照医生或药师的处方服药。任何剂量调整、停药或换药，请先咨询专业医疗人员。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "提醒功能依赖系统的闹钟与通知服务。在省电模式、系统后台限制、强制停止应用、或权限被收回等极端情况下，提醒可能延迟或失效。请不要把本应用作为唯一的用药保障手段。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                AboutCard(icon = Icons.Filled.Lock, title = "数据与隐私") {
                    BulletLine("所有药品与服药记录都保存在这台手机本地，应用没有申请网络权限。")
                    BulletLine("不会上传任何数据，也没有账号体系。")
                    BulletLine("导出备份时会写入应用私有目录，只有你自己通过分享功能才能送出。")
                    BulletLine("卸载应用会删除全部本地数据，建议定期导出备份。")
                }
            }

            item {
                AboutCard(icon = Icons.Filled.MedicalServices, title = "功能范围") {
                    BulletLine("自定义药品、剂型、规格、每次剂量与多个服药时间。")
                    BulletLine("支持每天、隔天、每周指定、每 N 天、吃 X 天停 Y 天等重复规则。")
                    BulletLine("用加号 / 减号记录实际服药数量，自动判断已服、部分服用与未服药。")
                    BulletLine("桌面小组件优先显示还没吃和马上要吃的药。")
                    BulletLine("历史日历与依从率统计，可导出 CSV 交给医生参考。")
                }
            }

            item {
                AboutCard(icon = Icons.Filled.PhoneAndroid, title = "兼容性") {
                    BulletLine("支持 Android 8.0 及以上，已针对 Android 14 适配。")
                    BulletLine("支持浅色 / 深色主题、动态取色与大字体。")
                    BulletLine("提供高对比度与简化模式的适老选项。")
                }
            }

            item {
                Text(
                    text = "感谢你把每天最重要的几件小事交给药准时。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AboutCard(
    icon: ImageVector,
    title: String,
    accent: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/** A single bullet line; kept trivial so the disclaimer stays scannable. */
@Composable
private fun BulletLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
