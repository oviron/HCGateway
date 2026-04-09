package dev.shuchir.hcgateway.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import dev.shuchir.hcgateway.domain.model.SyncState
import dev.shuchir.hcgateway.ui.components.FilledCard
import dev.shuchir.hcgateway.ui.theme.ExtendedTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToPermissions: (() -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val hasPermissions by viewModel.hasPermissions.collectAsState()
    val serverReachable by viewModel.serverReachable.collectAsState()
    val pendingCounts by viewModel.pendingCounts.collectAsState()
    val serverCounts by viewModel.serverCounts.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
    var pendingSync by remember { mutableStateOf<(() -> Unit)?>(null) }
    var syncSource by remember { mutableIntStateOf(0) }
    var lastFailedTypes by remember { mutableStateOf<List<String>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) {
        viewModel.onPermissionsResult()
    }

    LaunchedEffect(Unit) {
        viewModel.checkPermissions()
    }

    // Load pending counts when permissions are granted
    LaunchedEffect(hasPermissions) {
        if (hasPermissions == true) {
            viewModel.loadPendingCounts()
            viewModel.loadServerCounts()
            while (true) {
                kotlinx.coroutines.delay(60_000)
                viewModel.refreshTable()
            }
        }
    }


    var isRefreshing by remember { mutableStateOf(serverCounts == null) }
    val pullToRefreshState = rememberPullToRefreshState()

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            viewModel.refreshTable()
            viewModel.checkServerConnection()
            isRefreshing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HCGateway") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pullToRefresh(isRefreshing, state = pullToRefreshState, onRefresh = { isRefreshing = true }),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- Connection status card ---
            val statusColor = when (serverReachable) {
                true -> ExtendedTheme.colors.successContainer
                false -> MaterialTheme.colorScheme.errorContainer
                null -> MaterialTheme.colorScheme.surfaceContainerLow
            }
            val statusContentColor = when (serverReachable) {
                true -> ExtendedTheme.colors.onSuccessContainer
                false -> MaterialTheme.colorScheme.onErrorContainer
                null -> MaterialTheme.colorScheme.onSurface
            }
            val statusLabel = when (serverReachable) {
                true -> "Connected to ${settings.apiBase}"
                false -> "Cannot reach ${settings.apiBase}"
                null -> "Connecting to ${settings.apiBase}..."
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth().then(
                    if (serverReachable == false) Modifier.clickable { viewModel.checkServerConnection() }
                    else Modifier
                ),
                colors = CardDefaults.elevatedCardColors(containerColor = statusColor),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = null, tint = statusContentColor)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(statusLabel, style = MaterialTheme.typography.titleSmall, color = statusContentColor)
                        Text("User: ${settings.username}", style = MaterialTheme.typography.bodySmall, color = statusContentColor.copy(alpha = 0.7f))
                        if (serverReachable == false) {
                            Text("Tap to retry", style = MaterialTheme.typography.labelSmall, color = statusContentColor.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            // --- Permissions card ---
            if (hasPermissions == false) {
                FilledCard(tonalElevation = true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Health Connect permissions required", style = MaterialTheme.typography.titleSmall)
                            Text("Grant permissions to read and write health data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (onNavigateToPermissions != null) onNavigateToPermissions()
                            else permissionLauncher.launch(viewModel.getRequiredPermissions())
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("Grant Permissions") }
                }
            }

            // --- Sync card (actions + results) ---
            FilledCard(tonalElevation = true) {
                // Auto-dismiss Done/Cancelled: wait for amplitude morph, then shrink, then reset
                var progressDismissing by remember { mutableStateOf(false) }
                LaunchedEffect(syncState) {
                    suspend fun dismissProgress(timestamp: Long, consumeChanges: Boolean = false) {
                        val age = System.currentTimeMillis() - timestamp
                        if (age > 2000) {
                            viewModel.resetSyncState()
                            return
                        }
                        kotlinx.coroutines.delay(600)
                        progressDismissing = true
                        kotlinx.coroutines.delay(400)
                        progressDismissing = false
                        viewModel.loadServerCounts()
                        viewModel.loadPendingCounts(consumeChanges = consumeChanges)
                        viewModel.resetSyncState()
                    }

                    when (syncState) {
                        is SyncState.Done -> {
                            val done = syncState as SyncState.Done
                            lastFailedTypes = done.failedTypes.ifEmpty { emptyList() }
                            dismissProgress(done.timestamp, consumeChanges = syncSource == 1)
                        }
                        is SyncState.Cancelled -> {
                            val cancelled = syncState as SyncState.Cancelled
                            dismissProgress(cancelled.timestamp)
                        }
                        else -> {}
                    }
                }

                val isSyncing = syncState is SyncState.Syncing
                val isDone = syncState is SyncState.Done || syncState is SyncState.Cancelled
                val showProgress = isSyncing || isDone
                val errorState = syncState as? SyncState.Error

                // syncSource: 0 = Sync (left), 1 = Force Sync (right)
                val showCancel = isSyncing || isDone || pendingSync != null
                var cancelRequested by remember { mutableStateOf(false) }
                if (!showCancel) cancelRequested = false

                Text("Sync", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))

                // Progress bar above buttons — height animates to 0 when idle
                val syncingState = syncState as? SyncState.Syncing
                val targetProgress = when {
                    isDone -> 1f
                    syncingState != null && syncingState.totalTypes > 0 -> syncingState.typesCompleted.toFloat() / syncingState.totalTypes
                    else -> 0f
                }
                val animatedProgress by animateFloatAsState(
                    targetValue = targetProgress,
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    label = "syncProgress",
                )
                val amplitudeValue = if (isDone || !showProgress) 0f else 1f
                val progressHeight by animateDpAsState(
                    targetValue = if (showProgress && !progressDismissing) 16.dp else 0.dp,
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    label = "progressHeight",
                )
                if (progressHeight > 0.dp) {
                    LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().height(progressHeight),
                        amplitude = { amplitudeValue },
                    )
                    val showStatusText = syncingState != null && !progressDismissing && progressHeight > 8.dp
                    var lastStatusText by remember { mutableStateOf("") }
                    if (syncingState != null) {
                        val types = "${syncingState.typesCompleted}/${syncingState.totalTypes} types"
                        val records = syncingState.recordsSynced
                        val currentType = syncingState.currentType
                        lastStatusText = when {
                            currentType.isNotBlank() && records > 0 -> "Reading $currentType… · $types · $records records"
                            currentType.isNotBlank() -> "Reading $currentType… · $types"
                            else -> types
                        }
                    }
                    val statusTextHeight by animateDpAsState(
                        targetValue = if (showStatusText) 20.dp else 0.dp,
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        label = "statusTextHeight",
                    )
                    if (statusTextHeight > 0.dp && lastStatusText.isNotBlank()) {
                        Text(
                            lastStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp).height(statusTextHeight),
                        )
                    }

                    // Show completed types with record counts
                    if (syncingState != null && syncingState.completedTypes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            syncingState.completedTypes.forEach { result ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "✓ ${result.typeName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ExtendedTheme.colors.onSuccessContainer,
                                    )
                                    Text(
                                        "${result.recordCount}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(if (progressHeight > 8.dp) 4.dp else 0.dp))
                }

                // Error
                if (errorState != null) {
                    Text("Error: ${errorState.message}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = viewModel::resetSyncState) { Text("Dismiss") }
                    }
                }

                // Buttons — the non-pressed button shrinks, pressed button morphs to Cancel
                if (errorState == null) {
                    // The button that was NOT pressed shrinks to 0
                    val leftWeight by animateFloatAsState(
                        targetValue = when {
                            showCancel && syncSource == 1 -> 0f // Force Sync pressed → left shrinks
                            showCancel -> 1f // Sync pressed → left stays (becomes Cancel)
                            else -> 1f
                        },
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                        label = "leftWeight",
                        finishedListener = { value ->
                            if (value == 0f && pendingSync != null) {
                                pendingSync?.invoke()
                                pendingSync = null
                            }
                        },
                    )
                    val rightWeight by animateFloatAsState(
                        targetValue = when {
                            showCancel && syncSource == 0 -> 0f // Sync pressed → right shrinks
                            showCancel -> 1f // Force Sync pressed → right stays (becomes Cancel)
                            else -> 1f
                        },
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                        label = "rightWeight",
                        finishedListener = { value ->
                            if (value == 0f && pendingSync != null) {
                                pendingSync?.invoke()
                                pendingSync = null
                            }
                        },
                    )

                    val buttonHeight = 40.dp
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(
                            if (leftWeight > 0.05f && rightWeight > 0.05f) 8.dp else 0.dp
                        ),
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                    ) {
                        // Left slot
                        if (leftWeight > 0.05f) {
                            when {
                                // Sync pressed → left becomes Cancel
                                showCancel && syncSource == 0 -> OutlinedButton(
                                    onClick = { cancelRequested = true; viewModel.cancelSync() },
                                    modifier = Modifier.weight(leftWeight).fillMaxHeight(),
                                    shapes = ButtonDefaults.shapes(),
                                    enabled = !cancelRequested,
                                ) { Text("Cancel") }
                                // Force Sync pressed → left is shrinking placeholder
                                showCancel && syncSource == 1 -> Spacer(Modifier.weight(leftWeight))
                                // Idle → normal Sync button
                                else -> Button(
                                    onClick = { syncSource = 0; pendingSync = { viewModel.syncNow() } },
                                    modifier = Modifier.weight(leftWeight).fillMaxHeight(),
                                    enabled = hasPermissions == true,
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Sync")
                                }
                            }
                        }
                        // Right slot
                        if (rightWeight > 0.05f) {
                            when {
                                // Force Sync pressed → right becomes Cancel
                                showCancel && syncSource == 1 -> OutlinedButton(
                                    onClick = { cancelRequested = true; viewModel.cancelSync() },
                                    modifier = Modifier.weight(rightWeight).fillMaxHeight(),
                                    shapes = ButtonDefaults.shapes(),
                                    enabled = !cancelRequested,
                                ) { Text("Cancel") }
                                // Sync pressed → right is shrinking placeholder
                                showCancel && syncSource == 0 -> Spacer(Modifier.weight(rightWeight))
                                // Idle → normal Force Sync button
                                else -> OutlinedButton(
                                    onClick = { showDatePicker = true },
                                    modifier = Modifier.weight(rightWeight).fillMaxHeight(),
                                    enabled = hasPermissions == true,
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Force Sync", maxLines = 1)
                                }
                            }
                        }
                    }
                }

                // Failed types warning
                if (lastFailedTypes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Failed to sync: ${lastFailedTypes.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Data overview
                val showTable = settings.lastSync > 0 || serverCounts == null || serverCounts?.isNotEmpty() == true
                if (showTable) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    if (settings.lastSync > 0) {
                        val lastSyncTime = Instant.ofEpochMilli(settings.lastSync)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        Text(
                            "Last synced: $lastSyncTime",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    val density = LocalDensity.current
                    val tableHeightPx by viewModel.tableHeightPx.collectAsState()
                    val minHeight = with(density) { tableHeightPx.toDp() }
                    Box(modifier = Modifier.defaultMinSize(minHeight = minHeight)) {
                        Crossfade(
                            targetState = serverCounts != null,
                            label = "tableAnim",
                        ) { loaded ->
                            if (loaded) {
                                Column(modifier = Modifier.onGloballyPositioned { viewModel.updateTableHeight(it.size.height) }) {
                                    DataOverviewTable(pendingCounts, serverCounts, hasEverSynced = settings.lastSync > 0)
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = minHeight),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LoadingIndicator(modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    }
                }
            }

            // --- Health Connect unavailable ---
            if (!viewModel.isHealthConnectAvailable) {
                FilledCard(tonalElevation = true) {
                    Text(
                        "Health Connect is not available on this device",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        PullToRefreshDefaults.LoadingIndicator(
            state = pullToRefreshState,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        }
    }

    // Force Sync Date Range Picker
    if (showDatePicker) {
        val datePickerState = rememberDateRangePickerState(
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val startOfTomorrow = java.time.LocalDate.now().plusDays(1)
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    return utcTimeMillis < startOfTomorrow
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val start = datePickerState.selectedStartDateMillis
                    val end = datePickerState.selectedEndDateMillis
                    if (start != null && end != null) {
                        val startDate = Instant.ofEpochMilli(start).atZone(java.time.ZoneId.of("UTC")).toLocalDate()
                        val endDate = Instant.ofEpochMilli(end).atZone(java.time.ZoneId.of("UTC")).toLocalDate()
                        syncSource = 1
                        pendingSync = { viewModel.syncRange(startDate, endDate) }
                    }
                    showDatePicker = false
                }) { Text("Force Sync") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DateRangePicker(
                state = datePickerState,
                title = { Text("Force sync date range", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
                headline = { Text("Select range to re-sync", modifier = Modifier.padding(start = 24.dp)) },
                modifier = Modifier.weight(1f),
            )
        }
    }

}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DataOverviewTable(
    pendingCounts: Map<String, Int>,
    serverCounts: Map<String, Int>?,
    hasEverSynced: Boolean = false,
) {
    if (serverCounts == null) return

    val allTypes = (pendingCounts.keys + serverCounts.keys).distinct()
        .sortedByDescending { serverCounts[it] ?: 0 }

    if (allTypes.isEmpty()) return

    // Header
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Type", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("New", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 32.dp))
            Text("Server", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 40.dp))
        }
    }

    allTypes.forEach { typeName ->
        val pending = pendingCounts[typeName] ?: 0
        val srvCount = serverCounts[typeName]
        val pendingText = if (!hasEverSynced) "-" else "$pending"

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(typeName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(
                    pendingText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (hasEverSynced && pending > 0) FontWeight.Medium else FontWeight.Normal,
                    color = if (hasEverSynced && pending > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 32.dp),
                )
                Text(
                    "${srvCount ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 40.dp),
                )
            }
        }
    }
}

