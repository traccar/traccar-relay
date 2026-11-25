package org.traccar.relay.ui

import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.traccar.relay.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    val error = stringResource(R.string.logs_error)
    LaunchedEffect(Unit) {
        logs = withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder(
                    "logcat", "-d", "-v", "threadtime", "-t", "300", "--uid=${Process.myUid()}",
                    "MainActivity:V", "MainViewModel:V", "DeviceRepository:V", "KeySetupScreen:V",
                    "McsClient:V", "PushNotificationService:V", "LocationWorker:V", "*:S",
                ).redirectErrorStream(true).start()
                try {
                    val output = process.inputStream.bufferedReader().use { it.readLines() }
                    if (process.waitFor() == 0) output else listOf(error)
                } finally {
                    process.destroy()
                }
            } catch (_: IOException) {
                listOf(error)
            }
        }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            reverseLayout = true,
        ) {
            items(logs.asReversed()) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                )
            }
        }
    }
}
