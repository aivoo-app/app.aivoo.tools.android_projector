package app.aivoo.tools.androidprojector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.aivoo.tools.androidprojector.media.MediaManager
import app.aivoo.tools.androidprojector.model.ContentType
import app.aivoo.tools.androidprojector.server.MediaServer
import app.aivoo.tools.androidprojector.ui.ErrorScreen
import app.aivoo.tools.androidprojector.ui.HomeScreen
import app.aivoo.tools.androidprojector.ui.ImageSlideshowScreen
import app.aivoo.tools.androidprojector.ui.PdfViewerScreen
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
    data class Error(val message: String) : Content()
}

class MainActivity : ComponentActivity() {
    private var server: MediaServer? = null
    private val mediaManager = MediaManager(this)

    private val imageFiles = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.clearCache(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val ip = NetworkUtils.getIpAddress(this)
        val port = 7071

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var content by remember { mutableStateOf<Content>(Content.None) }
                var status by remember { mutableStateOf("Waiting for upload...") }
                var serverStarted by remember { mutableStateOf(false) }
                var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                var updateInfo by remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }
                var pdfPage by remember { mutableStateOf(0) }
                var pdfZoom by remember { mutableStateOf(1f) }

                LaunchedEffect(Unit) {
                    val latest = UpdateManager.checkLatest()
                    if (latest != null && UpdateManager.isUpdateAvailable(this@MainActivity, latest)) {
                        updateInfo = latest
                    }
                }

                LaunchedEffect(content) {
                    while (isActive) {
                        if (content is Content.Video) {
                            server?.currentPositionMs = mediaManager.player?.currentPosition ?: 0L
                            server?.currentPlaying = mediaManager.player?.isPlaying ?: false
                            server?.videoDurationMs = mediaManager.player?.duration ?: 0L
                        }
                        delay(100)
                    }
                }

                if (!serverStarted) {
                    StartScreen(onStart = {
                        val s = MediaServer(this@MainActivity, port, { uploaded ->
                            runOnUiThread {
                                status = "Received: ${uploaded.name}"
                                when (uploaded.type) {
                                    ContentType.PDF -> {
                                        content = Content.Pdf(File(uploaded.path))
                                        pdfPage = 0
                                        pdfZoom = 1f
                                    }
                                    ContentType.VIDEO -> { content = Content.Video(File(uploaded.path)); server?.videoEventId = (server?.videoEventId ?: 0L) + 1L }
                                    ContentType.IMAGE -> {
                                        imageFiles.add(File(uploaded.path))
                                        content = Content.Images(ArrayList(imageFiles))
                                    }
                                    ContentType.URL -> content = Content.Url(uploaded.path)
                                    app.aivoo.tools.androidprojector.model.ContentType.UNKNOWN -> status = "File shared: ${uploaded.name}"
                                    else -> content = Content.Error("Unsupported or unknown file type")
                                }
                                server?.pdfPage = pdfPage
                                server?.pdfZoom = pdfZoom
                                server?.pdfPageCount = 0
                            }
                        }, { listening ->
                            mediaManager.player?.volume = if (listening) 0f else 1f
                            mediaManager.onStateChanged = { playing, pos ->
                                server?.currentPositionMs = pos
                                server?.currentPlaying = playing
                                server?.videoDurationMs = mediaManager.player?.duration ?: 0L
                                server?.videoEventId = (server?.videoEventId ?: 0L) + 1L
                            }
                        }, { action ->
                            runOnUiThread {
                                when (action) {
                                    "prev" -> if (pdfPage > 0) pdfPage--
                                    "next" -> if (pdfPage < (server?.pdfPageCount ?: Int.MAX_VALUE) - 1) pdfPage++
                                    "zoomin" -> pdfZoom = (pdfZoom * 1.25f).coerceAtMost(4f)
                                    "zoomout" -> pdfZoom = (pdfZoom / 1.25f).coerceAtLeast(0.5f)
                                }
                                server?.pdfPage = pdfPage
                                server?.pdfZoom = pdfZoom
                            }
                        }, { action ->
                            runOnUiThread {
                                val pl = mediaManager.player ?: return@runOnUiThread
                                when (action) {
                                    "play" -> pl.play()
                                    "pause" -> pl.pause()
                                    "seekback" -> pl.seekTo((pl.currentPosition - 10000).coerceAtLeast(0))
                                    "seekfwd" -> pl.seekTo(pl.currentPosition + 10000)
                                    "replay" -> { pl.seekTo(0); pl.play() }
                                    "stop" -> { pl.pause(); pl.seekTo(0) }
                                }
                            }
                        })
                        server = s
                        try {
                            s.start()
                            serverStarted = true
                            status = "Server running"
                            qrBitmap = QrCodeUtils.generate("http://$ip:$port")
                        } catch (e: Exception) {
                            status = "Server failed: ${e.message}"
                        }
                    })
                } else {
                    when (val c = content) {
                        is Content.None -> HomeScreen(ip = ip, port = port, status = status, qrBitmap = qrBitmap)
                        is Content.Pdf -> PdfViewerScreen(
                            file = c.file,
                            pageIndex = pdfPage,
                            zoom = pdfZoom,
                            onPageChange = { pdfPage = it; server?.pdfPage = it },
                            onZoomChange = { pdfZoom = it; server?.pdfZoom = it },
                            onPageCount = { server?.pdfPageCount = it },
                            onBack = { content = Content.None }
                        )
                        is Content.Video -> VideoPlayerScreen(path = c.file.absolutePath, mediaManager = mediaManager, onBack = { content = Content.None })
                        is Content.Images -> ImageSlideshowScreen(files = c.files, onBack = { content = Content.None })
                        is Content.Url -> WebViewScreen(url = c.url, onBack = { content = Content.None })
                        is Content.Error -> ErrorScreen(message = c.message, onBack = { content = Content.None })
                    }
                }

                updateInfo?.let { info ->
                    UpdateDialog(
                        version = info.tagName,
                        onDownload = {
                            updateInfo = null
                            (this@MainActivity).lifecycleScope.launch {
                                val file = UpdateManager.download(this@MainActivity, info.downloadUrl)
                                if (file != null) {
                                    UpdateManager.markInstalled(this@MainActivity, info.tagName)
                                    UpdateManager.installApk(this@MainActivity, file)
                                }
                            }
                        },
                        onDismiss = { updateInfo = null },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { server?.stop() } catch (_: Exception) {}
        mediaManager.release()
        App.clearCache(this)
    }
}
