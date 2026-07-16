package com.athletedata.openAthleteMetrics.ui.nav

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.athletedata.openAthleteMetrics.ble.BleConnectionState
import kotlin.math.roundToInt

// ── Scroll behavior ───────────────────────────────────────────────────────────

class BottomNavScrollBehavior {
    var isVisible by mutableStateOf(true)
        private set

    fun onScroll(delta: Float) {
        if (delta < -2f) isVisible = false
        if (delta > 2f) isVisible = true
    }

    fun onFling(velocity: Float) {
        if (velocity < 0) isVisible = false
        if (velocity > 0) isVisible = true
    }

    fun show() {
        isVisible = true
    }
}

@Composable
fun rememberBottomNavScrollBehavior(): BottomNavScrollBehavior =
    remember { BottomNavScrollBehavior() }

val LocalBottomNavScrollBehavior = compositionLocalOf<BottomNavScrollBehavior> {
    error("No BottomNavScrollBehavior provided")
}

@Composable
fun rememberBottomNavNestedScrollConnection(
    behavior: BottomNavScrollBehavior,
): NestedScrollConnection = remember(behavior) {
    object : NestedScrollConnection {
        override fun onPreScroll(
            available: androidx.compose.ui.geometry.Offset,
            source: NestedScrollSource,
        ): androidx.compose.ui.geometry.Offset {
            behavior.onScroll(available.y)
            return androidx.compose.ui.geometry.Offset.Zero
        }

        override suspend fun onPostFling(
            consumed: Velocity,
            available: Velocity,
        ): Velocity {
            behavior.onFling(available.y)
            return super.onPostFling(consumed, available)
        }
    }
}

// ── Route constants ───────────────────────────────────────────────────────────

const val ROUTE_SETTINGS = "SETTINGS"
const val ROUTE_DEVICES = "DEVICES"

// ── Bottom nav bar ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomNavBar(
    currentRoute: String,
    connectionState: BleConnectionState,
    batteryPct: Int?,
    onDashboardTapped: () -> Unit,
    onDailyDetailTapped: () -> Unit,
    onQuestionsTapped: () -> Unit,
    onHistoryTapped: () -> Unit,
    onSettingsTapped: () -> Unit,
    onDevicesTapped: () -> Unit,
    onDevicesLongPressed: () -> Unit,
    scrollBehavior: BottomNavScrollBehavior,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val darkTheme = isSystemInDarkTheme()

    val screenWidthDp = configuration.screenWidthDp.dp
    val pillWidth = screenWidthDp - 48.dp
    val barHeightDp = 64.dp

    // Slide-off animation
    val navBarBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val hiddenOffsetPx = with(density) {
        navBarBottomPx + 16.dp.toPx() + barHeightDp.toPx()
    }

    val isSyncingNow = connectionState is BleConnectionState.Syncing ||
        connectionState is BleConnectionState.Parsing
    val targetOffsetY = if (
        currentRoute != ROUTE_SETTINGS &&
        currentRoute != ROUTE_DEVICES &&
        (scrollBehavior.isVisible || isSyncingNow)
    ) 0f else hiddenOffsetPx

    val slideSpec: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    val animatedOffsetY by animateFloatAsState(
        targetValue = targetOffsetY,
        animationSpec = slideSpec,
        label = "bar_slide",
    )

    // Active indicator geometry
    val centerZoneWidth = pillWidth - 128.dp
    val itemSlotWidth = centerZoneWidth / 4

    val targetCenterX: Dp
    val targetWidth: Dp
    val targetHeight: Dp
    when (currentRoute) {
        Page.DASHBOARD.name -> {
            targetCenterX = 64.dp + itemSlotWidth * 0.5f
            targetWidth = itemSlotWidth
            targetHeight = 48.dp
        }
        Page.DAILY_DETAIL.name -> {
            targetCenterX = 64.dp + itemSlotWidth * 1.5f
            targetWidth = itemSlotWidth
            targetHeight = 48.dp
        }
        Page.QUESTIONS.name -> {
            targetCenterX = 64.dp + itemSlotWidth * 2.5f
            targetWidth = itemSlotWidth
            targetHeight = 48.dp
        }
        Page.HISTORY.name -> {
            targetCenterX = 64.dp + itemSlotWidth * 3.5f
            targetWidth = itemSlotWidth
            targetHeight = 48.dp
        }
        else -> { // ROUTE_SETTINGS, ROUTE_DEVICES — no pill
            targetCenterX = 64.dp + itemSlotWidth * 0.5f
            targetWidth = 0.dp
            targetHeight = 0.dp
        }
    }

    val indicatorSpec: AnimationSpec<Dp> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
    val animCenterX by animateDpAsState(targetCenterX, indicatorSpec, label = "ind_cx")
    val animWidth by animateDpAsState(targetWidth, indicatorSpec, label = "ind_w")
    val animHeight by animateDpAsState(targetHeight, indicatorSpec, label = "ind_h")

    val indicatorOffsetX = animCenterX - animWidth / 2
    val indicatorOffsetY = (barHeightDp - animHeight) / 2

    // Colors
    val primaryContainer = MaterialTheme.colorScheme.primary
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline

    val activeColor = onPrimaryContainer
    val inactiveColor = onSurface.copy(alpha = 0.6f)

    Box(
        modifier = modifier
            .wrapContentSize()
            .offset { IntOffset(0, animatedOffsetY.roundToInt()) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp),
        ) {
            Surface(
                modifier = Modifier
                    .width(pillWidth)
                    .height(barHeightDp),
                shape = RoundedCornerShape(50),
                color = surface.copy(alpha = if (darkTheme) 0.88f else 0.92f),
                border = BorderStroke(0.5.dp, outline.copy(alpha = 0.2f)),
                shadowElevation = 8.dp,
                tonalElevation = 0.dp,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Active indicator — drawn behind items
                    Box(
                        modifier = Modifier
                            .offset(x = indicatorOffsetX, y = indicatorOffsetY)
                            .size(animWidth, animHeight)
                            .clip(RoundedCornerShape(50))
                            .background(primaryContainer),
                    )
                    // Items
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Left end cap — Settings
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clickable(
                                    onClick = onSettingsTapped,
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Settings",
                                tint = if (currentRoute == ROUTE_SETTINGS) activeColor else inactiveColor,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // Centre zone — icon tabs
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .drawBehind {
                                    val top = 12.dp.toPx()
                                    val bottom = size.height - 12.dp.toPx()
                                    val c = outline.copy(alpha = 0.35f)
                                    drawLine(c, Offset(0f, top), Offset(0f, bottom), 0.5.dp.toPx())
                                    drawLine(c, Offset(size.width, top), Offset(size.width, bottom), 0.5.dp.toPx())
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NavIcon(Icons.Outlined.Home, "Dashboard", currentRoute == Page.DASHBOARD.name, activeColor, inactiveColor, onDashboardTapped, Modifier.weight(1f).fillMaxHeight())
                            NavIcon(Icons.AutoMirrored.Outlined.Article, "Daily Detail", currentRoute == Page.DAILY_DETAIL.name, activeColor, inactiveColor, onDailyDetailTapped, Modifier.weight(1f).fillMaxHeight())
                            NavIcon(Icons.Outlined.Checklist, "Questions", currentRoute == Page.QUESTIONS.name, activeColor, inactiveColor, onQuestionsTapped, Modifier.weight(1f).fillMaxHeight())
                            NavIcon(Icons.Outlined.BarChart, "History", currentRoute == Page.HISTORY.name, activeColor, inactiveColor, onHistoryTapped, Modifier.weight(1f).fillMaxHeight())
                        }
                        // Right end cap — Devices
                        DevicesButton(
                            connectionState = connectionState,
                            batteryPct = batteryPct,
                            isActive = currentRoute == ROUTE_DEVICES,
                            activeColor = activeColor,
                            inactiveColor = inactiveColor,
                            onTap = onDevicesTapped,
                            onLongPress = onDevicesLongPressed,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DevicesButton(
    connectionState: BleConnectionState,
    batteryPct: Int?,
    isActive: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    inactiveColor: androidx.compose.ui.graphics.Color,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val isSyncing = connectionState is BleConnectionState.Syncing ||
        connectionState is BleConnectionState.Parsing
    val isConnected = connectionState is BleConnectionState.Connected ||
        connectionState is BleConnectionState.SyncComplete

    val infiniteTransition = rememberInfiniteTransition(label = "sync")
    val syncRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )
    val rotation = if (isSyncing) syncRotation else 0f

    val icon = when {
        isSyncing -> Icons.Outlined.Sync
        isConnected -> Icons.Filled.BluetoothConnected
        else -> Icons.Outlined.Bluetooth
    }

    val iconColor = if (isActive) activeColor else inactiveColor

    Box(
        modifier = Modifier
            .size(64.dp)
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    if (!isSyncing) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                },
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Devices",
                tint = iconColor,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(rotation),
            )
            if (batteryPct != null && (isConnected || isSyncing)) {
                Text(
                    text = "$batteryPct%",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    contentDesc: String,
    isActive: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    inactiveColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = if (isActive) activeColor else inactiveColor,
            modifier = Modifier.size(22.dp),
        )
    }
}
