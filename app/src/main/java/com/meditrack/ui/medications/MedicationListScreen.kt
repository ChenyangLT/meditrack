package com.meditrack.ui.medications

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditrack.core.util.MedicationVisuals
import com.meditrack.data.repository.MedicationReviewSnapshot
import com.meditrack.data.repository.MedicationUiModel
import com.meditrack.ui.components.StatusDot
import com.meditrack.core.theme.prefs

/**
 * 药品 - the medication inventory.
 *
 * The list answers three questions at a glance: what am I taking, when do I take it, and how much
 * is left. Everything else (strength, timing, note) is secondary text.
 *
 * Disabling a medication is offered as a switch rather than requiring a delete: a paused course of
 * treatment must keep its history and be resumable in one tap.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MedicationListScreen(
    onAddMedication: () -> Unit,
    onOpenMedication: (Long) -> Unit,
    viewModel: MedicationListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val reviewStates by viewModel.reviewStates.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<MedicationUiModel?>(null) }

    // The count advances when a dose is recorded elsewhere, which does not touch the medication row -
    // so re-read the review progress every time this screen appears rather than trusting the snapshot
    // that was true when the ViewModel was first created.
    LaunchedEffect(Unit) { viewModel.refreshReviews() }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${target.name}」？") },
            text = { Text("该药品的历史服药记录也会一起删除，此操作不可撤销。如果只是暂时停药，建议改为「停用」。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target.id)
                        pendingDelete = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("我的药品") },
                actions = {
                    Text(
                        text = "${state.activeCount} 种启用中",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddMedication,
                modifier = Modifier.testTag(com.meditrack.ui.MediTrackTestTags.ADD_MEDICATION_FAB),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加药品") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("搜索药品名或规格") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            // FlowRow, not Row: three filter chips at a large font scale do not fit one line, and
            // a Row hands the last chip whatever is left (which can be nothing).
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MedicationFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = {
                            Text(
                                when (filter) {
                                    MedicationFilter.ALL -> "全部"
                                    MedicationFilter.ACTIVE -> "启用中"
                                    MedicationFilter.INACTIVE -> "已停用"
                                }
                            )
                        },
                    )
                }
            }

            if (state.isEmpty) {
                EmptyMedicationState(onAddMedication = onAddMedication)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.medications, key = { it.id }) { med ->
                        MedicationRow(
                            model = med,
                            review = reviewStates[med.id],
                            onOpen = { onOpenMedication(med.id) },
                            onToggleActive = { viewModel.setActive(med.id, it) },
                            onDelete = { pendingDelete = med },
                        )
                    }
                }
            }
        }
    }
}

/** One medication row. */
@Composable
private fun MedicationRow(
    model: MedicationUiModel,
    review: MedicationReviewSnapshot?,
    onOpen: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val identity = MedicationVisuals.color(model.colorTag)
    val inactive = !model.isActive

    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (inactive) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MedicationVisuals.container(model.colorTag), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MedicationVisuals.icon(model.icon, filled = !inactive),
                    contentDescription = null,
                    tint = if (inactive) MaterialTheme.colorScheme.onSurfaceVariant else identity,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        // Deliberately not single-line: a drug name is the one string this app must
                        // never shorten, and the ellipsis hid the distinguishing tail of long ones.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (inactive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        StatusDot(MaterialTheme.colorScheme.outline, size = 6.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "已停用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = model.timesLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.padding(top = 4.dp))

                // A wrapping row: two AssistChips ("100mg/片" + "每天 3 次") overflow a narrow
                // card once the label metrics grow.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (model.subtitle.isNotBlank()) {
                        MiniTag(model.subtitle)
                    }
                    if (model.repeatLabel.isNotBlank() && !MaterialTheme.prefs.simplifiedMode) {
                        MiniTag(model.repeatLabel)
                    }
                }

                if (model.stockLabel.isNotBlank()) {
                    Spacer(modifier = Modifier.padding(top = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Inventory2,
                            contentDescription = null,
                            tint = if (model.isStockLow) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = model.stockLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (model.isStockLow) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            // weight, not wrap-content: the trailing warning icon is a fixed-size
                            // child measured after this text, so an unweighted text could starve it
                            // down to zero width at a large font scale.
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (model.isStockLow) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "库存不足",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                // The «复查» line. It is a *state* rather than a control, so it is drawn as text and
                // not as a chip: tapping it would do nothing, and the row's tap already opens the
                // medication where the countdown can be changed. A reached threshold is the one line
                // in this card that asks for action outside the app, hence the error colour.
                val reviewDue = review?.isDue == true
                val reviewLine = when {
                    reviewDue -> "该复查了"
                    else -> review?.subtitle
                }
                if (reviewLine != null) {
                    Spacer(modifier = Modifier.padding(top = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.EventAvailable,
                            contentDescription = null,
                            tint = if (reviewDue) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = reviewLine,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (reviewDue) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (reviewDue) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            // Weighted for the same reason as the stock line: a sibling icon is
                            // measured after this text and an unweighted text can starve it.
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(
                    checked = model.isActive,
                    onCheckedChange = onToggleActive,
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除 ${model.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Small neutral tag for secondary metadata. */
@Composable
private fun MiniTag(text: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = null,
    )
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun EmptyMedicationState(onAddMedication: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = MedicationVisuals.icon(
                com.meditrack.data.local.entity.MedicationIcon.BOTTLE,
            ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.padding(top = 16.dp))
        Text("还没有添加药品", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Text(
            text = "添加第一个药品，然后设置每天的服药时间。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // NOTE: the call to action is the Scaffold's single FloatingActionButton. This composable
        // previously rendered its own ExtendedFloatingActionButton too, so the empty screen showed
        // two identical "添加药品" buttons. The parameter is kept so both empty states share one
        // signature (the today screen's variant uses it the same way).
        Spacer(modifier = Modifier.padding(top = 20.dp))
        Text(
            text = "点击右下角的「添加药品」开始",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
