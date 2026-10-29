package app.aivoo.tools.androidprojector.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

object AudioBroadcaster {
    @Volatile var sampleRate: Int = 44100
    @Volatile var channelCount: Int = 2

    private val clients = mutableListOf<BlockingQueue<ByteArray>>()
    private val lock = Any()

    fun setFormat(sr: Int, ch: Int) {
        sampleRate = sr
        channelCount = ch
    }

    fun register(): BlockingQueue<ByteArray> {
        val q = LinkedBlockingQueue<ByteArray>(16)
        synchronized(lock) { clients.add(q) }
        return q
    }

    fun unregister(q: BlockingQueue<ByteArray>) {
        synchronized(lock) { clients.remove(q) }
    }

    fun push(data: ByteArray) {
        synchronized(lock) {
            for (q in clients) {
                if (!q.offer(data)) {
                    q.poll()
                    q.offer(data)
                }
            }
        }
    }
}

class LiveAudioProcessor : AudioProcessor {
    private var active = false
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        active = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        if (active) {
            AudioBroadcaster.setFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (active && inputBuffer.hasRemaining()) {
            val dup = inputBuffer.duplicate()
            val bytes = ByteArray(dup.remaining())
            dup.get(bytes)
            AudioBroadcaster.push(bytes)
        }
        outputBuffer = inputBuffer
    }

    override fun getOutput(): ByteBuffer {
        val b = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return b
    }

    override fun isEnded(): Boolean = false

    override fun queueEndOfStream() {}

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
    }

    override fun reset() {
        active = false
        outputBuffer = AudioProcessor.EMPTY_BUFFER
    }
}
