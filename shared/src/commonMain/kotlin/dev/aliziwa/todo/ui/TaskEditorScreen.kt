package dev.aliziwa.todo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskValidation
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.next
import kotlinx.datetime.plus

/**
 * Task editor used for both add and edit. Grouped cards; the date and time are entered with the
 * shared [DatePickerField] / [TimePickerField] so there is one way to pick a time in the app.
 */
@Composable
fun TaskEditorForm(
    initial: TaskDraft,
    today: LocalDate,
    confirmLabel: String,
    onSubmit: (TaskDraft) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Minutes-of-day right now, so the time picker can offer "+15m" / "+1h". */
    nowMinutes: Int? = null,
    /** Opens the time picker immediately — used by screenshot tests and the "set a time" CTA. */
    openTimePicker: Boolean = false,
) {
    var title by remember(initial) { mutableStateOf(initial.title) }
    var notes by remember(initial) { mutableStateOf(initial.notes) }
    var priority by remember(initial) { mutableStateOf(initial.priority) }
    var scope by remember(initial) { mutableStateOf(initial.scope) }
    var date by remember(initial) { mutableStateOf(initial.date ?: today) }
    var timeMinutes by remember(initial) { mutableStateOf(initial.timeMinutes) }
    var confirmRecurringDelete by remember(initial) { mutableStateOf(false) }

    val palette = LocalTodoPalette.current
    val titleError = TaskValidation.normalizeTitle(title) == null

    if (confirmRecurringDelete && onDelete != null) {
        RecurringDeleteConfirmDialog(
            title = title.ifBlank { "this daily task" },
            onConfirm = { confirmRecurringDelete = false; onDelete() },
            onCancel = { confirmRecurringDelete = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Keeps the Add/Save row reachable while the keyboard is up. The app Scaffold already
            // pads (and consumes) safeDrawing, which includes the IME, so this never double-pads.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EditorCard {
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= TaskValidation.MAX_TITLE_LENGTH) title = it },
                label = { Text("What needs doing?") },
                isError = titleError && title.isNotEmpty(),
                supportingText = {
                    Text(if (titleError) "A title, however short" else "${title.length}/${TaskValidation.MAX_TITLE_LENGTH}")
                },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                textStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                minLines = 2,
                maxLines = 5,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        EditorCard {
            Text(
                "How urgent?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.reversed().forEach { option ->
                    PriorityChip(
                        priority = option,
                        selected = priority == option,
                        onClick = { priority = option },
                    )
                }
            }

            Spacer(Modifier.height(2.dp))
            Text(
                "Lives in",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TaskScope.entries.forEach { option ->
                    SelectableChip(
                        text = scopeLabel(option),
                        selected = scope == option,
                        onClick = { scope = option },
                    )
                }
            }
        }

        EditorCard {
            AnimatedVisibility(
                visible = scope != TaskScope.RECURRING_DAILY,
                enter = fadeIn(tween(160)) + expandVertically(Motion.bouncyFinite()),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
            ) {
                DatePickerField(
                    date = date,
                    onChange = { date = it },
                    presets = datePresets(today, scope),
                    label = dateLabelFor(scope),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                )
            }

            TimePickerField(
                minutes = timeMinutes,
                onChange = { timeMinutes = it },
                label = if (scope == TaskScope.RECURRING_DAILY) "Time of day" else "Due time (optional)",
                helper = if (scope == TaskScope.RECURRING_DAILY) {
                    "The daily alarm time. \"No time\" means no alarm."
                } else {
                    "Set a time and you get a countdown plus a doorbell when it is due."
                },
                nowMinutes = nowMinutes,
                initiallyOpen = openTimePicker,
            )

            AnimatedVisibility(
                visible = scope == TaskScope.RECURRING_DAILY,
                enter = fadeIn(tween(160)) + expandVertically(Motion.bouncyFinite()),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
            ) {
                Surface(
                    color = palette.lowContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) {
                    Text(
                        "Repeats every day. Ticking off or skipping today does not affect tomorrow; " +
                            "deleting it removes every future day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onLow,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        ) {
            val submitInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    onSubmit(
                        TaskDraft(
                            id = initial.id,
                            title = title,
                            notes = notes,
                            priority = priority,
                            scope = scope,
                            date = if (scope == TaskScope.RECURRING_DAILY) initial.date ?: today else date,
                            timeMinutes = timeMinutes,
                        )
                    )
                },
                enabled = !titleError,
                shape = Pill,
                interactionSource = submitInteraction,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .bouncyPress(submitInteraction),
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(confirmLabel, style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onCancel, shape = Pill) { Text("Cancel") }
            if (onDelete != null) {
                val deleteInteraction = remember { MutableInteractionSource() }
                Surface(
                    onClick = {
                        if (scope == TaskScope.RECURRING_DAILY) confirmRecurringDelete = true else onDelete()
                    },
                    interactionSource = deleteInteraction,
                    shape = Pill,
                    color = palette.highContainer,
                    modifier = Modifier.size(52.dp).bouncyPress(deleteInteraction),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = if (scope == TaskScope.RECURRING_DAILY) {
                                "Delete for all days"
                            } else {
                                "Delete task"
                            },
                            tint = palette.high,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun PriorityChip(priority: Priority, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalTodoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (selected) palette.spine(priority) else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(180),
        label = "priorityChip",
    )
    val pop = rememberPop(selected, peak = if (selected) 1.12f else 1f)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = Pill,
        color = container,
        modifier = Modifier
            .bouncyPress(interaction)
            .graphicsLayer {
                scaleX = pop
                scaleY = pop
            },
    ) {
        Text(
            priority.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.surfaceContainerLowest
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

internal fun scopeLabel(scope: TaskScope): String = when (scope) {
    TaskScope.DAY -> "Day"
    TaskScope.WEEK -> "Week"
    TaskScope.MONTH -> "Month"
    TaskScope.RECURRING_DAILY -> "Every day"
}

private fun dateLabelFor(scope: TaskScope): String = when (scope) {
    TaskScope.WEEK -> "Any day in the target week"
    TaskScope.MONTH -> "Any day in the target month"
    else -> "Date"
}

internal fun datePresets(today: LocalDate, scope: TaskScope): List<Pair<String, LocalDate>> = when (scope) {
    TaskScope.WEEK -> listOf(
        "This week" to today,
        "Next week" to today.plus(1, DateTimeUnit.DAY).next(DayOfWeek.MONDAY),
    )
    TaskScope.MONTH -> listOf(
        "This month" to today,
        "Next month" to today.plus(1, DateTimeUnit.MONTH),
    )
    else -> listOf(
        "Today" to today,
        "Tomorrow" to today.plus(1, DateTimeUnit.DAY),
        "In 2 days" to today.plus(2, DateTimeUnit.DAY),
        "Next Mon" to today.plus(1, DateTimeUnit.DAY).next(DayOfWeek.MONDAY),
    )
}
