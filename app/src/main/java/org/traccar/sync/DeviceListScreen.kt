package org.traccar.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var showUrlDialog by remember { mutableStateOf(false) }

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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable { showUrlDialog = true },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.server_url), style = MaterialTheme.typography.labelSmall)
                    Text(state.serverUrl, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (state.loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.error != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else if (state.devices.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_devices_found))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(state.devices) { device ->
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text(device.id) },
                            modifier = Modifier.clickable {
                                clipboardManager.setText(AnnotatedString(device.id))
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerUrlDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_url)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
