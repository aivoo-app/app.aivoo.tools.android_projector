package app.aivoo.tools.androidprojector.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import app.aivoo.tools.androidprojector.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(url: String, pairingPin: String, status: String, qrBitmap: Bitmap?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.start_title), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.server_url, url), fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
        if (pairingPin.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Pairing code: $pairingPin", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        Text(status, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        qrBitmap?.let { bmp ->
            Image(bitmap = bmp.asImageBitmap(), contentDescription = stringResource(R.string.qr_content_description), modifier = Modifier.size(220.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.qr_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.upload_help), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


