package org.traccar.relay.auth

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import org.traccar.relay.R

private const val LOGIN_URL = "https://accounts.google.com/EmbeddedSetup"
private const val COOKIE_CHECK_INTERVAL = 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    authenticationInProgress: Boolean,
    error: String?,
    onClose: () -> Unit,
    onTokenReceived: (email: String, token: String) -> Unit,
) {
    val handler = remember { Handler(Looper.getMainLooper()) }
    var found by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sign_in)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    var lastEmail = ""

                    fun checkForOAuthToken() {
                        if (found) return
                        val cookieManager = CookieManager.getInstance()
                        val token = GoogleAuthCookieParser.findOAuthToken(cookieManager.getCookie(LOGIN_URL))
                            ?: GoogleAuthCookieParser.findOAuthToken(
                                cookieManager.getCookie("https://accounts.google.com"),
                            )
                        if (token != null) {
                            found = true
                            cookieManager.flush()
                            onTokenReceived(lastEmail, token)
                            return
                        }
                        handler.postDelayed(::checkForOAuthToken, COOKIE_CHECK_INTERVAL)
                    }

                    WebView(context).apply {
                        webView = this
                        configureForGoogleAuthentication(
                            onPageFinished = { _, url ->
                                val email = url.toUri().getQueryParameter("Email")
                                if (!email.isNullOrBlank()) lastEmail = email
                                checkForOAuthToken()
                            },
                        )
                        loadUrl(LOGIN_URL)
                    }
                },
            )

            if (authenticationInProgress) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            error?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }
}
