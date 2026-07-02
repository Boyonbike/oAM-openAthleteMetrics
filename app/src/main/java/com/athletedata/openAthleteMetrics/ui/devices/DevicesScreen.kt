package com.athletedata.openAthleteMetrics.ui.devices

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.ble.BleConnectionState
import com.athletedata.openAthleteMetrics.ble.BlePermissionHelper
import com.athletedata.openAthleteMetrics.ble.DiscoveredCandidate
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.rememberBlePermissionState
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.ui.components.PillSelector
import com.athletedata.openAthleteMetrics.ui.theme.CardRadius
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import com.athletedata.openAthleteMetrics.ui.theme.space4
import com.athletedata.openAthleteMetrics.ui.theme.space8
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen(
    onNavigateBack: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val drivers by viewModel.drivers.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val reprocessState by viewModel.reprocessState.collectAsStateWithLifecycle()
    val reprocessingDeviceId by viewModel.reprocessingDeviceId.collectAsStateWithLifecycle()
    // REMOVED: interrupted-sync-recovery
    // ADDED: interrupted-sync-message
    val syncInterrupted by viewModel.syncInterrupted.collectAsStateWithLifecycle()
    val discoveredCandidates by viewModel.discoveredCandidates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val blePermissionState = rememberBlePermissionState()

    var errorMessages by remember { mutableStateOf<List<String>?>(null) }
    var driverToDelete by remember { mutableStateOf<WasmDriverManifest?>(null) }
    var deviceToRemove by remember { mutableStateOf<Device?>(null) }
    var deviceToShowActions by remember { mutableStateOf<Device?>(null) }
    var deviceToReprocess by remember { mutableStateOf<Device?>(null) }
    // REMOVED: early-sync-warning

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.onDriverFileSelected(it) } }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) filePicker.launch("*/*") }

    LaunchedEffect(Unit) {
        viewModel.driverEvents.collect { event ->
            when (event) {
                is DriverEvent.ValidationError -> errorMessages = event.errors
                is DriverEvent.Error -> errorMessages = listOf(event.message)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    errorMessages?.let { errors ->
        AlertDialog(
            onDismissRequest = { errorMessages = null },
            title = { Text("Driver Error") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    errors.forEach { Text(it, style = TypographyMeta) }
                }
            },
            confirmButton = {
                TextButton(onClick = { errorMessages = null }) { Text("OK") }
            },
        )
    }

    driverToDelete?.let { manifest ->
        AlertDialog(
            onDismissRequest = { driverToDelete = null },
            title = { Text("Remove Driver") },
            text = { Text("Remove \"${manifest.displayName}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDriver(manifest.id)
                    driverToDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { driverToDelete = null }) { Text("Cancel") }
            },
        )
    }

    deviceToRemove?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToRemove = null },
            title = { Text("Remove device") },
            text = { Text("Remove ${device.displayName} from saved devices? This does not affect any synced data.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onRemoveDeviceTapped(device)
                    deviceToRemove = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRemove = null }) { Text("Cancel") }
            },
        )
    }

    deviceToShowActions?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToShowActions = null },
            title = { Text(device.displayName) },
            text = null,
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        deviceToShowActions = null
                        deviceToReprocess = device
                    }) { Text("Reprocess synced data") }
                    TextButton(onClick = {
                        deviceToShowActions = null
                        deviceToRemove = device
                    }) { Text("Remove device") }
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToShowActions = null }) { Text("Cancel") }
            },
        )
    }

    // REMOVED: early-sync-warning — AlertDialog for "Sync may be incomplete" removed

    deviceToReprocess?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToReprocess = null },
            title = { Text("Reprocess synced data") },
            text = {
                Text(
                    "This will reprocess raw BLE data from ${device.displayName} " +
                    "using the current driver. Covers the last 7 days of synced raw data — " +
                    "older data must resync naturally. " +
                    "Corrected values will replace existing records."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onReprocessConfirmed(device)
                    deviceToReprocess = null
                }) { Text("Reprocess") }
            },
            dismissButton = {
                TextButton(onClick = { deviceToReprocess = null }) { Text("Cancel") }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column {
                DevicesHeader(
                    selectedTab = selectedTab,
                    onSelectTab = viewModel::selectTab,
                    onNavigateBack = onNavigateBack,
                )

                if (connectionState !is BleConnectionState.Idle &&
                    connectionState !is BleConnectionState.Connected) {
                    BleBanner(
                        state = connectionState,
                        onSyncAcknowledged = viewModel::onSyncAcknowledged,
                        onAddDeviceTapped = viewModel::onAddDeviceTapped,
                        onDisconnectDismissed = viewModel::onDisconnectDismissed,
                    )
                }

                // REMOVED: interrupted-sync-recovery
                // ADDED: interrupted-sync-message
                if (syncInterrupted.show) {
                    SyncInterruptedBanner(onDismiss = viewModel::onSyncInterruptedDismissed)
                }

                val sortedDevices = remember(devices) { devices.sortedBy { it.displayName } }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = space8,
                        top = space8,
                        end = space8,
                        bottom = space8 + 80.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(space8),
                    horizontalArrangement = Arrangement.spacedBy(space8),
                    modifier = Modifier.weight(1f),
                ) {
                    when (selectedTab) {
                        DevicesTab.DEVICE -> {
                            items(sortedDevices, key = { it.id }) { device ->
                                DeviceCell(
                                    device = device,
                                    connectionState = connectionState,
                                    reprocessState = reprocessState,
                                    isReprocessingThisDevice = reprocessingDeviceId == device.id,
                                    onConnect = { viewModel.onDeviceCellTapped(device) },
                                    onSync = {
                                        // Early sync warning removed. The sync system is stateless with respect to timing —
                                        // a sync can be initiated at any time without restriction.
                                        viewModel.onSyncTapped()
                                    },
                                    onLongPress = { deviceToShowActions = device },
                                    onDisconnect = viewModel::onDisconnectTapped,
                                )
                            }
                            items(discoveredCandidates, key = { it.address }) { candidate ->
                                val hasDuplicateNames = discoveredCandidates.count { it.deviceName == candidate.deviceName } > 1
                                CandidateCell(
                                    candidate = candidate,
                                    hasDuplicateNames = hasDuplicateNames,
                                    onAdd = { viewModel.onCandidateSelected(candidate) },
                                )
                            }
                            if (connectionState !is BleConnectionState.Scanning && discoveredCandidates.isEmpty()) {
                                item {
                                    AddCell(
                                        label = "Add Device",
                                        sublabel = if (sortedDevices.isNotEmpty()) "Multi-device not yet supported" else null,
                                    ) {
                                        when {
                                            !BlePermissionHelper.allGranted(context) ->
                                                blePermissionState.requestPermissions()
                                            !BlePermissionHelper.isBluetoothEnabled(context) ->
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        "Please enable Bluetooth to add a device"
                                                    )
                                                }
                                            else -> viewModel.onAddDeviceTapped()
                                        }
                                    }
                                }
                            }
                        }
                        DevicesTab.DRIVER -> {
                            items(drivers, key = { it.id }) { driver ->
                                DriverCell(
                                    manifest = driver,
                                    onLongPress = { driverToDelete = driver },
                                )
                            }
                            item {
                                AddCell("Add Driver") {
                                    if (BlePermissionHelper.storagePermissionsRequired() &&
                                        !BlePermissionHelper.storagePermissionGranted(context)
                                    ) {
                                        storagePermissionLauncher.launch(
                                            Manifest.permission.READ_EXTERNAL_STORAGE
                                        )
                                    } else {
                                        filePicker.launch("*/*")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun DevicesHeader(
    selectedTab: DevicesTab,
    onSelectTab: (DevicesTab) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                PillSelector(
                    tabs = listOf("Device", "Driver"),
                    selectedIndex = selectedTab.ordinal,
                    onSelect = { onSelectTab(DevicesTab.entries[it]) },
                    modifier = Modifier.align(Alignment.Center),
                )
                Spacer(
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.CenterEnd),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun AddCell(label: String, sublabel: String? = null, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(CardRadius),
            )
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(CardRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(space8))
            Text(
                text = label,
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sublabel != null) {
                Spacer(modifier = Modifier.height(space4))
                Text(
                    text = sublabel,
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = space8),
                )
            }
        }
    }
}

@Composable
private fun CandidateCell(candidate: DiscoveredCandidate, hasDuplicateNames: Boolean, onAdd: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onAdd)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(CardRadius),
            )
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(CardRadius))
            .padding(space8),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = candidate.deviceName,
                style = TypographyTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(space4))
            Text(
                text = candidate.manifest.displayName,
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "···${candidate.address.takeLast(5)}",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (hasDuplicateNames) 1f else 0.5f
                ),
            )
            Spacer(modifier = Modifier.height(space8))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    text = "ADD",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceCell(
    device: Device,
    connectionState: BleConnectionState,
    reprocessState: ReprocessState,
    isReprocessingThisDevice: Boolean,
    onConnect: () -> Unit,
    onSync: () -> Unit,
    onLongPress: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val isConnecting = (connectionState as? BleConnectionState.Connecting)?.deviceAddress == device.bleAddress
    val isActive = when (connectionState) {
        is BleConnectionState.Connected     -> connectionState.deviceAddress == device.bleAddress
        is BleConnectionState.Syncing       -> connectionState.deviceAddress == device.bleAddress
        is BleConnectionState.SyncComplete  -> connectionState.deviceAddress == device.bleAddress
        else -> false
    }
    val streamState = (connectionState as? BleConnectionState.Connected)
        ?.takeIf { it.deviceAddress == device.bleAddress }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = { if (!isConnecting) { if (isActive) onSync() else onConnect() } },
                onLongClick = onLongPress,
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(CardRadius),
            )
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(CardRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(space8),
        ) {
            Icon(
                imageVector = Icons.Outlined.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(space4))
            Text(
                text = device.displayName,
                style = TypographyTitle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(space4))
            Text(
                text = "MAC: ${device.bleAddress}",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Driver: ${device.driverId}",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Last Sync: ${if (device.lastSyncMs != null) relativeTime(device.lastSyncMs) else "Never"}",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = when {
                    isConnecting -> "Connecting..."
                    isActive     -> "Connected"
                    else         -> "Disconnected"
                },
                style = TypographyMeta,
                color = when {
                    isConnecting -> MaterialTheme.colorScheme.primary
                    isActive     -> MaterialTheme.colorScheme.primary
                    else         -> MaterialTheme.colorScheme.error
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (streamState != null && streamState.packetsReceived > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (streamState.isQuiescent)
                        "Ready to sync — ${streamState.packetsReceived} packets received"
                    else
                        "Receiving data... ${streamState.packetsReceived} packets",
                    style = TypographyMeta,
                    color = if (streamState.isQuiescent)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // State B: Connecting — scrim + spinner
        if (isConnecting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        RoundedCornerShape(CardRadius),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }

        // Battery % top-left — always shown when known
        if (device.lastBatteryPct != null) {
            Text(
                text = "${device.lastBatteryPct}%",
                style = TypographyMeta,
                color = if (device.lastBatteryPct <= 20)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 6.dp, top = 6.dp),
                maxLines = 1,
            )
        }

        // Disconnect icon top-right — only when connected
        if (isActive) {
            IconButton(
                onClick = onDisconnect,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Disconnect",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Reprocess progress bar at bottom of card
        if (isReprocessingThisDevice && reprocessState is ReprocessState.Running) {
            LinearProgressIndicator(
                progress = { reprocessState.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriverCell(manifest: WasmDriverManifest, onLongPress: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onLongClick = onLongPress, onClick = {})
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(CardRadius),
            )
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(CardRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(space8),
        ) {
            Icon(
                imageVector = Icons.Outlined.Extension,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(space8))
            Text(
                text = manifest.displayName,
                style = TypographyTitle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(space4))
            Text(
                text = manifest.id,
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(space4))
            Text(
                text = manifest.version,
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// REMOVED: interrupted-sync-recovery — RecoveryBanner deleted
// ADDED: interrupted-sync-message
@Composable
private fun SyncInterruptedBanner(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = space8, vertical = space4),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Sync interrupted — please sync again",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(space8))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun BleBanner(
    state: BleConnectionState,
    onSyncAcknowledged: () -> Unit,
    onAddDeviceTapped: () -> Unit,
    onDisconnectDismissed: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = space8, vertical = space4),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(space8)) {
            when (state) {
                is BleConnectionState.Scanning -> {
                    Text("Scanning for devices...", style = TypographyTitle)
                    Spacer(Modifier.height(space4))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is BleConnectionState.Connecting -> {
                    Text("Connecting...", style = TypographyTitle)
                    Spacer(Modifier.height(space4))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is BleConnectionState.Syncing -> {
                    Text("Syncing...", style = TypographyTitle)
                    Spacer(Modifier.height(space4))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is BleConnectionState.SyncComplete -> {
                    val s = state.summary
                    val newCount = s.newRecordsInserted + s.accumulatorUpdates +
                        s.sessionsInserted + s.activitiesInserted
                    val hasNew = newCount > 0
                    Text(
                        if (hasNew) "Sync complete" else "Already up to date",
                        style = TypographyTitle,
                        fontWeight = FontWeight.Bold,
                    )
                    if (hasNew) {
                        Spacer(Modifier.height(space4))
                        Text(
                            "$newCount new records",
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // REMOVED: early-sync-warning — "(early sync)" label removed
                    Spacer(Modifier.height(space4))
                    TextButton(
                        onClick = onSyncAcknowledged,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Dismiss") }
                }
                is BleConnectionState.Disconnected -> {
                    Text("Disconnected", style = TypographyTitle)
                    if (state.reason != null) {
                        Text(
                            state.reason,
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LaunchedEffect(state) {
                        delay(3.seconds)
                        onDisconnectDismissed()
                    }
                }
                is BleConnectionState.Error -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.message,
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onAddDeviceTapped) { Text("Retry") }
                    }
                }
                is BleConnectionState.GattCacheError -> {
                    Text(
                        "Having trouble connecting to ${state.deviceName}.",
                        style = TypographyTitle,
                    )
                    Spacer(Modifier.height(space4))
                    Text(
                        "Try turning Bluetooth off and on, then reconnect.",
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(space4))
                    TextButton(
                        onClick = onAddDeviceTapped,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Retry") }
                }
                is BleConnectionState.Idle,
                is BleConnectionState.Connected -> { /* never shown — caller guards these */ }
            }
        }
    }
}

private fun relativeTime(ms: Long): String {
    val diff = (System.currentTimeMillis() - ms) / 1000
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60} min ago"
        diff < 86400 -> "${diff / 3600} hours ago"
        else -> "${diff / 86400} days ago"
    }
}
