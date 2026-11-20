package app.aivoo.tools.androidprojector

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.aivoo.tools.androidprojector.media.MediaManager
import app.aivoo.tools.androidprojector.media.ScreenShareManager
import app.aivoo.tools.androidprojector.R
import app.aivoo.tools.androidprojector.model.ContentType
import app.aivoo.tools.androidprojector.server.CaServer
import app.aivoo.tools.androidprojector.server.MediaServer
import app.aivoo.tools.androidprojector.ui.ErrorScreen
import app.aivoo.tools.androidprojector.ui.HomeScreen
import app.aivoo.tools.androidprojector.ui.ImageSlideshowScreen
import app.aivoo.tools.androidprojector.ui.PdfViewerScreen
import app.aivoo.tools.androidprojector.ui.ScreenShareScreen
import app.aivoo.tools.androidprojector.ui.StartScreen
import app.aivoo.tools.androidprojector.ui.UpdateDialog
import app.aivoo.tools.androidprojector.ui.VideoPlayerScreen
import app.aivoo.tools.androidprojector.ui.WebViewScreen
import app.aivoo.tools.androidprojector.util.NetworkUtils
import app.aivoo.tools.androidprojector.util.QrCodeUtils
import app.aivoo.tools.androidprojector.util.UpdateManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed class Content {
    object None : Content()
    data class Pdf(val file: File) : Content()
    data class Video(val file: File) : Content()
    data class Images(val files: List<File>) : Content()
    data class Url(val url: String) : Content()
    data class ScreenShare(val sessionId: String) : Content()
    data class Error(val message: String) : Content()
}

class MainActivity : ComponentActivity() {
    private var server: MediaServer? = null
    private var caServer: CaServer? = null
    private val mediaManager = MediaManager(this)
    private var screenShareManager: ScreenShareManager? = null

    private val imageFiles = mutableListOf<File>()
    private var currentVideoFile: File? = null
    private var currentPdfFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenShareManager = runCatching { ScreenShareManager(this) }
            .onFailure { Log.e("MainActivity", "ScreenShareManager init failed", it) }
            .getOrNull()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var ip by remember { mutableStateOf(NetworkUtils.getIpAddress(this@MainActivity)) }
                var port by remember { mutableStateOf(7071) }
                var content by remember { mutableStateOf<Content>(Content.None) }
                var status by rememberSaveable { mutableStateOf(getString(R.string.status_waiting)) }
                var serverStarted by remember { mutableStateOf(false) }
                var starting by remember { mutableStateOf(false) }
                var serverUrl by remember { mutableStateOf<String?>(null) }
                var caUrl by remember { mutableStateOf("") }
                var pairingPin by remember { mutableStateOf("") }
                var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                var updateInfo by remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }
                var pdfPage by rememberSaveable { mutableStateOf(0) }

                LaunchedEffect(Unit) {
                    val latest = UpdateManager.checkLatest()
                    if (latest != null && UpdateManager.isUpdateAvailable(this@MainActivity, latest)) {
                        updateInfo = latest
                    }
                }

                LaunchedEffect(content) {
                    if (content !is Content.Video) {
                        server?.currentFile = null
                        server?.currentPlaying = false
                        server?.currentPositionMs = 0L
                        server?.videoDurationMs = 0L
                        server?.videoEventId = (server?.videoEventId ?: 0L) + 1L
                        return@LaunchedEffect
                    }
                    while (isActive) {
                        server?.currentPositionMs = mediaManager.player?.currentPosition ?: 0L
                        server?.videoDurationMs = safeDuration()
                        delay(100)
                    }
                }

                DisposableEffect(Unit) {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            ip = NetworkUtils.getIpAddress(this@MainActivity)
                        }

                        override fun onLost(network: Network) {
                            ip = NetworkUtils.getIpAddress(this@MainActivity)
                        }
                    }
                    val request = NetworkRequest.Builder().build()
                    runCatching { cm?.registerNetworkCallback(request, callback) }
                    onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
                }

                LaunchedEffect(ip, serverStarted) {
                    val s = server ?: return@LaunchedEffect
                    if (serverStarted) {
                        val url = "https://$ip:$port/"
                        serverUrl = url
                        qrBitmap = QrCodeUtils.generate(url)
                    }
                }

                BackHandler(enabled = serverStarted && content !is Content.None) {
                    server?.endSession()
                    screenShareManager?.stop()
                    content = Content.None
                    server?.currentFile = null
                    server?.currentPlaying = false
                    server?.currentPositionMs = 0L
                    server?.videoDurationMs = 0L
                    server?.videoEventId = (server?.videoEventId ?: 0L) + 1L
                }

                if (!serverStarted) {
                    StartScreen(status = status, starting = starting, onStart = {
                        if (starting) return@StartScreen
                        starting = true
                        status = getString(R.string.status_starting_server)
                        try {
                            val selectedPort = findAvailablePort(port)
                            port = selectedPort
                            val s = MediaServer(applicationContext, selectedPort, { uploaded ->
                                runOnUiThread {
                                    val label = uploaded.displayName ?: uploaded.name
                                    status = getString(R.string.status_received, label)
                                    when (uploaded.type) {
                                        ContentType.PDF -> {
                                            currentPdfFile?.let { old ->
                                                server?.removeUploadedFile(old.name)
                                                old.delete()
                                            }
                                            currentPdfFile = File(uploaded.path)
                                            content = Content.Pdf(currentPdfFile!!)
                                            pdfPage = 0
                                            server?.pdfPage = 0
                                        }
                                        ContentType.VIDEO -> {
                                            currentVideoFile?.let { old ->
                                                server?.removeUploadedFile(old.name)
                                                old.delete()
                                            }
                                            currentVideoFile = File(uploaded.path)
                                            content = Content.Video(currentVideoFile!!)
                                            server?.videoEventId = (server?.videoEventId ?: 0L) + 1L
                                        }
                                        ContentType.IMAGE -> {
                                            imageFiles.add(File(uploaded.path))
                                            content = Content.Images(ArrayList(imageFiles))
                                        }
                                        ContentType.URL -> content = Content.Url(uploaded.path)
                                        app.aivoo.tools.androidprojector.model.ContentType.UNKNOWN -> {
                                            // Unknown files are downloads only. Never auto-install APKs.
                                            status = getString(R.string.status_file_shared, label)
                                        }
                                        else -> content = Content.Error(getString(R.string.unsupported_file))
                                    }
                                }
                            }, { listening ->
                                runOnUiThread {
                                    mediaManager.player?.volume = if (listening) 0f else 1f
                                }
                            }, { action ->
                                runOnUiThread {
                                    when (action) {
                                        "prev" -> if (pdfPage > 0) pdfPage--
                                        "next" -> if (pdfPage < (server?.pdfPageCount ?: Int.MAX_VALUE) - 1) pdfPage++
                                    }
                                    server?.pdfPage = pdfPage
                                }
                            }, { action ->
                                runOnUiThread {
                                    val pl = mediaManager.player ?: return@runOnUiThread
                                    when (action) {
                                        "play" -> pl.play()
                                        "pause" -> pl.pause()
                                        "seekback" -> { val np = (pl.currentPosition - 10000).coerceAtLeast(0); pl.seekTo(np); server?.currentPositionMs = np }
                                        "seekfwd" -> {
                                            val duration = pl.duration
                                            val np = if (duration > 0L) minOf(pl.currentPosition + 10000, duration) else pl.currentPosition + 10000
                                            pl.seekTo(np)
                                            server?.currentPositionMs = np
                                        }
                                        "replay" -> { pl.seekTo(0); pl.play(); server?.currentPositionMs = 0 }
                                        "stop" -> { pl.pause(); pl.seekTo(0); server?.currentPositionMs = 0 }
                                    }
                                    val newPlaying = when (action) {
                                        "play" -> true
                                        "pause" -> false
                                        "stop" -> false
                                        "replay" -> true
                                        else -> pl.isPlaying
                                    }
                                    if (action == "play" || action == "pause") server?.currentPositionMs = pl.currentPosition
                                    server?.currentPlaying = newPlaying
                                    server?.videoEventId = (server?.videoEventId ?: 0L) + 1L
                                }
                            }, {
                                runOnUiThread {
                                    when (val c = content) {
                                        is Content.Pdf -> {
                                            if (!c.file.exists()) {
                                                currentPdfFile = null
                                                content = Content.None
                                            }
                                        }
                                        is Content.Video -> {
                                            if (!c.file.exists() || server?.currentFile == null) {
                                                currentVideoFile = null
                                                content = Content.None
                                            }
                                        }
                                        is Content.Images -> {
                                            if (imageFiles.removeAll { !it.exists() }) {
                                                content = if (imageFiles.isEmpty()) {
                                                    Content.None
                                                } else {
                                                    Content.Images(ArrayList(imageFiles))
                                                }
                                            }
                                        }
                                        else -> {}
                                    }
                                    status = getString(R.string.status_files_updated)
                                }
                            }, { active ->
                                runOnUiThread {
                                    if (!active && content is Content.ScreenShare) {
                                        screenShareManager?.stop()
                                        content = Content.None
                                    }
                                    status = getString(if (active) R.string.status_remote_active else R.string.status_waiting)
                                }
                            }, {
                                runOnUiThread {
                                    mediaManager.player?.pause()
                                    content = Content.ScreenShare("active")
                                    status = getString(R.string.status_screen_share)
                                }
                            }, { offer ->
                                screenShareManager?.createAnswer(offer)
                            }, {
                                runOnUiThread {
                                    screenShareManager?.stop()
                                    if (content is Content.ScreenShare) {
                                        content = Content.None
                                    }
                                    status = getString(R.string.status_waiting)
                                }
                            }, {
                                if (screenShareManager == null) "unavailable"
                                else (screenShareManager?.lastError ?: screenShareManager?.lastStage ?: "ok")
                            })
                            server = s
                            mediaManager.onStateChanged = { playing, pos ->
                                server?.currentPositionMs = pos
                                server?.currentPlaying = playing
                                server?.videoDurationMs = safeDuration()
                                server?.videoEventId = (server?.videoEventId ?: 0L) + 1L
                            }
                            s.start()
                            val url = "https://$ip:$selectedPort/"
                            serverUrl = url
                            pairingPin = s.pairingPin
                            qrBitmap = QrCodeUtils.generate(url)
                            caServer = runCatching { CaServer(applicationContext).apply { start() } }.getOrNull()
                            caUrl = if (caServer != null) "http://$ip:8081/ca.crt" else ""
                            status = getString(R.string.status_server_running)
                            serverStarted = true
                        } catch (e: Exception) {
                            runCatching { server?.stop() }
                            server = null
                            serverUrl = null
                            caUrl = ""
                            pairingPin = ""
                            status = getString(R.string.status_server_failed, e.message ?: "")
                        } finally {
                            starting = false
                        }
                    })
                } else {
                    when (val c = content) {
                        is Content.None -> HomeScreen(
                            url = serverUrl ?: "https://$ip:$port",
                            caUrl = caUrl,
                            pairingPin = pairingPin,
                            status = status,
                            qrBitmap = qrBitmap,
                        )
                        is Content.Pdf -> key(c.file) {
                            PdfViewerScreen(
                                file = c.file,
                                pageIndex = pdfPage,
                                onPageChange = { pdfPage = it; server?.pdfPage = it },
                                onPageCount = { server?.pdfPageCount = it },
                                onBack = { content = Content.None }
                            )
                        }
                        is Content.Video -> key(c.file.absolutePath) {
                            VideoPlayerScreen(path = c.file.absolutePath, mediaManager = mediaManager, onBack = { content = Content.None })
                        }
                        is Content.Images -> ImageSlideshowScreen(files = c.files, onBack = { content = Content.None })
                        is Content.Url -> key(c.url) {
                            WebViewScreen(url = c.url)
                        }
                        is Content.ScreenShare -> {
                            val manager = screenShareManager
                            if (manager != null) {
                                ScreenShareScreen(
                                    renderer = manager.renderer,
                                    onBack = {
                                        server?.endSession()
                                        manager.stop()
                                        content = Content.None
                                    }
                                )
                            } else {
                                ErrorScreen(
                                    message = getString(R.string.screen_share_unavailable),
                                    onBack = {
                                        server?.endSession()
                                        content = Content.None
                                    }
                                )
                            }
                        }
                        is Content.Error -> key(c.message) {
                            ErrorScreen(message = c.message, onBack = { content = Content.None })
                        }
                    }
                }

                updateInfo?.let { info ->
                    UpdateDialog(
                        version = info.tagName,
                        onDownload = {
                            updateInfo = null
                            (this@MainActivity).lifecycleScope.launch {
                                try {
                                    val file = UpdateManager.download(this@MainActivity, info.downloadUrl)
                                    if (file != null) {
                                        UpdateManager.installApk(this@MainActivity, file)
                                        UpdateManager.markInstalled(this@MainActivity, info.tagName)
                                    }
                                } catch (e: Exception) {
                                    status = getString(R.string.status_update_failed, e.message ?: "")
                                }
                            }
                        },
                        onDismiss = { updateInfo = null },
                    )
                }
            }
        }
    }

    private fun findAvailablePort(preferred: Int): Int {
        return try {
            java.net.ServerSocket(preferred).use { preferred }
        } catch (_: Exception) {
            java.net.ServerSocket(0).use { it.localPort }
        }
    }

    private fun safeDuration(): Long {
        val duration = mediaManager.player?.duration ?: 0L
        return if (duration > 0L) duration else 0L
    }

    override fun onPause() {
        super.onPause()
        mediaManager.player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { server?.stop() } catch (_: Exception) {}
        try { caServer?.stop() } catch (_: Exception) {}
        caServer = null
        screenShareManager?.release()
        screenShareManager = null
        mediaManager.release()
        App.clearCache(this)
    }
}
