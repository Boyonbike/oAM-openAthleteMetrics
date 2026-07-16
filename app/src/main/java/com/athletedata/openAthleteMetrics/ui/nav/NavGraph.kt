package com.athletedata.openAthleteMetrics.ui.nav

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import com.athletedata.openAthleteMetrics.glance.WidgetDeepLink
import com.athletedata.openAthleteMetrics.ui.dailydetail.DailyDetailScreen
import com.athletedata.openAthleteMetrics.ui.dailydetail.DailyDetailSection
import com.athletedata.openAthleteMetrics.ui.devices.DevicesScreen
import com.athletedata.openAthleteMetrics.ui.history.HistoryScreen
import com.athletedata.openAthleteMetrics.ui.metric.MetricDetailScreen
import com.athletedata.openAthleteMetrics.ui.overview.DashboardNavigationEvent
import com.athletedata.openAthleteMetrics.ui.overview.DashboardScreen
import com.athletedata.openAthleteMetrics.ui.questions.QuestionsScreen
import com.athletedata.openAthleteMetrics.ui.settings.SettingsScreen
import java.time.LocalDate

enum class Page(val label: String) {
    DASHBOARD("Dashboard"),
    DAILY_DETAIL("Daily Detail"),
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
fun AppNavGraph(
    widgetIntent: Intent? = null,
    onWidgetIntentConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: NavHostViewModel = hiltViewModel()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val batteryPct by viewModel.batteryPct.collectAsStateWithLifecycle()

    val scrollBehavior = rememberBottomNavScrollBehavior()

    var showSettings by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showMetricDetail by remember { mutableStateOf(false) }
    var pendingMetricType by remember { mutableStateOf<MetricType?>(null) }
    var pendingDailyDetailDate by remember { mutableStateOf<String?>(null) }
    var pendingDailyDetailSection by remember { mutableStateOf<DailyDetailSection?>(null) }
    var pendingDailyDetailMetric by remember { mutableStateOf<String?>(null) }
    var pendingQuestionsDate by remember { mutableStateOf<String?>(null) }
    var pendingQuestionsTab by remember { mutableStateOf<String?>(null) }
    var pendingHistoryMetricKey by remember { mutableStateOf<String?>(null) }
    var pendingHistoryDateString by remember { mutableStateOf<String?>(null) }

    var currentPage by remember { mutableStateOf(Page.DASHBOARD) }
    val backStack = remember { mutableStateListOf<Page>(Page.DASHBOARD) }

    val navigateTo: (Page) -> Unit = { page ->
        if (backStack.lastOrNull() != page) {
            backStack.add(page)
            currentPage = page
        }
    }

    // Hoisted so both DashboardScreen's callback params and the widget-deep-link effect
    // below can reuse the exact same "event -> nav state" wiring.
    val onOpenDailyDetailAtSection: (date: LocalDate, section: DailyDetailSection, metricKey: String?) -> Unit =
        { date, section, metricKey ->
            pendingDailyDetailDate = date.toString()
            pendingDailyDetailSection = section
            pendingDailyDetailMetric = metricKey
            navigateTo(Page.DAILY_DETAIL)
        }
    val onNavigateToQuestions: (LocalDate) -> Unit = { date ->
        pendingQuestionsDate = date.toString()
        navigateTo(Page.QUESTIONS)
    }
    val onNavigateToHabitsTab: (LocalDate) -> Unit = { date ->
        pendingQuestionsDate = date.toString()
        pendingQuestionsTab = "HABITS"
        navigateTo(Page.QUESTIONS)
    }

    // Home-screen widget tap-to-open: resolves via the same WidgetTapDestinationResolver
    // the in-app dashboard tap uses, then dispatches through the same nav-state wiring
    // above. OpenWeightSheet has no wired callback at this level (it's handled locally
    // inside DashboardScreen's own collector) — landing on Dashboard without
    // auto-opening the sheet is a deliberate simplification for the cold-launch case.
    LaunchedEffect(widgetIntent) {
        val intent = widgetIntent ?: return@LaunchedEffect
        val templateId = intent.getStringExtra(WidgetDeepLink.EXTRA_TEMPLATE_ID)
            ?.let { name -> WidgetTemplateId.entries.find { it.name == name } } ?: return@LaunchedEffect
        val date = intent.getStringExtra(WidgetDeepLink.EXTRA_DATE)?.let(LocalDate::parse) ?: LocalDate.now()
        when (val event = viewModel.resolveWidgetTap(templateId, date)) {
            is DashboardNavigationEvent.OpenDailyDetail -> onOpenDailyDetailAtSection(event.date, event.section, event.metricKey)
            is DashboardNavigationEvent.OpenQuestions -> onNavigateToQuestions(event.date)
            is DashboardNavigationEvent.OpenHabitsTab -> onNavigateToHabitsTab(event.date)
            DashboardNavigationEvent.OpenWeightSheet -> navigateTo(Page.DASHBOARD)
        }
        onWidgetIntentConsumed()
    }

    val currentRoute = when {
        showSettings -> ROUTE_SETTINGS
        showDevices -> ROUTE_DEVICES
        else -> currentPage.name
    }

    BackHandler(enabled = backStack.size > 1 || showSettings || showDevices || showMetricDetail) {
        when {
            showMetricDetail -> showMetricDetail = false
            showSettings -> { showSettings = false; scrollBehavior.show() }
            showDevices -> { showDevices = false; scrollBehavior.show() }
            backStack.size > 1 -> {
                backStack.removeAt(backStack.lastIndex)
                currentPage = backStack.last()
            }
        }
    }

    CompositionLocalProvider(LocalBottomNavScrollBehavior provides scrollBehavior) {
        Box(modifier = modifier.fillMaxSize()) {
            // All pages stay composed simultaneously so scroll state and remember values
            // survive navigation. Alpha=0 hides the page and disables touch hit-testing.
            Page.entries.forEach { page ->
                val isActive = currentPage == page && !showSettings && !showDevices
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isActive) 1f else 0f)
                        .zIndex(if (isActive) 1f else 0f),
                ) {
                    when (page) {
                        Page.DASHBOARD -> DashboardScreen(
                            onNavigateToQuestions = onNavigateToQuestions,
                            onNavigateToHabitsTab = onNavigateToHabitsTab,
                            onOpenDailyDetailAtSection = onOpenDailyDetailAtSection,
                        )
                        Page.DAILY_DETAIL -> DailyDetailScreen(
                            initialDate = pendingDailyDetailDate,
                            onInitialDateConsumed = { pendingDailyDetailDate = null },
                            initialSection = pendingDailyDetailSection,
                            initialMetricKey = pendingDailyDetailMetric,
                            onInitialSectionConsumed = {
                                pendingDailyDetailSection = null
                                pendingDailyDetailMetric = null
                            },
                        )
                        Page.QUESTIONS -> QuestionsScreen(
                            initialDate = pendingQuestionsDate,
                            onInitialDateConsumed = { pendingQuestionsDate = null },
                            initialTab = pendingQuestionsTab,
                            onInitialTabConsumed = { pendingQuestionsTab = null },
                        )
                        Page.HISTORY -> HistoryScreen(
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
                    onNavigateBack = {
                        showSettings = false
                        scrollBehavior.show()
                    },
                    onNavigateToDashboard = {
                        showSettings = false
                        scrollBehavior.show()
                        navigateTo(Page.DASHBOARD)
                    },
                )
            }

            if (showDevices) {
                DevicesScreen(onNavigateBack = {
                    showDevices = false
                    scrollBehavior.show()
                })
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
                onDailyDetailTapped = {
                    showSettings = false; showDevices = false
                    scrollBehavior.show()
                    navigateTo(Page.DAILY_DETAIL)
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
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(2f),
            )
        }
    }
}
