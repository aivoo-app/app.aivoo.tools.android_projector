package app.aivoo.tools.androidprojector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.aivoo.tools.androidprojector.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StartScreen(status: String = "", starting: Boolean = false, onStart: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.start_title), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.developed_by), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.company), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.website), fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        if (status.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(status, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onStart,
            enabled = !starting,
            modifier = Modifier
                .focusRequester(focusRequester)
                .width(200.dp)
                .height(56.dp)
        ) {
            Text(stringResource(if (starting) R.string.starting else R.string.start_server), fontSize = 18.sp)
        }
    }
}

