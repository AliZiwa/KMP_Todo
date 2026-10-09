package dev.aliziwa.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskValidation
import dev.aliziwa.todo.util.Format
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.next
import kotlinx.datetime.plus

/**
 * Task editor used for both add and edit. Deliberately uses chips + text fields rather than the
 * Material date/time pickers so it stays compact at phone width and predictable on desktop.
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
) {
    var title by remember(initial) { mutableStateOf(initial.title) }
    var notes by remember(initial) { mutableStateOf(initial.notes) }
    var priority by remember(initial) { mutableStateOf(initial.priority) }
    var scope by remember(initial) { mutableStateOf(initial.scope) }
    var date by remember(initial) { mutableStateOf(initial.date ?: today) }
    var dateText by remember(initial) { mutableStateOf((initial.date ?: today).toString()) }
    var timeMinutes by remember(initial) { mutableStateOf(initial.timeMinutes) }
    var timeText by remember(initial) { mutableStateOf(initial.timeMinutes?.let { Format.timeOfDay(it) } ?: "") }
    var confirmRecurringDelete by remember(initial) { mutableStateOf(false) }

    val titleError = TaskValidation.normalizeTitle(title) == null

    if (confirmRecurringDelete && onDelete != null) {
        RecurringDeleteConfirmDialog(
            title = title.ifBlank { "this daily task" },
            onConfirm = { confirmRecurringDelete = false; onDelete() },
            onCancel = { confirmRecurringDelete = false },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { if (it.length <= TaskValidation.MAX_TITLE_LENGTH) title = it },
            label = { Text("Title") },
            isError = titleError && title.isNotEmpty(),
            supportingText = {
                Text(if (titleError) "Required" else "${title.length}/${TaskValidation.MAX_TITLE_LENGTH}")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes (optional)") },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Priority", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Priority.entries.reversed().forEach { option ->
                FilterChip(
                    selected = priority == option,
                    onClick = { priority = option },
                    label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        Text("Lives in", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskScope.entries.forEach { option ->
                FilterChip(
                    selected = scope == option,
                    onClick = { scope = option },
                    label = { Text(scopeLabel(option)) },
                )
            }
        }

        if (scope != TaskScope.RECURRING_DAILY) {
            Text(dateLabelFor(scope), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = datePresets(today, scope)
                options.forEach { (label, value) ->
                    FilterChip(
                        selected = date == value,
                        onClick = { date = value; dateText = value.toString() },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(
                value = dateText,
                onValueChange = { text ->
                    dateText = text
                    DateMath.parseDate(text)?.let { date = it }
                },
                label = { Text("Date (YYYY-MM-DD)") },
                isError = DateMath.parseDate(dateText) == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                buildString {
                    append("Repeats every day")
                    timeMinutes?.let { append(" at ").append(Format.timeOfDay(it)) }
                    append(". Ticking off or skipping today does not affect tomorrow; ")
                    append("deleting it removes every future day.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            if (scope == TaskScope.RECURRING_DAILY) "Time of day" else "Due time (optional)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = timeMinutes == null,
                onClick = { timeMinutes = null; timeText = "" },
                label = { Text("No time") },
            )
            listOf(7 * 60, 9 * 60, 12 * 60, 15 * 60, 18 * 60, 20 * 60).forEach { minutes ->
                FilterChip(
                    selected = timeMinutes == minutes,
                    onClick = { timeMinutes = minutes; timeText = Format.timeOfDay(minutes) },
                    label = { Text(Format.timeOfDay(minutes)) },
                )
            }
        }
        OutlinedTextField(
            value = timeText,
            onValueChange = { text ->
                timeText = text
                timeMinutes = DateMath.parseTimeOfDay(text)
            },
            label = { Text("Time (HH:MM)") },
            isError = timeText.isNotBlank() && DateMath.parseTimeOfDay(timeText) == null,
            supportingText = if (scope == TaskScope.RECURRING_DAILY) {
                { Text("The daily alarm time. Leave empty for no alarm.") }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                modifier = Modifier.weight(1f),
            ) { Text(confirmLabel) }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
        if (onDelete != null) {
            OutlinedButton(
                onClick = {
                    if (scope == TaskScope.RECURRING_DAILY) confirmRecurringDelete = true else onDelete()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (scope == TaskScope.RECURRING_DAILY) "Delete for all days" else "Delete task")
            }
        }
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

private fun datePresets(today: LocalDate, scope: TaskScope): List<Pair<String, LocalDate>> = when (scope) {
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
