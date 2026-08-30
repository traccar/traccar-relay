package org.traccar.relay.auth

import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.traccar.relay.R
import org.traccar.relay.proto.EncryptionUnlockRequestExtras
import org.traccar.relay.proto.SecurityDomain
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID

private const val TAG = "KeySetupScreen"
private const val KEY_BRIDGE_NAME = "mm"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeySetupScreen(
    webLoginUrl: String?,
    loading: Boolean,
    error: String?,
    onWebLoginUrlRequired: (String) -> Unit,
    onClose: () -> Unit,
    onSharedKeyReceived: (ByteArray) -> Unit,
) {
    val unlockUrl = remember {
        val extras = EncryptionUnlockRequestExtras(
            operation = 1,
            securityDomain = SecurityDomain(name = "finder_hw", unknown = 0),
            sessionId = UUID.randomUUID().toString(),
        )
        val kdi = Base64.encodeToString(
            extras.encode(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        Uri.parse("https://accounts.google.com/encryption/unlock/android")
            .buildUpon()
            .appendQueryParameter("kdi", kdi)
            .build()
            .toString()
    }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(unlockUrl) {
        onWebLoginUrlRequired(unlockUrl)
    }

    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            webView?.apply {
                removeJavascriptInterface(KEY_BRIDGE_NAME)
                stopLoading()
                destroy()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.key_setup)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (webLoginUrl != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                    webView = this
                    val bridge = object {
                        @JavascriptInterface
                        fun setVaultSharedKeys(
                            @Suppress("UNUSED_PARAMETER") ignored: String,
                            vaultKeys: String,
                        ) {
                            try {
                                val keyBytes = parseFinderSharedKey(vaultKeys)
                                handler.post {
                                    removeJavascriptInterface(KEY_BRIDGE_NAME)
                                    onSharedKeyReceived(keyBytes)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing vault keys", e)
                            }
                        }

                        @JavascriptInterface
                        fun closeView() {
                            Log.d(TAG, "closeView called")
                        }
                    }

                    configureForGoogleAuthentication(
                        onHttpError = { statusCode, url ->
                            Log.e(TAG, "Google HTTP $statusCode at ${safeUrlForLog(url)}")
                        },
                    )
                    addJavascriptInterface(bridge, KEY_BRIDGE_NAME)
                    loadUrl(webLoginUrl)
                        }
                    },
                )
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
            }
            error?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }
}

private fun parseFinderSharedKey(vaultKeys: String): ByteArray {
    val finderKeys = JSONObject(vaultKeys).optJSONArray("finder_hw")
        ?: throw IllegalArgumentException("No finder_hw key in vault")
    require(finderKeys.length() > 0) { "No finder_hw key in vault" }
    val key = finderKeys.getJSONObject(0).getJSONObject("key")
    return ByteArray(key.length()) { index -> key.getInt(index.toString()).toByte() }
}

private fun safeUrlForLog(url: String): String = try {
    val uri = URI(url)
    "${uri.scheme}://${uri.host}${uri.path.orEmpty()}"
} catch (_: URISyntaxException) {
    "invalid-url"
}
