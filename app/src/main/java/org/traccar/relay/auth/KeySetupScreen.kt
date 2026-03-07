package org.traccar.relay.auth

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import org.traccar.relay.R
import org.json.JSONObject
import org.traccar.relay.proto.EncryptionUnlockRequestExtras
import org.traccar.relay.proto.SecurityDomain
import java.util.UUID

private const val TAG = "KeySetupScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeySetupScreen(onSharedKeyReceived: (ByteArray) -> Unit) {
    val extras = EncryptionUnlockRequestExtras(
        operation = 1,
        securityDomain = SecurityDomain(name = "finder_hw", unknown = 0),
        sessionId = UUID.randomUUID().toString(),
    )
    val kdi = Base64.encodeToString(extras.encode(), Base64.DEFAULT or Base64.NO_WRAP)
    val unlockUrl = "https://accounts.google.com/encryption/unlock/android?kdi=$kdi"

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.key_setup)) }) },
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

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun setVaultSharedKeys(@Suppress("UNUSED_PARAMETER") str: String, vaultKeys: String) {
                            try {
                                val json = JSONObject(vaultKeys)
                                val finderArray = json.optJSONArray("finder_hw")
                                    ?: throw Exception("No finder_hw key in vault")
                                if (finderArray.length() > 0) {
                                    val entry = finderArray.getJSONObject(0)
                                    val keyObj = entry.getJSONObject("key")
                                    val keyBytes = ByteArray(keyObj.length())
                                    for (j in 0 until keyObj.length()) {
                                        keyBytes[j] = keyObj.getInt(j.toString()).toByte()
                                    }
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
                    }, "mm")

                    var navigatedToUnlock = false

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!navigatedToUnlock && url != null && url.contains("myaccount.google.com")) {
                                navigatedToUnlock = true
                                view?.loadUrl(unlockUrl)
                            }
                        }
                    }

                    loadUrl("https://accounts.google.com/")
                }
            },
        )
    }
}
