package app.aivoo.tools.androidprojector.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import app.aivoo.tools.androidprojector.media.MediaManager

@Composable
fun VideoPlayerScreen(path: String, mediaManager: MediaManager, onBack: () -> Unit) {
    val player = remember { mediaManager.createPlayer() }

    DisposableEffect(path) {
        mediaManager.playFile(path)
        onDispose { mediaManager.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                useController = true
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Back) {
                    onBack(); true
                } else false
            }
    )
}

