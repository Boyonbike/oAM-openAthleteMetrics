package com.athletedata.openAthleteMetrics.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.ui.dailydetail.DailyDetailScreen
import com.athletedata.openAthleteMetrics.ui.devices.DevicesScreen
import com.athletedata.openAthleteMetrics.ui.history.HistoryScreen
import com.athletedata.openAthleteMetrics.ui.metric.MetricDetailScreen
import com.athletedata.openAthleteMetrics.ui.overview.DashboardScreen
import com.athletedata.openAthleteMetrics.ui.questions.QuestionsScreen
import com.athletedata.openAthleteMetrics.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

enum class Page(val label: String) {
    DASHBOARD("Dashboard"),
    QUESTIONS("Questions"),
    HISTORY("History"),
}

// Maps dashboard metric-card string keys to MetricType. "SLEEP" is a special case because the
// dashboard uses a short key while the enum value is SLEEP_STAGE. Unknown keys (e.g. "WEIGHT")
// return null and fall through to the History pager.
private fun parseMetricKey(key: String): MetricType? = when (key) {
    "SLEEP" -> MetricType.SLEEP_STAGE
    else -> MetricType.entries.find { it.name == key }
}

// ── Nav graph ─────────────────────────────────────────────────────────────────

@Composable
fun AppNavGraph(modifier: Modifier = Modifier) {
    val viewModel: NavHostViewModel = hiltViewModel()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val batteryPct by viewModel.batteryPct.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { Page.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = rememberBottomNavScrollBehavior()

    var showSettings by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showDailyDetail by remember { mutableStateOf(false) }
    var showMetricDetail by remember { mutableStateOf(false) }
    var pendingMetricType by remember { mutableStateOf<MetricType?>(null) }
    var pendingQuestionsDate by remember { mutableStateOf<String?>(null) }
    var pendingQuestionsTab by remember { mutableStateOf<String?>(null) }
    var pendingHistoryMetricKey by remember { mutableStateOf<String?>(null) }
    var pendingHistoryDateString by remember { mutableStateOf<String?>(null) }

    val currentPage = Page.entries[pagerState.currentPage]

    val navigateTo: (Page) -> Unit = remember(pagerState, coroutineScope) {
        { page -> coroutineScope.launch { pagerState.animateScrollToPage(page.ordinal) } }
    }

    val currentRoute = when {
        showSettings -> ROUTE_SETTINGS
        showDevices -> ROUTE_DEVICES
        else -> currentPage.name
    }

    CompositionLocalProvider(LocalBottomNavScrollBehavior provides scrollBehavior) {
        Box(modifier = modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
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
                            val mt = parseMetricKey(metricKey)
                            if (mt != null) {
                                pendingMetricType = mt
                                showMetricDetail = true
                            } else {
                                pendingHistoryMetricKey = metricKey
                                pendingHistoryDateString = dateStr
                                coroutineScope.launch { pagerState.animateScrollToPage(Page.HISTORY.ordinal) }
                            }
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

            if (showSettings) {
                SettingsScreen(
                    onNavigateBack = {
                        showSettings = false
                        scrollBehavior.show()
                    },
                    onNavigateToDashboard = {
                        showSettings = false
                        scrollBehavior.show()
                        coroutineScope.launch { pagerState.animateScrollToPage(Page.DASHBOARD.ordinal) }
                    },
                )
            }

            if (showDevices) {
                DevicesScreen(onNavigateBack = {
                    showDevices = false
                    scrollBehavior.show()
                })
            }

            if (showDailyDetail) {
                DailyDetailScreen(onBack = { showDailyDetail = false })
            }

            if (showMetricDetail && pendingMetricType != null) {
                MetricDetailScreen(
                    metricType = pendingMetricType!!,
                    onBack = { showMetricDetail = false },
                )
            }

            BottomNavBar(
                currentRoute = currentRoute,
                connectionState = connectionState,
                batteryPct = batteryPct,
                onDashboardTapped = {
                    showSettings = false; showDevices = false
                    scrollBehavior.show()
                    navigateTo(Page.DASHBOARD)
                },
                onQuestionsTapped = {
                    showSettings = false; showDevices = false
                    scrollBehavior.show()
                    navigateTo(Page.QUESTIONS)
                },
                onHistoryTapped = {
                    showSettings = false; showDevices = false
                    scrollBehavior.show()
                    navigateTo(Page.HISTORY)
                },
                onSettingsTapped = { showDevices = false; showSettings = true },
                onDevicesTapped = { showSettings = false; showDevices = true },
                onDevicesLongPressed = viewModel::onDevicesLongPressed,
                scrollBehavior = scrollBehavior,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
