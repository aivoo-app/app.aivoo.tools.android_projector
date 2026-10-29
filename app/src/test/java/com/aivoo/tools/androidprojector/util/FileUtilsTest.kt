package com.aivoo.tools.androidprojector.util

import com.aivoo.tools.androidprojector.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class FileUtilsTest {
    @Test
    fun typeFromName_pdf() = assertEquals(ContentType.PDF, FileUtils.typeFromName("a.pdf"))
    @Test
    fun typeFromName_video() = assertEquals(ContentType.VIDEO, FileUtils.typeFromName("v.MP4"))
    @Test
    fun typeFromName_image() = assertEquals(ContentType.IMAGE, FileUtils.typeFromName("img.PNG"))
    @Test
    fun typeFromName_unknown() = assertEquals(ContentType.UNKNOWN, FileUtils.typeFromName("x.txt"))
    @Test
    fun typeFromMime() {
        assertEquals(ContentType.PDF, FileUtils.typeFromMime("application/pdf"))
        assertEquals(ContentType.VIDEO, FileUtils.typeFromMime("video/mp4"))
        assertEquals(ContentType.IMAGE, FileUtils.typeFromMime("image/jpeg"))
        assertEquals(ContentType.UNKNOWN, FileUtils.typeFromMime(null))
    }
}
