package org.traccar.relay

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource

import androidx.compose.material3.AlertDialog
import org.traccar.relay.ui.ServerUrlDialog
import org.traccar.relay.ui.ShimmerListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showUrlDialog by remember { mutableStateOf(false) }
    var scheduleInfoDeviceId by remember { mutableStateOf<String?>(null) }

    if (scheduleInfoDeviceId != null) {
        val info = state.workSchedules[scheduleInfoDeviceId]
        if (info != null) {
            val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
            val nextTime = if (info.nextScheduleTimeMillis > 0) {
                dateFormat.format(Date(info.nextScheduleTimeMillis))
            } else {
                "Unknown"
            }
            AlertDialog(
                onDismissRequest = { scheduleInfoDeviceId = null },
                title = { Text(stringResource(R.string.schedule_info)) },
                text = {
                    Text(
                        listOf(
                            "${stringResource(R.string.state)}: ${info.state}",
                            "${stringResource(R.string.next_run)}: $nextTime",
                            "${stringResource(R.string.run_attempts)}: ${info.runAttemptCount}",
                        ).joinToString("\n"),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { scheduleInfoDeviceId = null }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.cancelWork(scheduleInfoDeviceId!!)
                        scheduleInfoDeviceId = null
                    }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }

    if (showUrlDialog) {
        ServerUrlDialog(
            currentUrl = state.serverUrl,
            onDismiss = { showUrlDialog = false },
            onConfirm = { url ->
                viewModel.updateServerUrl(url)
                showUrlDialog = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { viewModel.signOut() }) {
                        Text(stringResource(R.string.sign_out))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.server_url)) },
                    supportingContent = { Text(state.serverUrl) },
                    modifier = Modifier.clickable { showUrlDialog = true },
                )
            }
            if (state.loading) {
                items(3) { ShimmerListItem() }
            } else if (state.error != null) {
                item {
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        headlineContent = {
                            Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        },
                    )
                }
            } else if (state.devices.isEmpty()) {
                item {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.no_devices_found)) },
                    )
                }
            } else {
                items(state.devices) { device ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                        headlineContent = { Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(device.id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = if (device.id in state.workSchedules) {{
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { scheduleInfoDeviceId = device.id },
                            )
                        }} else null,
                        modifier = Modifier.clickable {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", device.id)))
                            }
                        },
                    )
                }
            }
        }
    }
}
