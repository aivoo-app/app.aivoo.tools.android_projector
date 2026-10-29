package com.aivoo.tools.androidprojector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aivoo.tools.androidprojector.media.MediaManager
import com.aivoo.tools.androidprojector.model.ContentType
import com.aivoo.tools.androidprojector.server.MediaServer
import com.aivoo.tools.androidprojector.ui.ErrorScreen
import com.aivoo.tools.androidprojector.ui.HomeScreen
import com.aivoo.tools.androidprojector.ui.ImageSlideshowScreen
import com.aivoo.tools.androidprojector.ui.PdfViewerScreen
import com.aivoo.tools.androidprojector.ui.StartScreen
import com.aivoo.tools.androidprojector.ui.UpdateDialog
import com.aivoo.tools.androidprojector.ui.VideoPlayerScreen
import com.aivoo.tools.androidprojector.ui.WebViewScreen
import com.aivoo.tools.androidprojector.util.NetworkUtils
import com.aivoo.tools.androidprojector.util.QrCodeUtils
import com.aivoo.tools.androidprojector.util.UpdateManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                var downloading by remember { mutableStateOf(false) }

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
                                    ContentType.PDF -> content = Content.Pdf(File(uploaded.path))
                                    ContentType.VIDEO -> content = Content.Video(File(uploaded.path))
                                    ContentType.IMAGE -> {
                                        imageFiles.add(File(uploaded.path))
                                        content = Content.Images(ArrayList(imageFiles))
                                    }
                                    ContentType.URL -> content = Content.Url(uploaded.path)
                                    else -> content = Content.Error("Unsupported or unknown file type")
                                }
                            }
                        }, { listening ->
                            mediaManager.player?.volume = if (listening) 0f else 1f
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
                        is Content.Pdf -> PdfViewerScreen(file = c.file, onBack = { content = Content.None })
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
                            downloading = true
                            (this@MainActivity).lifecycleScope.launch {
                                val file = UpdateManager.download(this@MainActivity, info.downloadUrl)
                                downloading = false
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
