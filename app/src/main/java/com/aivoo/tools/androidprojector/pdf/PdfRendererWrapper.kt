package app.aivoo.tools.androidprojector.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

class PdfRendererWrapper(private val file: File) {
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    var pageCount: Int = 0
        private set

    init {
        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(pfd!!)
        pageCount = renderer!!.pageCount
    }

    fun renderPage(index: Int, width: Int = 0, height: Int = 0): Bitmap? {
        val r = renderer ?: return null
        if (index < 0 || index >= r.pageCount) return null
        return try {
            val page = r.openPage(index)
            try {
                if (page.width <= 0 || page.height <= 0) return null
                val scale = if (width > 0) width.toFloat() / page.width else 1f
                var w = if (width > 0) width else page.width
                var h = if (height > 0) height else (page.height * scale).toInt().coerceAtLeast(1)
                val maxSide = 4096
                val maxPixels = maxSide.toLong() * maxSide.toLong()
                if (w.toLong() * h.toLong() > maxPixels) {
                    val shrink = kotlin.math.sqrt(maxPixels.toDouble() / (w.toLong() * h.toLong())).toFloat()
                    w = (w * shrink).toInt().coerceAtLeast(1)
                    h = (h * shrink).toInt().coerceAtLeast(1)
                }
                val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun close() {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        renderer = null
        pfd = null
    }
}