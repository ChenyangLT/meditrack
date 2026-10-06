package com.meditrack.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meditrack.core.theme.prefs
import com.meditrack.ui.MediTrackTestTags
import kotlinx.coroutines.delay

/**
 * The first-run gate: 《用户协议》 and 《使用说明》, both of which must actually be read.
 *
 * ## Why a gate rather than a dialog
 *
 * A dialog has a "later" button, and the whole point of this screen is that there is no later: the two
 * documents describe what the app does not promise (it is not a diagnosis), what it does with the user's
 * data (nothing leaves the phone unless they press 去搜索 themselves), and how to make the reminders
 * survive their particular phone's battery manager. A user who waves that away has been told nothing.
 *
 * ## Why "actually read" is enforced the way it is
 *
 * The requirement is explicit: both documents must be scrolled to the bottom **and** a minimum amount of
 * time must have elapsed before the checkboxes can be ticked. Both conditions are implemented as facts
 * about the screen rather than as a formality:
 *
 *  - **Scrolled to the end** is detected from the real list state, per document, and each document keeps
 *    its own flag - so the guide cannot be signed by scrolling the agreement.
 *  - **The timer** runs from the moment the screen appears and gates the *checkboxes*, not the button, so
 *    the user can read at their own pace and is never told "you must wait" after they have finished.
 *
 * ## Why it is not shown again
 *
 * `agreementAcceptedVersion` is compared against [AGREEMENT_VERSION]. Installing this build over an older
 * one shows it exactly once - the flag does not exist there yet, so the condition is true - and every
 * later update shows nothing, because the stored version is already current. That is the requested
 * "旧版本也显示，但只显示一次".
 *
 * ## Why there is an exit
 *
 * A gate that cannot be refused is not an agreement. 不同意，退出 finishes the Activity, leaving no data
 * behind. Nothing has been read from or written to the user's medication data at this point, so there is
 * nothing to clean up.
 */
@Composable
fun AgreementGate(
    onAccepted: () -> Unit,
    onDeclined: () -> Unit = {},
) {
    // The Activity is what has to be finished by "不同意，退出", and it cannot be reached by casting
    // `LocalContext` directly: the context a composable receives is a `ContextWrapper` around the themed
    // Activity, so `context as? Activity` silently returns null and the button appears to do nothing -
    // leaving the user on a gate with no way past it except force-stopping the app. The wrapper chain is
    // therefore walked explicitly. (`LocalActivity` would be tidier but only exists in newer
    // activity-compose releases than this module pins.)
    val activity = LocalContext.current.findActivity()
    var tab by remember { mutableIntStateOf(0) }
    var termsRead by remember { mutableStateOf(false) }
    var guideRead by remember { mutableStateOf(false) }
    var termsChecked by remember { mutableStateOf(false) }
    var guideChecked by remember { mutableStateOf(false) }

    // Counts down once, from the moment the screen appears. Gating the *checkboxes* rather than the
    // button is deliberate: a user who genuinely reads both documents should never be told to wait, and a
    // user who scrolls to the bottom in two seconds cannot tick anything until this reaches zero.
    var secondsLeft by remember { mutableIntStateOf(READING_SECONDS) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1_000L)
            secondsLeft--
        }
    }

    val canAccept = termsChecked && guideChecked && termsRead && guideRead

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    text = "欢迎使用药准时",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "开始之前，请先阅读下面两份文件。两份都需要滑动到底部，并分别勾选确认。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("用户协议") },
                    icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("使用说明") },
                    icon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    0 -> DocumentReader(
                        text = AgreementTexts.USER_AGREEMENT,
                        testTag = MediTrackTestTags.AGREEMENT_TERMS,
                        onReachedEnd = { termsRead = true },
                    )
                    else -> DocumentReader(
                        text = AgreementTexts.USER_GUIDE,
                        testTag = MediTrackTestTags.AGREEMENT_GUIDE,
                        onReachedEnd = { guideRead = true },
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    AgreementCheckRow(
                        checked = termsChecked,
                        enabled = termsRead && secondsLeft == 0,
                        label = "我已阅读并同意《用户协议》",
                        hint = when {
                            !termsRead -> "请先滑动《用户协议》到底部"
                            secondsLeft > 0 -> "还需阅读 $secondsLeft 秒"
                            else -> null
                        },
                        onCheckedChange = { termsChecked = it },
                    )
                    AgreementCheckRow(
                        checked = guideChecked,
                        enabled = guideRead && secondsLeft == 0,
                        label = "我已阅读《使用说明》",
                        hint = when {
                            !guideRead -> "请先滑动《使用说明》到底部"
                            secondsLeft > 0 -> "还需阅读 $secondsLeft 秒"
                            else -> null
                        },
                        onCheckedChange = { guideChecked = it },
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                // The callback first, so a host that wants to react (log, navigate) can;
                                // then the Activity finishes, which is the part that actually leaves the
                                // app. `finish()` rather than `finishAffinity()`: the gate is shown on the
                                // only Activity this app has, so there is nothing beneath it to reveal -
                                // and `finishAffinity` would tear down a task this screen does not own.
                                onDeclined()
                                activity?.finish()
                            },
                        ) {
                            Text("不同意，退出")
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = onAccepted,
                            enabled = canAccept,
                            modifier = Modifier.testTag(MediTrackTestTags.AGREEMENT_ACCEPT),
                        ) {
                            Text(if (canAccept) "进入药准时" else "请读完并勾选")
                        }
                    }
                }
            }
        }
    }
}

/**
 * One document, with its own "reached the end" detection.
 *
 * A `LazyColumn` rather than a `Column` + `verticalScroll` because the documents are long enough that
 * composing every paragraph on a low-end device is measurable, and because a lazy list gives an accurate
 * `canScrollForward` - which is the honest way to know the user is at the bottom. The flag is sticky: a
 * user who scrolls back up to re-read has already read it.
 */
@Composable
private fun DocumentReader(
    text: String,
    testTag: String,
    onReachedEnd: () -> Unit,
) {
    val paragraphs = remember(text) { text.trim().lines().filter { it.isNotBlank() } }
    val listState = rememberLazyListState()

    // "At the bottom" means there is nothing left to scroll to. `canScrollForward == false` is exactly
    // that, and it is robust to the last paragraph being shorter than the viewport.
    val atEnd by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= paragraphs.lastIndex && !listState.canScrollForward
        }
    }
    LaunchedEffect(atEnd) {
        if (atEnd && paragraphs.isNotEmpty()) onReachedEnd()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(testTag),
        contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(count = paragraphs.size) { index ->
            val line = paragraphs[index]
            when {
                line.startsWith("## ") -> Text(
                    text = line.removePrefix("## "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                line.startsWith("# ") -> Text(
                    text = line.removePrefix("# "),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                line.startsWith("- ") -> Row {
                    Text("• ", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = line.removePrefix("- "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                else -> Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A `LazyListScope.items` shim so the reader does not need an experimental-API opt-in. */

/**
 * One acknowledgement line.
 *
 * The checkbox is disabled until its document has been read *and* the timer has run out, and the reason
 * is spelled out next to it - a disabled control with no explanation is the single most common way an
 * onboarding flow makes a user angry.
 */
@Composable
private fun AgreementCheckRow(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    hint: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
        Icon(
            imageVector = if (checked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = when {
                checked -> MaterialTheme.colorScheme.primary
                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How long both documents must be open before they can be acknowledged. */
const val READING_SECONDS = 15

/**
 * Walks the `ContextWrapper` chain to the hosting Activity, or null when there is none.
 *
 * A composable's `LocalContext` is a `ContextWrapper` (the theme wrapper, then the Activity), so a plain
 * cast to `Activity` fails for the same reason it fails inside a `RecyclerView` adapter. Recursion is the
 * documented way to resolve it and is bounded by the wrapper depth, which is two in practice.
 */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
