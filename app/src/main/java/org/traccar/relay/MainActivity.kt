package org.traccar.relay

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import androidx.lifecycle.viewmodel.compose.viewModel
import org.traccar.relay.auth.KeySetupScreen
import org.traccar.relay.auth.LoginScreen
import org.traccar.relay.ui.LogsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("MainActivity", "App opened")
        enableEdgeToEdge()
        requestBatteryOptimizationExemption()
        setContent {
            MaterialTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.state.collectAsState()
                var showLogs by remember { mutableStateOf(false) }

                if (state.token == null) {
                    LoginScreen(onTokenReceived = viewModel::onTokenReceived)
                } else if (state.needsKeySetup) {
                    KeySetupScreen(onSharedKeyReceived = viewModel::onSharedKeyReceived)
                } else if (showLogs) {
                    LogsScreen(onBack = { showLogs = false })
                } else {
                    DeviceListScreen(viewModel, onShowLogs = { showLogs = true })
                }
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val powerManager: PowerManager = getSystemService() ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }
}
