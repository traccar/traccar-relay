package org.traccar.find.hub.sync

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val viewModel: MainViewModel = viewModel()
                val token by viewModel.token.collectAsState()
                val needsKeySetup by viewModel.needsKeySetup.collectAsState()

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (token == null) {
                        LoginScreen(onTokenReceived = viewModel::onTokenReceived)
                    } else if (needsKeySetup) {
                        KeySetupScreen(onSharedKeyReceived = viewModel::onSharedKeyReceived)
                    } else {
                        DeviceListScreen(viewModel)
                    }
                }
            }
        }
    }
}
