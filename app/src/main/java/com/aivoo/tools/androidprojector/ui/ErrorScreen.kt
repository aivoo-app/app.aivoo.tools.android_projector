package app.aivoo.tools.androidprojector.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aivoo.tools.androidprojector.R

@Composable
fun ErrorScreen(message: String, onBack: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ProjectorBackground {
        ProjectorCard(modifier = Modifier.width(560.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.error_title),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    message,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = onBack,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .width(200.dp)
                        .height(54.dp),
                ) {
                    Text(stringResource(R.string.back), fontSize = 16.sp)
                }
            }
        }
    }
}