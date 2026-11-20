package app.aivoo.tools.androidprojector.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import app.aivoo.tools.androidprojector.media.MediaManager

@Composable
fun VideoPlayerScreen(path: String, mediaManager: MediaManager, onBack: () -> Unit) {
    val player = remember { mediaManager.createPlayer() }
    BackHandler { onBack() }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(path) {
        isLoading = true
        errorMessage = null
        mediaManager.playFile(path)
        onDispose { mediaManager.release() }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsLoadingChanged(isLoadingEvent: Boolean) {
                isLoading = isLoadingEvent
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isLoading = false
                errorMessage = error.message ?: "Playback error"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    isLoading = false
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                        onBack()
                        true
                    } else {
                        false
                    }
                }
        )

        val message = errorMessage
        when {
            message != null -> MessageOverlay(message)
            isLoading -> LoadingOverlay()
        }
    }
}