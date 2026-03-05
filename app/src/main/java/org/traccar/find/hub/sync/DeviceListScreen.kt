package org.traccar.find.hub.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun DeviceListScreen(viewModel: MainViewModel) {
    val devices by viewModel.devices.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val locatingDevice by viewModel.locatingDevice.collectAsState()
    val locatedDeviceId by viewModel.locatedDeviceId.collectAsState()
    val locationResult by viewModel.locationResult.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else if (devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No devices found")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(devices) { device ->
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = {
                            Column {
                                Text(device.id)
                                if (locatingDevice == device.id) {
                                    Text(
                                        "Locating...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (locationResult != null && locatedDeviceId == device.id) {
                                    LocationResultView(locationResult!!)
                                }
                            }
                        },
                        trailingContent = {
                            if (locatingDevice == device.id) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                IconButton(onClick = { viewModel.requestLocation(device) }) {
                                    Text("\uD83D\uDCCD")
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LocationResultView(result: LocationResult) {
    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.padding(top = 4.dp)) {
        for (entry in result.locations) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            entry.timestamp?.let {
                Text("Time: $it", style = MaterialTheme.typography.bodySmall)
            }
            entry.status?.let {
                Text("Status: $it", style = MaterialTheme.typography.bodySmall)
            }
            entry.accuracy?.let {
                Text("Accuracy: ${it}m", style = MaterialTheme.typography.bodySmall)
            }
            entry.altitude?.let {
                if (it != 0) Text("Altitude: ${it}m", style = MaterialTheme.typography.bodySmall)
            }
            entry.semanticLocation?.let {
                if (it.isNotEmpty()) Text("Location: $it", style = MaterialTheme.typography.bodySmall)
            }
            if (entry.latitude != null && entry.longitude != null) {
                val url = "https://maps.google.com/?q=${entry.latitude},${entry.longitude}"
                val coordText = "%.6f, %.6f".format(entry.latitude, entry.longitude)
                val annotatedString = buildAnnotatedString {
                    pushStringAnnotation("URL", url)
                    withStyle(SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )) {
                        append(coordText)
                    }
                    pop()
                }
                ClickableText(
                    text = annotatedString,
                    style = MaterialTheme.typography.bodySmall,
                    onClick = { offset ->
                        annotatedString.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()?.let { uriHandler.openUri(it.item) }
                    }
                )
            }
        }
    }
}
