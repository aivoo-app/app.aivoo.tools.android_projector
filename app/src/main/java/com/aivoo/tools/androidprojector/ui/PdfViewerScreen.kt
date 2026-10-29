package com.aivoo.tools.androidprojector.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aivoo.tools.androidprojector.pdf.PdfRendererWrapper
import java.io.File

@Composable
fun PdfViewerScreen(file: File, onBack: () -> Unit) {
    val renderer = remember { PdfRendererWrapper(file) }
    DisposableEffect(Unit) { onDispose { renderer.close() } }
    var pageIndex by remember { mutableStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

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
                            if (pageIndex < renderer.pageCount - 1) pageIndex++
                            true
                        }
                        Key.DirectionLeft, Key.PageUp -> {
                            if (pageIndex > 0) pageIndex--
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
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = { if (pageIndex > 0) pageIndex-- }, enabled = pageIndex > 0) { Text("Prev") }
            Text("Page ${pageIndex + 1} / ${renderer.pageCount}", color = androidx.compose.ui.graphics.Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterVertically))
            Button(onClick = { if (pageIndex < renderer.pageCount - 1) pageIndex++ }, enabled = pageIndex < renderer.pageCount - 1) { Text("Next") }
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
