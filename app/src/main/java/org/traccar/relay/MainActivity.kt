package org.traccar.relay

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import androidx.lifecycle.viewmodel.compose.viewModel
import org.traccar.relay.auth.KeySetupScreen
import org.traccar.relay.auth.LoginScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBatteryOptimizationExemption()
        setContent {
            MaterialTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.state.collectAsState()
                var showGoogleSetup by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(showGoogleSetup, state.authenticated, state.needsKeySetup) {
                    if (showGoogleSetup && state.authenticated && !state.needsKeySetup) {
                        showGoogleSetup = false
                    }
                }

                if (showGoogleSetup) {
                    key(state.authenticationGeneration) {
                        if (!state.authenticated) {
                            LoginScreen(
                                authenticationInProgress = state.authenticationInProgress,
                                error = state.error,
                                onClose = { showGoogleSetup = false },
                                onTokenReceived = viewModel::onTokenReceived,
                            )
                        } else if (state.needsKeySetup) {
                            KeySetupScreen(
                                webLoginUrl = state.keySetupUrl,
                                loading = state.authenticationInProgress,
                                error = state.error,
                                onWebLoginUrlRequired = viewModel::prepareKeySetup,
                                onClose = { showGoogleSetup = false },
                                onSharedKeyReceived = viewModel::onSharedKeyReceived,
                            )
                        }
                    }
                } else {
                    DeviceListScreen(
                        viewModel = viewModel,
                        onGoogleSetup = { showGoogleSetup = true },
                    )
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
