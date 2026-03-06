package org.traccar.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import org.traccar.sync.auth.KeySetupScreen
import org.traccar.sync.auth.LoginScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.state.collectAsState()

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (state.token == null) {
                        LoginScreen(onTokenReceived = viewModel::onTokenReceived)
                    } else if (state.needsKeySetup) {
                        KeySetupScreen(onSharedKeyReceived = viewModel::onSharedKeyReceived)
                    } else {
                        DeviceListScreen(viewModel)
                    }
                }
            }
        }
    }
}
