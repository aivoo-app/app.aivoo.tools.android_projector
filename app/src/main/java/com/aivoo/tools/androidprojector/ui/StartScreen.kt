package app.aivoo.tools.androidprojector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
fun StartScreen(status: String = "", starting: Boolean = false, onStart: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ProjectorBackground {
        ProjectorCard(modifier = Modifier.width(620.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 40.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.start_title),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.developed_by),
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.company),
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.website),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        status,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(36.dp))
                Button(
                    onClick = onStart,
                    enabled = !starting,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .width(260.dp)
                        .height(58.dp),
                ) {
                    Text(
                        stringResource(if (starting) R.string.starting else R.string.start_server),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}