package dev.aliziwa.todo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.aliziwa.todo.platform.Clock
import dev.aliziwa.todo.platform.DueAlert
import kotlinx.coroutines.delay

/** What the user chose on a due alert. */
sealed interface AlertAction {
    data object Done : AlertAction
    data class Extend(val minutes: Int) : AlertAction
    data object Dismiss : AlertAction
    data object Delete : AlertAction
    /** Window closed without a decision — keep the alert sticky, just stop the noise. */
    data object Silence : AlertAction
}

private val PriorityNames = arrayOf("Low", "Medium", "High")
private val PriorityColors = arrayOf(Color(0xFF6B7A8F), Color(0xFFC98A1B), Color(0xFFD1453B))

/**
 * Always-on-top, undecorated due alert. One is shown at a time: the shared DueEngine owns the
 * queue, so this window simply re-renders when a different [alert] arrives.
 *
 * Closing the window (Esc / red-less chrome has no close button, but the OS can still ask) is
 * treated as [AlertAction.Silence] — the task stays due, matching "sticky until Done / Extend /
 * Dismiss / Delete".
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DueAlertWindow(
    alert: DueAlert,
    clock: Clock,
    onAction: (AlertAction) -> Unit,
) {
    val state = rememberWindowState(
        width = 460.dp,
        height = 300.dp,
        position = WindowPosition(Alignment.Center),
    )
    var now by remember { mutableStateOf(clock.nowEpochMillis()) }
    var customOpen by remember(alert.taskId) { mutableStateOf(false) }
    var customText by remember(alert.taskId) { mutableStateOf("45") }

    LaunchedEffect(alert.taskId) {
        while (true) {
            now = clock.nowEpochMillis()
            delay(1_000)
        }
    }

    Window(
        onCloseRequest = { onAction(AlertAction.Silence) },
        state = state,
        title = alert.title,
        undecorated = true,
        alwaysOnTop = true,
        resizable = false,
    ) {
        LaunchedEffect(alert.taskId) {
            window.toFront()
            window.requestFocus()
        }
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = null,
            ) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val idx = alert.priority.coerceIn(0, 2)
                        Box(
                            Modifier
                                .background(PriorityColors[idx].copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                                .border(1.dp, PriorityColors[idx].copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                PriorityNames[idx],
                                color = PriorityColors[idx],
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (alert.isRecurring) {
                            Text("daily", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (alert.extensionCount > 0) {
                            Text(
                                "extended ${alert.extensionCount}x",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        alert.title,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                    )
                    Spacer(Modifier.height(4.dp))
                    val delta = alert.dueAtEpochMillis - now
                    Text(
                        if (delta <= 0) "due ${Countdown.relative(delta)} ago" else "due in ${Countdown.relative(delta)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))

                    if (customOpen) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = customText,
                                onValueChange = { v -> customText = v.filter(Char::isDigit).take(4) },
                                label = { Text("Minutes") },
                                singleLine = true,
                                modifier = Modifier.width(140.dp),
                                keyboardActions = KeyboardActions(onDone = {
                                    customText.toIntOrNull()?.takeIf { it > 0 }?.let { onAction(AlertAction.Extend(it)) }
                                }),
                            )
                            Spacer(Modifier.width(10.dp))
                            Button(
                                enabled = (customText.toIntOrNull() ?: 0) > 0,
                                onClick = {
                                    customText.toIntOrNull()?.takeIf { it > 0 }
                                        ?.let { onAction(AlertAction.Extend(it)) }
                                },
                            ) { Text("Extend") }
                            Spacer(Modifier.width(6.dp))
                            TextButton(onClick = { customOpen = false }) { Text("Cancel") }
                        }
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { onAction(AlertAction.Done) }) { Text("Done") }
                            OutlinedButton(onClick = { onAction(AlertAction.Extend(5)) }) { Text("+5") }
                            OutlinedButton(onClick = { onAction(AlertAction.Extend(15)) }) { Text("+15") }
                            OutlinedButton(onClick = { onAction(AlertAction.Extend(30)) }) { Text("+30") }
                            OutlinedButton(onClick = { customOpen = true }) { Text("Custom…") }
                            TextButton(onClick = { onAction(AlertAction.Dismiss) }) { Text("Dismiss") }
                            TextButton(
                                onClick = { onAction(AlertAction.Delete) },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
