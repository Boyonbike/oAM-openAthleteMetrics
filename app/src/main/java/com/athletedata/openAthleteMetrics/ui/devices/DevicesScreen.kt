package com.athletedata.openAthleteMetrics.ui.devices

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.ble.BlePermissionHelper
import com.athletedata.openAthleteMetrics.ble.driver.WasmDriverManifest
import com.athletedata.openAthleteMetrics.ble.rememberBlePermissionState
import com.athletedata.openAthleteMetrics.data.model.Device
import com.athletedata.openAthleteMetrics.ui.components.PillSelector
import com.athletedata.openAthleteMetrics.ui.theme.CardRadius
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import com.athletedata.openAthleteMetrics.ui.theme.space4
import com.athletedata.openAthleteMetrics.ui.theme.space8
import kotlinx.coroutines.launch
import timber.log.Timber

@Composable
fun DevicesScreen(
    onNavigateBack: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val drivers by viewModel.drivers.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val blePermissionState = rememberBlePermissionState()

    var errorMessages by remember { mutableStateOf<List<String>?>(null) }
    var driverToDelete by remember { mutableStateOf<WasmDriverManifest?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.onDriverFileSelected(it) } }

    LaunchedEffect(Unit) {
        viewModel.driverEvents.collect { event ->
            when (event) {
                is DriverEvent.ValidationError -> errorMessages = event.errors
                is DriverEvent.Error -> errorMessages = listOf(event.message)
            }
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

                val sortedDevices = remember(devices) { devices.sortedBy { it.displayName } }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(space8),
                    verticalArrangement = Arrangement.spacedBy(space8),
                    horizontalArrangement = Arrangement.spacedBy(space8),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (selectedTab) {
                        DevicesTab.DEVICE -> {
                            items(sortedDevices, key = { it.id }) { device ->
                                DeviceCell(device)
                            }
                            item {
                                AddCell("Add Device") {
                                    when {
                                        !BlePermissionHelper.allGranted(context) ->
                                            blePermissionState.requestPermissions()
                                        !BlePermissionHelper.isBluetoothEnabled(context) ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "Please enable Bluetooth to add a device"
                                                )
                                            }
                                        else -> Timber.d("BLE ready")
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
                                AddCell("Add Driver") { filePicker.launch("*/*") }
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
        }
    }
}

@Composable
private fun AddCell(label: String, onClick: () -> Unit = {}) {
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
        }
    }
}

@Composable
private fun DeviceCell(device: Device) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
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
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(space8))
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
                text = device.driverId,
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(space4))
            Text(
                text = if (device.lastSyncMs != null) "Last sync: ${relativeTime(device.lastSyncMs)}"
                       else "Never synced",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

private fun relativeTime(ms: Long): String {
    val diff = (System.currentTimeMillis() - ms) / 1000
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60} min ago"
        diff < 86400 -> "${diff / 3600} hours ago"
        else -> "${diff / 86400} days ago"
    }
}
