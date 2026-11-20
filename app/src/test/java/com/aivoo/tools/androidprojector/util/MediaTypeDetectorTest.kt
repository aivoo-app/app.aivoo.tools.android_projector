package app.aivoo.tools.androidprojector.util

import app.aivoo.tools.androidprojector.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MediaTypeDetectorTest {
    private fun tempFile(bytes: ByteArray): File {
        val file = File.createTempFile("media-type-test", ".bin")
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    @Test
    fun detectsPdf() {
        assertEquals(ContentType.PDF, MediaTypeDetector.detect(tempFile("%PDF-1.7\n".toByteArray())))
    }

    @Test
    fun detectsImages() {
        assertEquals(ContentType.IMAGE, MediaTypeDetector.detect(tempFile(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))))
        assertEquals(ContentType.IMAGE, MediaTypeDetector.detect(tempFile(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))))
        assertEquals(ContentType.IMAGE, MediaTypeDetector.detect(tempFile("GIF89a".toByteArray())))
        assertEquals(ContentType.IMAGE, MediaTypeDetector.detect(tempFile("BM".toByteArray().plus(ByteArray(30)))))
        assertEquals(ContentType.IMAGE, MediaTypeDetector.detect(tempFile("RIFF".toByteArray().plus(ByteArray(4)).plus("WEBP".toByteArray()))))
    }

    @Test
    fun detectsHeicAsImage() {
        val bytes = ByteArray(32)
        bytes[4] = 'f'.code.toByte()
        bytes[5] = 't'.code.toByte()
        bytes[6] = 'y'.code.toByte()
        bytes[7] = 'p'.code.toByte()
        bytes[8] = 'h'.code.toByte()
        bytes[9] = 'e'.code.toByte()
        bytes[10] = 'i'.code.toByte()
        bytes[11] = 'c'.code.toByte()
        assertEquals(ContentType.IMAGE, MediaTypeDetector.detect(tempFile(bytes)))
    }

    @Test
    fun detectsVideos() {
        assertEquals(ContentType.VIDEO, MediaTypeDetector.detect(tempFile(byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()))))
        val mp4 = ByteArray(32)
        mp4[4] = 'f'.code.toByte()
        mp4[5] = 't'.code.toByte()
        mp4[6] = 'y'.code.toByte()
        mp4[7] = 'p'.code.toByte()
        assertEquals(ContentType.VIDEO, MediaTypeDetector.detect(tempFile(mp4)))
        val ts = ByteArray(189)
        ts[0] = 0x47.toByte()
        ts[188] = 0x47.toByte()
        assertEquals(ContentType.VIDEO, MediaTypeDetector.detect(tempFile(ts)))
    }

    @Test
    fun unknownForText() {
        assertEquals(ContentType.UNKNOWN, MediaTypeDetector.detect(tempFile("hello world".toByteArray())))
    }
}