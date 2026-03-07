package org.traccar.relay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.traccar.relay.auth.KeySetupScreen
import org.traccar.relay.auth.LoginScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.state.collectAsState()

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
