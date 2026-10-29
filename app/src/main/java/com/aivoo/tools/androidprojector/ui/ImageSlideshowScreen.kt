package com.aivoo.tools.androidprojector.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File

@Composable
fun ImageSlideshowScreen(files: List<File>, onBack: () -> Unit) {
    var index by remember { mutableStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(index, files) {
        bitmap = files.getOrNull(index)?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    DisposableEffect(Unit) {
        onDispose { bitmap?.recycle() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    when (ev.key) {
                        Key.DirectionRight -> { if (index < files.size - 1) index++; true }
                        Key.DirectionLeft -> { if (index > 0) index--; true }
                        Key.Back, Key.Escape -> { onBack(); true }
                        else -> false
                    }
                } else false
            }
    ) {
        bitmap?.let { bmp ->
            Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } ?: Text("Loading...", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.Center))
        if (files.size > 1) {
            Text("${index + 1} / ${files.size}", modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp), color = androidx.compose.ui.graphics.Color.White, fontSize = 16.sp)
        }
    }
}

