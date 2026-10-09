package dev.aliziwa.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.domain.Due
import dev.aliziwa.todo.domain.DueState
import dev.aliziwa.todo.domain.ExtensionRules
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskItem
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.util.Format

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
fun PriorityBadge(priority: Priority, modifier: Modifier = Modifier) {
    val (bg, fg, label) = when (priority) {
        Priority.HIGH -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "HIGH")
        Priority.MEDIUM -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, "MED")
        Priority.LOW -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "LOW")
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Ticks every second via [nowMillis]; turns red and reads "overdue by 3m" once past due. */
@Composable
fun CountdownChip(item: TaskItem, nowMillis: Long, modifier: Modifier = Modifier) {
    val state = Due.stateOf(item, nowMillis)
    if (state == DueState.NO_TIME && item.dueAt == null) return
    val delta = (item.dueAt ?: return) - nowMillis
    val (bg, fg, text) = when (state) {
        DueState.OVERDUE, DueState.DUE -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            Format.countdown(delta),
        )
        DueState.DISMISSED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "dismissed · " + Format.countdown(delta),
        )
        DueState.DONE -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "done",
        )
        else -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Format.countdown(delta),
        )
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun TaskRow(
    item: TaskItem,
    nowMillis: Long,
    onToggleDone: () -> Unit,
    onOpen: () -> Unit,
    onExtend: (Int) -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var customExtendOpen by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }
    var confirmRecurringDelete by remember { mutableStateOf(false) }
    val done = item.status == TaskStatus.DONE
    val isRecurring = item.scope == TaskScope.RECURRING_DAILY

    if (confirmRecurringDelete) {
        RecurringDeleteConfirmDialog(
            title = item.title,
            onConfirm = { confirmRecurringDelete = false; onDelete() },
            onCancel = { confirmRecurringDelete = false },
        )
    }

    if (customExtendOpen) {
        AlertDialog(
            onDismissRequest = { customExtendOpen = false },
            title = { Text("Push back by") },
            text = {
                OutlinedTextField(
                    value = customMinutes,
                    onValueChange = { customMinutes = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Minutes") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        customMinutes.toIntOrNull()?.let(onExtend)
                        customMinutes = ""
                        customExtendOpen = false
                    },
                    enabled = customMinutes.toIntOrNull() != null,
                ) { Text("Push") }
            },
            dismissButton = { TextButton(onClick = { customExtendOpen = false }) { Text("Cancel") } },
        )
    }

    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (done) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleDone, modifier = Modifier.size(40.dp)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (done) MaterialTheme.colorScheme.primary else Color.Transparent,
                    border = if (done) null else androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.size(22.dp),
                ) {
                    if (done) {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Done",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.isRecurringInstance) {
                        Icon(
                            Icons.Default.Repeat,
                            contentDescription = "Repeats daily",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp).padding(end = 2.dp),
                        )
                    }
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    PriorityBadge(item.priority)
                    CountdownChip(item, nowMillis)
                    if (item.extensionCount > 0) {
                        Text(
                            "+${item.extensionCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    ExtensionRules.presetMinutes.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text("Extend +${minutes}m") },
                            onClick = { menuOpen = false; onExtend(minutes) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Extend custom…") },
                        onClick = { menuOpen = false; customExtendOpen = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { menuOpen = false; onOpen() },
                    )
                    if (item.status == TaskStatus.PENDING) {
                        DropdownMenuItem(
                            text = { Text("Dismiss") },
                            onClick = { menuOpen = false; onDismiss() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (isRecurring) "Delete (all days)" else "Delete") },
                        onClick = {
                            menuOpen = false
                            if (isRecurring) confirmRecurringDelete = true else onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Deleting a recurring task removes the template for every day, so it always asks first.
 * Skipping a single day is "Dismiss", which is spelled out in the message.
 */
@Composable
fun RecurringDeleteConfirmDialog(
    title: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete \"$title\"?") },
        text = { Text("Delete this daily task for all days? Use Dismiss to skip just today.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete for all days") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep it") } },
    )
}

@Composable
fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
