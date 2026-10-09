package dev.aliziwa.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.domain.ExtensionRules
import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.util.Format

/**
 * In-app due alert. Shown automatically whenever `DueEngine.currentAlert` is non-null, and also as
 * the `alert/<id>` route when the app is opened from a notification.
 */
@Composable
fun DueAlertDialog(
    alert: DueAlert,
    nowMillis: Long,
    onDone: () -> Unit,
    onExtend: (Int) -> Unit,
    onDismissTask: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var customOpen by remember(alert.taskId) { mutableStateOf(false) }
    var customText by remember(alert.taskId) { mutableStateOf("") }
    var confirmRecurringDelete by remember(alert.taskId) { mutableStateOf(false) }

    if (confirmRecurringDelete) {
        RecurringDeleteConfirmDialog(
            title = alert.title,
            onConfirm = { confirmRecurringDelete = false; onDelete() },
            onCancel = { confirmRecurringDelete = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column {
                Text("Due now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                Text(alert.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    Format.countdown(alert.dueAtEpochMillis - nowMillis),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                if (alert.isRecurring) {
                    Text(
                        "Daily routine — this only affects today.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (alert.extensionCount > 0) {
                    Text(
                        "Already pushed back ${alert.extensionCount} time${if (alert.extensionCount == 1) "" else "s"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Push back by", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtensionRules.presetMinutes.forEach { minutes ->
                        OutlinedButton(onClick = { onExtend(minutes) }) { Text("+${minutes}m") }
                    }
                    OutlinedButton(onClick = { customOpen = !customOpen }) { Text("Custom") }
                }
                if (customOpen) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Minutes") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { customText.toIntOrNull()?.let(onExtend) },
                            enabled = customText.toIntOrNull() != null,
                        ) { Text("Push") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDone) { Text("Done") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismissTask) { Text("Dismiss") }
                TextButton(
                    onClick = { if (alert.isRecurring) confirmRecurringDelete = true else onDelete() },
                ) { Text(if (alert.isRecurring) "Delete all" else "Delete") }
            }
        },
        modifier = Modifier.padding(8.dp),
    )
}
