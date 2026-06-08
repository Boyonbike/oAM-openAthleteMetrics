package com.athletedata.openAthleteMetrics.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.athletedata.openAthleteMetrics.ui.components.PillSelector
import com.athletedata.openAthleteMetrics.ui.dailydetail.DailyDetailScreen
import com.athletedata.openAthleteMetrics.ui.devices.DevicesScreen
import com.athletedata.openAthleteMetrics.ui.history.HistoryScreen
import com.athletedata.openAthleteMetrics.ui.overview.DashboardScreen
import com.athletedata.openAthleteMetrics.ui.questions.QuestionsScreen
import com.athletedata.openAthleteMetrics.ui.settings.SettingsScreen
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import kotlinx.coroutines.launch

enum class Page(val label: String) {
    DASHBOARD("Dashboard"),
    QUESTIONS("Questions"),
    HISTORY("History"),
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
fun AppTopBar(
    currentPage: Page,
    continuousIndex: Float,
    subtitle: String? = null,
    onSubtitleClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit,
    onDevicesClick: () -> Unit,
    onNavigate: (Page) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        ) {
            // Row 1 — settings | nav links to the other two pages | devices
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
            ) {
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                PillSelector(
                    tabs = Page.entries.map { it.label },
                    selectedIndex = currentPage.ordinal,
                    continuousIndex = continuousIndex,
                    onSelect = { onNavigate(Page.entries[it]) },
                    modifier = Modifier.align(Alignment.Center),
                )
                IconButton(
                    onClick = onDevicesClick,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Devices,
                        contentDescription = "Devices",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            // Row 2 — optional subtitle / date selector
            if (subtitle != null) {
                if (onSubtitleClick != null) {
                    TextButton(
                        onClick = onSubtitleClick,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = subtitle,
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Text(
                        text = subtitle,
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

// ── Nav graph ─────────────────────────────────────────────────────────────────

@Composable
fun AppNavGraph(modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState(pageCount = { Page.entries.size })
    val coroutineScope = rememberCoroutineScope()

    var showSettings by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showDailyDetail by remember { mutableStateOf(false) }
    var pendingQuestionsDate by remember { mutableStateOf<String?>(null) }
    var pendingQuestionsTab by remember { mutableStateOf<String?>(null) }
    var pendingHistoryMetricKey by remember { mutableStateOf<String?>(null) }
    var pendingHistoryDateString by remember { mutableStateOf<String?>(null) }

    val currentPage = Page.entries[pagerState.currentPage]
    val continuousIndex = pagerState.currentPage + pagerState.currentPageOffsetFraction

    val navigateTo: (Page) -> Unit = remember(pagerState, coroutineScope) {
        { page -> coroutineScope.launch { pagerState.animateScrollToPage(page.ordinal) } }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                currentPage = currentPage,
                continuousIndex = continuousIndex,
                onNavigate = navigateTo,
                onSettingsClick = { showSettings = true },
                onDevicesClick = { showDevices = true },
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { pageIndex ->
                when (pageIndex) {
                    0 -> DashboardScreen(
                        onNavigateToQuestions = { date ->
                            pendingQuestionsDate = date.toString()
                            coroutineScope.launch { pagerState.animateScrollToPage(Page.QUESTIONS.ordinal) }
                        },
                        onNavigateToHabitsTab = { date ->
                            pendingQuestionsDate = date.toString()
                            pendingQuestionsTab = "HABITS"
                            coroutineScope.launch { pagerState.animateScrollToPage(Page.QUESTIONS.ordinal) }
                        },
                        onNavigateToHistory = { metricKey, dateStr ->
                            pendingHistoryMetricKey = metricKey
                            pendingHistoryDateString = dateStr
                            coroutineScope.launch { pagerState.animateScrollToPage(Page.HISTORY.ordinal) }
                        },
                        onNavigateToHistoryDirect = {
                            coroutineScope.launch { pagerState.animateScrollToPage(Page.HISTORY.ordinal) }
                        },
                        onNavigateToDailyDetail = { showDailyDetail = true },
                    )
                    1 -> QuestionsScreen(
                        initialDate = pendingQuestionsDate,
                        onInitialDateConsumed = { pendingQuestionsDate = null },
                        initialTab = pendingQuestionsTab,
                        onInitialTabConsumed = { pendingQuestionsTab = null },
                    )
                    2 -> HistoryScreen(
                        initialMetricKey = pendingHistoryMetricKey,
                        initialDateString = pendingHistoryDateString,
                        onInitialTargetConsumed = {
                            pendingHistoryMetricKey = null
                            pendingHistoryDateString = null
                        },
                    )
                }
            }
        }

        if (showSettings) {
            SettingsScreen(
                onNavigateBack = { showSettings = false },
                onNavigateToDashboard = {
                    showSettings = false
                    coroutineScope.launch { pagerState.animateScrollToPage(Page.DASHBOARD.ordinal) }
                },
            )
        }

        if (showDevices) {
            DevicesScreen(onNavigateBack = { showDevices = false })
        }

        if (showDailyDetail) {
            DailyDetailScreen(onBack = { showDailyDetail = false })
        }
    }
}
