package com.meditrack.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 历史 - the calendar, the statistics and the day detail.
 *
 * Reading order is deliberate: the calendar answers "which days went wrong?" at a glance, the
 * statistics answer "how am I doing overall?", and the day detail answers "what exactly happened on
 * the 12th?". The legend is always visible because the colours carry meaning and a user must be able
 * to decode them without guessing.
 *
 * The page uses a scrolled Column rather than a LazyColumn: it holds three sections, so lazy item
 * virtualisation would add nothing, and composing them through SubcomposeLayout during the first
 * measure previously corrupted the composition outright (see HistoryCards.kt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenMedication: (Long) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("历史与统计") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Reading order: calendar -> statistics -> the selected day's detail.
            CalendarCard(state, viewModel)
            StatisticsCard(state, viewModel)
            DayDetailCard(state, viewModel, onOpenMedication)
        }
    }
}
