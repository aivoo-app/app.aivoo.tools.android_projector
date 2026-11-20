package app.aivoo.tools.androidprojector.ui

import android.graphics.Bitmap
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WebViewScreen(url: String) {
    val webView = remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = canGoBack) {
        webView.value?.goBack()
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        canGoBack = view?.canGoBack() == true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        isLoading = true
                        errorMessage = null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        isLoading = false
                        canGoBack = view?.canGoBack() == true
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request == null || request.isForMainFrame) {
                            isLoading = false
                            errorMessage = "Unable to load page"
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.toString() ?: return false
                        return !isHttpUrl(target)
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return url != null && !isHttpUrl(url)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progress = newProgress
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                if (Build.VERSION.SDK_INT >= 26) {
                    settings.safeBrowsingEnabled = true
                }
                loadUrl(url)
                webView.value = this
            }
        },
        modifier = Modifier.fillMaxSize()
    )

    Box(modifier = Modifier.fillMaxSize()) {
        val message = errorMessage
        when {
            message != null -> MessageOverlay(message)
            isLoading -> LoadingOverlay(progress / 100f)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.value?.destroy()
            webView.value = null
        }
    }
}

private fun isHttpUrl(url: String): Boolean {
    return url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}