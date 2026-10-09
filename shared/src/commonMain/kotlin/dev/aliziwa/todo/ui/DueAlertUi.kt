package dev.aliziwa.todo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
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
    val palette = LocalTodoPalette.current
    var customOpen by remember(alert.taskId) { mutableStateOf(false) }
    var confirmRecurringDelete by remember(alert.taskId) { mutableStateOf(false) }

    if (confirmRecurringDelete) {
        RecurringDeleteConfirmDialog(
            title = alert.title,
            onConfirm = { confirmRecurringDelete = false; onDelete() },
            onCancel = { confirmRecurringDelete = false },
        )
        return
    }

    if (customOpen) {
        ExtendMinutesDialog(
            onConfirm = { minutes -> customOpen = false; onExtend(minutes) },
            onCancel = { customOpen = false },
        )
        return
    }

    val delta = alert.dueAtEpochMillis - nowMillis
    val shake = rememberShake(alert.taskId)

    AlertDialog(
        onDismissRequest = onClose,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.graphicsLayer { translationX = shake },
        icon = {
            AlertBell(modifier = Modifier.size(78.dp), ringing = true)
        },
        title = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                InfoPill(
                    text = if (delta <= 0) "due ${Format.span(-delta)} ago" else "due in ${Format.span(delta)}",
                    containerColor = palette.highContainer,
                    contentColor = palette.onHigh,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    alert.title,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
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
                Text(
                    "Push back by",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExtensionRules.presetMinutes.forEach { minutes ->
                        QuickChip("+${minutes}m") { onExtend(minutes) }
                    }
                    QuickChip("Custom…") { customOpen = true }
                }
            }
        },
        confirmButton = {
            val interaction = remember { MutableInteractionSource() }
            Button(
                onClick = onDone,
                shape = Pill,
                interactionSource = interaction,
                modifier = Modifier.height(52.dp).bouncyPress(interaction, squish = 0.9f),
            ) {
                Text("Done", style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismissTask, shape = Pill) { Text("Dismiss") }
                TextButton(
                    onClick = { if (alert.isRecurring) confirmRecurringDelete = true else onDelete() },
                    shape = Pill,
                ) { Text(if (alert.isRecurring) "Delete all" else "Delete") }
            }
        },
    )
}

/**
 * The alert's mascot: a bell drawn with Canvas that swings while something is actually due.
 * Shared with the desktop always-on-top alert window so both look the same.
 */
@Composable
fun AlertBell(
    modifier: Modifier = Modifier.size(72.dp),
    ringing: Boolean = true,
    color: Color = LocalTodoPalette.current.high,
    accent: Color = LocalTodoPalette.current.medium,
) {
    val transition = rememberInfiniteTransition(label = "bell")
    val wobble by transition.animateFloat(
        initialValue = -13f,
        targetValue = 13f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "swing",
    )
    val swing = if (ringing) wobble else 0f
    val halo = rememberBreath(ringing, min = 1f, max = 1.12f, periodMillis = 900)

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = halo
                    scaleY = halo
                },
        ) {
            drawCircle(color.copy(alpha = 0.16f), radius = size.minDimension / 2f)
        }
        Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    rotationZ = swing
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.1f)
                },
        ) {
            val unit = size.minDimension / 100f
            fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
            fun s(v: Float) = v * unit

            // Handle
            drawRoundRect(color, p(46f, 16f), Size(s(8f), s(8f)), CornerRadius(s(4f), s(4f)))
            // Body: a dome plus straight sides
            val body = Path().apply {
                moveTo(p(26f, 66f).x, p(26f, 66f).y)
                cubicTo(
                    p(26f, 40f).x, p(26f, 40f).y,
                    p(34f, 23f).x, p(34f, 23f).y,
                    p(50f, 23f).x, p(50f, 23f).y,
                )
                cubicTo(
                    p(66f, 23f).x, p(66f, 23f).y,
                    p(74f, 40f).x, p(74f, 40f).y,
                    p(74f, 66f).x, p(74f, 66f).y,
                )
                close()
            }
            drawPath(body, color)
            drawRoundRect(color, p(21f, 66f), Size(s(58f), s(8f)), CornerRadius(s(4f), s(4f)))
            // Clapper
            drawCircle(color, radius = s(6f), center = p(50f, 82f))
            // Sound lines
            drawLine(accent, p(10f, 40f), p(2f, 36f), strokeWidth = s(3f), cap = StrokeCap.Round)
            drawLine(accent, p(12f, 52f), p(3f, 53f), strokeWidth = s(3f), cap = StrokeCap.Round)
            drawLine(accent, p(90f, 40f), p(98f, 36f), strokeWidth = s(3f), cap = StrokeCap.Round)
            drawLine(accent, p(88f, 52f), p(97f, 53f), strokeWidth = s(3f), cap = StrokeCap.Round)
        }
    }
}

/** The big action buttons the desktop alert window and the in-app dialog share. */
@Composable
fun AlertActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = Pill,
        color = container,
        modifier = modifier.bouncyPress(interaction, squish = 0.9f),
    ) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleMedium, color = content)
        }
    }
}
