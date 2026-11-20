package app.aivoo.tools.androidprojector.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aivoo.tools.androidprojector.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aivoo.tools.androidprojector.pdf.PdfRendererWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfViewerScreen(
    file: File,
    pageIndex: Int,
    onPageChange: (Int) -> Unit,
    onPageCount: (Int) -> Unit = {},
    onBack: () -> Unit,
) {
    val rendererState = remember(file) { mutableStateOf<PdfRendererWrapper?>(null) }
    val renderer = rendererState.value
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    val focusRequester = remember { FocusRequester() }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val renderWidth = remember(configuration, density) {
        with(density) { configuration.screenWidthDp.dp.toPx().toInt() }.coerceAtLeast(320).coerceAtMost(3840)
    }

    LaunchedEffect(file) {
        val opened = withContext(Dispatchers.IO) {
            runCatching { PdfRendererWrapper(file) }
        }
        opened
            .onSuccess {
                rendererState.value = it
                onPageCount(it.pageCount)
            }
            .onFailure {
                error = it.message ?: context.getString(R.string.pdf_open_failed)
            }
    }

    DisposableEffect(file) {
        onDispose {
            rendererState.value?.close()
            rendererState.value = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { bitmap?.recycle() }
    }

    val count = renderer?.pageCount ?: 0
    LaunchedEffect(renderer) {
        if (renderer != null) {
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(pageIndex) {
        scale = 1f
        offset = Offset.Zero
    }
    LaunchedEffect(pageIndex, renderer) {
        val r = renderer ?: return@LaunchedEffect
        val rendered = withContext(Dispatchers.IO) {
            runCatching { r.renderPage(pageIndex, renderWidth) }.getOrNull()
        }
        if (rendered != null) {
            bitmap = rendered
            error = null
        } else {
            error = context.getString(R.string.pdf_render_failed, pageIndex + 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    when (ev.key) {
                        Key.DirectionRight, Key.PageDown -> {
                            if (pageIndex < count - 1) onPageChange(pageIndex + 1)
                            true
                        }
                        Key.DirectionLeft, Key.PageUp -> {
                            if (pageIndex > 0) onPageChange(pageIndex - 1)
                            true
                        }
                        Key.DirectionCenter -> {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2f
                                offset = Offset.Zero
                            }
                            true
                        }
                        Key.Back, Key.Escape -> {
                            onBack()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val message = error
        when {
            message != null -> MessageOverlay(message)
            renderer == null -> LoadingOverlay()
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset += pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2f
                                offset = Offset.Zero
                            }
                        })
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .clipToBounds()
            )
            else -> Text(stringResource(R.string.loading), color = Color.White)
        }

        if (renderer != null && count > 0) {
            Text(
                stringResource(R.string.page_format, pageIndex + 1, count),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 14.sp,
                color = Color.White
            )
        }
    }
}