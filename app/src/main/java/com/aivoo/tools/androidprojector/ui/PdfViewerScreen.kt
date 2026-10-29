package app.aivoo.tools.androidprojector.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aivoo.tools.androidprojector.pdf.PdfRendererWrapper
import java.io.File

@Composable
fun PdfViewerScreen(
    file: File,
    pageIndex: Int,
    onPageChange: (Int) -> Unit,
    onPageCount: (Int) -> Unit = {},
    onBack: () -> Unit,
) {
    val renderer = remember { PdfRendererWrapper(file) }
    DisposableEffect(Unit) { onDispose { renderer.close() } }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(renderer) { onPageCount(renderer.pageCount) }
    LaunchedEffect(pageIndex) {
        bitmap = renderer.renderPage(pageIndex, 1920)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    when (ev.key) {
                        Key.DirectionRight, Key.PageDown -> {
                            if (pageIndex < renderer.pageCount - 1) onPageChange(pageIndex + 1)
                            true
                        }
                        Key.DirectionLeft, Key.PageUp -> {
                            if (pageIndex > 0) onPageChange(pageIndex - 1)
                            true
                        }
                        Key.Back, Key.Escape -> { onBack(); true }
                        else -> false
                    }
                } else false
            }
    ) {
        bitmap?.let { bmp ->
            Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } ?: Text("Loading...", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.Center))
        Text(
            "Page ${pageIndex + 1} / ${renderer.pageCount}",
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            fontSize = 14.sp,
            color = androidx.compose.ui.graphics.Color.White
        )
    }
}
