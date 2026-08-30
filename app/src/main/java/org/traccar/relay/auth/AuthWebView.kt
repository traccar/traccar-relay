package org.traccar.relay.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.content.Context
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import java.net.URISyntaxException

object GoogleAuthUrlPolicy {

    private val allowedHosts = setOf(
        "accounts.google.com",
        "myaccount.google.com",
    )

    fun isAllowed(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in allowedHosts &&
            (uri.port == -1 || uri.port == 443) &&
            uri.userInfo == null
    } catch (_: URISyntaxException) {
        false
    }
}

object GoogleAuthCookieParser {

    fun findOAuthToken(cookies: String?): String? = cookies
        ?.split(';')
        ?.asSequence()
        ?.map { it.trim().split("=", limit = 2) }
        ?.firstOrNull { it.size == 2 && it[0] == "oauth_token" }
        ?.get(1)
        ?.takeIf { it.isNotBlank() }
}

@SuppressLint("SetJavaScriptEnabled")
fun WebView.configureForGoogleAuthentication(
    onBlockedNavigation: (String) -> Unit = {},
    onPageStarted: (WebView, String) -> Unit = { _, _ -> },
    onPageFinished: (WebView, String) -> Unit = { _, _ -> },
    onHttpError: (Int, String) -> Unit = { _, _ -> },
) {
    WebView.setWebContentsDebuggingEnabled(false)
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        safeBrowsingEnabled = true
        userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureForGoogleAuthentication, true)
    }

    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (GoogleAuthUrlPolicy.isAllowed(url)) {
                onPageStarted(view, url)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (GoogleAuthUrlPolicy.isAllowed(url)) {
                onPageFinished(view, url)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return true
            return if (GoogleAuthUrlPolicy.isAllowed(url)) {
                false
            } else {
                onBlockedNavigation(url)
                true
            }
        }

        @Deprecated("Deprecated in Android")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val value = url ?: return true
            return if (GoogleAuthUrlPolicy.isAllowed(value)) {
                false
            } else {
                onBlockedNavigation(value)
                true
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.cancel()
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            if (request?.isForMainFrame == true) {
                onHttpError(errorResponse?.statusCode ?: 0, request.url.toString())
            }
        }
    }
}

fun clearGoogleAuthenticationSession(context: Context) {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
    WebView(context).apply {
        clearCache(true)
        clearFormData()
        clearHistory()
        destroy()
    }
}
