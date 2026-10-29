package app.aivoo.tools.androidprojector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StartScreen(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Android TV Projector", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        Text("Developed by Aimon Islam", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Company: AIVOO", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("Website: https://aivoo.app", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(40.dp))
        Button(onClick = onStart, modifier = Modifier.width(200.dp).height(56.dp)) {
            Text("Start Server", fontSize = 18.sp)
        }
    }
}

