package com.aivoo.tools.androidprojector.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

class MediaManager(private val context: Context) {
    var player: ExoPlayer? = null
        private set
    var onStateChanged: ((Boolean, Long) -> Unit)? = null

    fun createPlayer(): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(LiveAudioProcessor()))
                    .build()
            }
        }
        val p = ExoPlayer.Builder(context).setRenderersFactory(renderersFactory).build()
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
        p.setMediaItem(MediaItem.fromUri("file://$path"))
        p.prepare()
        p.play()
    }

    fun release() {
        player?.release()
        player = null
    }
}
