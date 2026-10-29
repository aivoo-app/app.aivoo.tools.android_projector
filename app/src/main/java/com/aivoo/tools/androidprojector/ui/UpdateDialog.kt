package com.aivoo.tools.androidprojector.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun UpdateDialog(
    version: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update available") },
        text = { Text("A new version ($version) is available. Download and install now?") },
        confirmButton = { Button(onClick = onDownload) { Text("Update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}
