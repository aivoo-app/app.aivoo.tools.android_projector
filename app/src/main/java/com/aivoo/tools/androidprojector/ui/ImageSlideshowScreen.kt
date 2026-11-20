package app.aivoo.tools.androidprojector.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ImageSlideshowScreen(files: List<File>, onBack: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val targetWidth = remember(configuration, density) {
        with(density) { configuration.screenWidthDp.dp.toPx().toInt() }.coerceAtLeast(320).coerceAtMost(3840)
    }
    val targetHeight = remember(configuration, density) {
        with(density) { configuration.screenHeightDp.dp.toPx().toInt() }.coerceAtLeast(240).coerceAtMost(3840)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(files) {
        if (index >= files.size) {
            index = (files.size - 1).coerceAtLeast(0)
        }
    }

    LaunchedEffect(index, files, targetWidth, targetHeight) {
        bitmap = null
        error = null
        val file = files.getOrNull(index)
        if (file == null) {
            error = context.getString(R.string.image_not_found)
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) {
            runCatching { decodeSampled(file, targetWidth, targetHeight) }.getOrNull()
        }
        if (decoded == null) {
            error = context.getString(R.string.image_decode_failed)
            return@LaunchedEffect
        }
        val rotated = withContext(Dispatchers.IO) {
            runCatching { applyExifRotation(decoded, file) }.getOrDefault(decoded)
        }
        bitmap = rotated
    }

    DisposableEffect(Unit) {
        onDispose { bitmap?.recycle() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    when (ev.key) {
                        Key.DirectionRight -> {
                            if (index < files.size - 1) index++
                            true
                        }
                        Key.DirectionLeft -> {
                            if (index > 0) index--
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
            message != null -> Text(message, color = Color.White)
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            else -> Text(stringResource(R.string.loading), color = Color.White)
        }

        if (files.size > 1) {
            Text(
                stringResource(R.string.image_index, index + 1, files.size),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}

private fun decodeSampled(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (sample < 16 &&
        bounds.outWidth / (sample * 2) >= targetWidth &&
        bounds.outHeight / (sample * 2) >= targetHeight
    ) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

@Suppress("DEPRECATION")
private fun applyExifRotation(source: Bitmap, file: File): Bitmap {
    val degrees = try {
        when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) {
        0f
    }
    if (degrees == 0f) return source
    val matrix = Matrix().apply { postRotate(degrees) }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (rotated !== source) source.recycle()
    return rotated
}