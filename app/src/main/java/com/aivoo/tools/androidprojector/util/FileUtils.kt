package com.aivoo.tools.androidprojector.util

import com.aivoo.tools.androidprojector.model.ContentType
import java.io.File

object FileUtils {
    fun typeFromName(name: String): ContentType {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> ContentType.PDF
            "mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp" -> ContentType.VIDEO
            "jpg", "jpeg", "png", "gif", "bmp", "webp" -> ContentType.IMAGE
            else -> ContentType.UNKNOWN
        }
    }

    fun typeFromMime(mime: String?): ContentType {
        if (mime == null) return ContentType.UNKNOWN
        val m = mime.lowercase()
        return when {
            m.contains("pdf") -> ContentType.PDF
            m.startsWith("video/") -> ContentType.VIDEO
            m.startsWith("image/") -> ContentType.IMAGE
            m.startsWith("text/") -> ContentType.URL
            else -> ContentType.UNKNOWN
        }
    }

    fun safeDelete(file: File?) {
        try { file?.delete() } catch (_: Exception) {}
    }
}
