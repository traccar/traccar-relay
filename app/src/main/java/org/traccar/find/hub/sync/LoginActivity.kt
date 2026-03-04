package org.traccar.find.hub.sync

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var found = false

    companion object {
        private const val TAG = "LoginActivity"
        private const val LOGIN_URL = "https://accounts.google.com/EmbeddedSetup"
        private const val COOKIE_CHECK_INTERVAL = 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "Page finished: $url")
                checkForOAuthToken()
            }
        }

        webView.loadUrl(LOGIN_URL)
    }

    private fun checkForOAuthToken() {
        if (found) return
        val cookies = CookieManager.getInstance().getCookie("https://accounts.google.com")
        if (cookies != null) {
            val token = cookies.split("; ")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "oauth_token" }
                ?.get(1)
            if (token != null) {
                found = true
                Log.i(TAG, "OAuth token retrieved: $token")
                return
            }
        }
        handler.postDelayed({ checkForOAuthToken() }, COOKIE_CHECK_INTERVAL)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
