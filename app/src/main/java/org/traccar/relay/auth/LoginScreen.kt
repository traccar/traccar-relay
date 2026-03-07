package org.traccar.relay.auth

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.core.net.toUri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import org.traccar.relay.R

private const val LOGIN_URL = "https://accounts.google.com/EmbeddedSetup"
private const val COOKIE_CHECK_INTERVAL = 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onTokenReceived: (email: String, token: String) -> Unit) {
    val handler = remember { Handler(Looper.getMainLooper()) }
    val found = remember { mutableListOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.sign_in)) }) },
    ) { padding ->
        AndroidView(
            modifier = Modifier.padding(padding),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    var lastEmail = ""

                    fun checkForOAuthToken() {
                        if (found[0]) return
                        val cookies = CookieManager.getInstance().getCookie("https://accounts.google.com")
                        if (cookies != null) {
                            val token = cookies.split("; ")
                                .map { it.split("=", limit = 2) }
                                .firstOrNull { it.size == 2 && it[0] == "oauth_token" }
                                ?.get(1)
                            if (token != null) {
                                found[0] = true
                                onTokenReceived(lastEmail, token)
                                return
                            }
                        }
                        handler.postDelayed({ checkForOAuthToken() }, COOKIE_CHECK_INTERVAL)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            url?.let { u ->
                                val emailParam = u.toUri().getQueryParameter("Email")
                                if (!emailParam.isNullOrEmpty()) {
                                    lastEmail = emailParam
                                }
                            }
                            checkForOAuthToken()
                        }
                    }

                    loadUrl(LOGIN_URL)
                }
            },
        )
    }
}
