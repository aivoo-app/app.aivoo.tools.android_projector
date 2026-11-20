package app.aivoo.tools.androidprojector.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aivoo.tools.androidprojector.R

@Composable
fun HomeScreen(url: String, pairingPin: String, status: String, qrBitmap: Bitmap?) {
    ProjectorBackground {
        ProjectorCard(modifier = Modifier.width(700.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 44.dp, vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.start_title),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        stringResource(R.string.server_url, url),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (pairingPin.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.pairing_code, pairingPin),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    status,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (qrBitmap != null) {
                    Spacer(Modifier.height(20.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.qr_content_description),
                            modifier = Modifier
                                .padding(12.dp)
                                .size(210.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.qr_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}