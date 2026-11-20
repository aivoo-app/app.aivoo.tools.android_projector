@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.aivoo.tools.androidprojector.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class MediaManager(private val context: Context) {
    var player: ExoPlayer? = null
        private set
    var onStateChanged: ((Boolean, Long) -> Unit)? = null

    fun createPlayer(): ExoPlayer {
        player?.let { return it }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val p = ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onStateChanged?.invoke(isPlaying, p.currentPosition)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                onStateChanged?.invoke(p.isPlaying, p.currentPosition)
            }
            override fun onPositionDiscontinuity(reason: Int) {
                onStateChanged?.invoke(p.isPlaying, p.currentPosition)
            }
        })
        player = p
        return p
    }

    fun playFile(path: String) {
        val p = player ?: createPlayer()
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        p.prepare()
        p.play()
    }

    fun release() {
        val p = player ?: return
        p.release()
        player = null
    }
}
