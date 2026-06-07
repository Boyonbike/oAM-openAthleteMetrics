package com.athletedata.app.ui.nav

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.athletedata.app.ui.history.HistoryScreen
import com.athletedata.app.ui.overview.DailyOverviewScreen
import com.athletedata.app.ui.questions.DailyQuestionsScreen
import com.athletedata.app.ui.settings.SettingsScreen
import com.athletedata.app.ui.theme.TypographyMeta
import java.time.LocalDate

enum class Page(val label: String) {
    DASHBOARD("Dashboard"),
    QUESTIONS("Questions"),
    HISTORY("History"),
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
fun AppTopBar(
    currentPage: Page,
    subtitle: String? = null,
    onSubtitleClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit,
    onDevicesClick: () -> Unit,
    onNavigate: (Page) -> Unit,
    modifier: Modifier = Modifier,
) {
    val otherPages = Page.entries.filter { it != currentPage }

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
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    otherPages.forEach { page ->
                        TextButton(
                            onClick = { onNavigate(page) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = page.label,
                                style = TypographyMeta,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
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
            // Row 2 — date selector (tappable) or static subtitle
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
    val navController = rememberNavController()
    val context = LocalContext.current
    val today = remember { LocalDate.now().toString() }

    val onDevicesClick: () -> Unit = remember(context) {
        { Toast.makeText(context, "Device connection coming soon", Toast.LENGTH_SHORT).show() }
    }

    val onSettingsClick: () -> Unit = remember(navController) {
        { navController.navigate("settings") }
    }

    val navigateTo: (Page) -> Unit = remember(navController, today) {
        { page ->
            val route = when (page) {
                Page.DASHBOARD -> "dashboard"
                Page.QUESTIONS -> "questions"
                Page.HISTORY   -> "history/all/$today"
            }
            navController.navigate(route) {
                popUpTo("dashboard") { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "dashboard",
        modifier = modifier,
    ) {
        composable("dashboard") {
            DailyOverviewScreen(
                onNavigate = navigateTo,
                onSettingsClick = onSettingsClick,
                onDevicesClick = onDevicesClick,
                onNavigateToQuestions = { date ->
                    navController.navigate("questions?date=$date") {
                        popUpTo("dashboard") { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToMetricDetail = {},
            )
        }
        composable(
            route = "questions?date={date}",
            arguments = listOf(navArgument("date") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) {
            DailyQuestionsScreen(
                onNavigate = navigateTo,
                onSettingsClick = onSettingsClick,
                onDevicesClick = onDevicesClick,
                onNavigateToDate = { date ->
                    navController.navigate("questions?date=$date") {
                        popUpTo("dashboard") { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(
            route = "history/{metricKey}/{dateString}",
            arguments = listOf(
                navArgument("metricKey") { type = NavType.StringType },
                navArgument("dateString") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val metricKey = backStackEntry.arguments?.getString("metricKey") ?: "all"
            val dateString = backStackEntry.arguments?.getString("dateString") ?: today
            HistoryScreen(
                metricKey = metricKey,
                dateString = dateString,
                onNavigate = navigateTo,
                onSettingsClick = onSettingsClick,
                onDevicesClick = onDevicesClick,
                onNavigateToDate = { date ->
                    navController.navigate("history/$metricKey/$date") {
                        popUpTo("dashboard") { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
