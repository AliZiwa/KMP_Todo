package dev.aliziwa.todo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.aliziwa.todo.platform.Clock
import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.ui.AlertActionButton
import dev.aliziwa.todo.ui.AlertBell
import dev.aliziwa.todo.ui.ExtendMinutesDialog
import dev.aliziwa.todo.ui.InfoPill
import dev.aliziwa.todo.ui.LocalTodoPalette
import dev.aliziwa.todo.ui.Pill
import dev.aliziwa.todo.ui.TodoTheme
import dev.aliziwa.todo.ui.rememberShake
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

/**
 * Always-on-top, undecorated due alert. One is shown at a time: the shared DueEngine owns the
 * queue, so this window simply re-renders when a different [alert] arrives.
 *
 * Shares the app's theme, bell and buttons with the in-app alert dialog, so the two look like the
 * same product. Closing the window is treated as [AlertAction.Silence] — the task stays due,
 * matching "sticky until Done / Extend / Dismiss / Delete".
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DueAlertWindow(
    alert: DueAlert,
    clock: Clock,
    onAction: (AlertAction) -> Unit,
) {
    val state = rememberWindowState(
        width = 480.dp,
        height = 408.dp,
        position = WindowPosition(Alignment.Center),
    )
    var now by remember { mutableStateOf(clock.nowEpochMillis()) }
    var customOpen by remember(alert.taskId) { mutableStateOf(false) }

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
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
    ) {
        LaunchedEffect(alert.taskId) {
            window.toFront()
            window.requestFocus()
        }
        TodoTheme {
            val palette = LocalTodoPalette.current
            val shake = rememberShake(alert.taskId, amplitude = 10f)
            val priorityIndex = alert.priority.coerceIn(0, 2)
            val priorityColour = when (priorityIndex) {
                2 -> palette.high
                1 -> palette.medium
                else -> palette.low
            }
            val priorityContainer = when (priorityIndex) {
                2 -> palette.highContainer
                1 -> palette.mediumContainer
                else -> palette.lowContainer
            }

            if (customOpen) {
                ExtendMinutesDialog(
                    onConfirm = { minutes -> customOpen = false; onAction(AlertAction.Extend(minutes)) },
                    onCancel = { customOpen = false },
                )
            }

            Surface(
                modifier = Modifier.fillMaxSize().graphicsLayer { translationX = shake },
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Column(
                    Modifier
                        .background(palette.headerBrush)
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AlertBell(modifier = Modifier.size(72.dp), ringing = true)
                    Spacer(Modifier.height(10.dp))

                    val delta = alert.dueAtEpochMillis - now
                    InfoPill(
                        text = if (delta <= 0) {
                            "due ${Countdown.relative(delta)} ago"
                        } else {
                            "due in ${Countdown.relative(delta)}"
                        },
                        containerColor = palette.highContainer,
                        contentColor = palette.onHigh,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        alert.title,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfoPill(
                            text = PriorityNames[priorityIndex],
                            containerColor = priorityContainer,
                            contentColor = priorityColour,
                        )
                        if (alert.isRecurring) {
                            InfoPill(
                                text = "daily",
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (alert.extensionCount > 0) {
                            InfoPill(
                                text = "pushed ×${alert.extensionCount}",
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AlertActionButton(
                            text = "Done",
                            onClick = { onAction(AlertAction.Done) },
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        AlertActionButton(
                            text = "Dismiss",
                            onClick = { onAction(AlertAction.Dismiss) },
                            container = MaterialTheme.colorScheme.surfaceContainerLowest,
                            content = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        listOf(5, 15, 30).forEach { minutes ->
                            SmallPill("+${minutes}m") { onAction(AlertAction.Extend(minutes)) }
                        }
                        SmallPill("Custom…") { customOpen = true }
                        SmallPill("Delete", danger = true) { onAction(AlertAction.Delete) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallPill(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val palette = LocalTodoPalette.current
    Surface(
        onClick = onClick,
        shape = Pill,
        color = if (danger) palette.highContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (danger) palette.high else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
