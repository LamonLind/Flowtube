package io.github.aedev.flow.ui.components.integrations

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val GOOGLE_LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"
private const val YOUTUBE_HOST = "youtube.com"
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun GoogleSignInWebView(
    onSignedIn: () -> Unit,
    onFailure: () -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_USER_AGENT
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient =
                    object : WebViewClient() {
                        private var completed = false

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            if (isYouTubeUrl(url) && !completed) {
                                completed = true
                                CookieManager.getInstance().flush()
                                onSignedIn()
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) onFailure()
                        }
                    }
                loadUrl(GOOGLE_LOGIN_URL)
            }
        },
        update = {},
    )
}

private fun isYouTubeUrl(url: String): Boolean =
    runCatching {
        Uri.parse(url).host?.lowercase()?.let { it == YOUTUBE_HOST || it.endsWith(".$YOUTUBE_HOST") } == true
    }.getOrDefault(false)
