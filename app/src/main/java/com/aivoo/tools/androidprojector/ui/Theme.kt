package app.aivoo.tools.androidprojector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ProjectorColors = darkColorScheme(
    primary = Color(0xFF818CF8),
    onPrimary = Color(0xFF0B1020),
    secondary = Color(0xFF22D3EE),
    onSecondary = Color(0xFF0B1020),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFF87171),
)

@Composable
fun ProjectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ProjectorColors, content = content)
}

@Composable
fun ProjectorBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1E293B), Color(0xFF0B1020)),
                    radius = 1200f,
                ),
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun ProjectorCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 18.dp,
        content = content,
    )
}
@Composable
fun LoadingOverlay(progress: Float? = null) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun MessageOverlay(message: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        ProjectorCard(modifier = Modifier.width(520.dp)) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.Text(
                    message,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
