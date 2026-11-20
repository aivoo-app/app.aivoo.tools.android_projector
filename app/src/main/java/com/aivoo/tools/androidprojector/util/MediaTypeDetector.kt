package app.aivoo.tools.androidprojector.util

import app.aivoo.tools.androidprojector.model.ContentType
import java.io.File
import java.io.FileInputStream

object MediaTypeDetector {
    fun detect(file: File): ContentType {
        return try {
            val bytes = ByteArray(512)
            val read = FileInputStream(file).use { it.read(bytes) }
            if (read <= 0) return ContentType.UNKNOWN
            if (read >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()) return ContentType.PDF
            if (read >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return ContentType.IMAGE
            if (read >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return ContentType.IMAGE
            if (read >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return ContentType.IMAGE
            if (read >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return ContentType.IMAGE
            if (read >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()) return ContentType.IMAGE
            if (read >= 12 && bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() && bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()) {
                val brand = String(bytes, 8, 4, Charsets.ISO_8859_1)
                if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")) {
                    return ContentType.IMAGE
                }
            }
            if (read >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()) return ContentType.VIDEO
            if (read >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() && bytes[8] == 0x41.toByte() && bytes[9] == 0x56.toByte() && bytes[10] == 0x49.toByte()) return ContentType.VIDEO
            if (read >= 188 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte()) return ContentType.VIDEO
            val head = String(bytes, 0, read, Charsets.ISO_8859_1)
            if (head.contains("ftyp") || head.contains("moov") || head.contains("mdat")) return ContentType.VIDEO
            ContentType.UNKNOWN
        } catch (_: Exception) {
            ContentType.UNKNOWN
        }
    }
}