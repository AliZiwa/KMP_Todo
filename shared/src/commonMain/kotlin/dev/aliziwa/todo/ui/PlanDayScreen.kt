package dev.aliziwa.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskItem
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.util.Format
import kotlinx.datetime.LocalDate

private class QuickRow(var title: String, var timeMinutes: Int?, var priority: Priority)

/**
 * The morning "Plan your day" sheet: shows what is already on today's plate and lets the user
 * quick-add several tasks in one go.
 */
@Composable
fun PlanDayScreen(
    today: LocalDate,
    existing: List<TaskItem>,
    nowMillis: Long,
    onAddAll: (List<TaskDraft>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember { mutableStateListOf(QuickRow("", null, Priority.MEDIUM)) }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Plan your day", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            DateMath.shortLabel(today),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val pending = existing.filter { it.status == TaskStatus.PENDING }
        if (pending.isNotEmpty()) {
            SectionHeader("Already on today (${pending.size})")
            pending.take(8).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PriorityBadge(item.priority)
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    CountdownChip(item, nowMillis)
                }
            }
        }

        SectionHeader("Add to today")
        rows.forEachIndexed { index, row ->
            QuickAddRow(
                row = row,
                onChange = { title, time, priority ->
                    rows[index] = QuickRow(title, time, priority)
                },
            )
        }

        OutlinedButton(onClick = { rows.add(QuickRow("", null, Priority.MEDIUM)) }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("Another task", modifier = Modifier.padding(start = 6.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val drafts = rows.filter { it.title.isNotBlank() }.map {
                        TaskDraft(
                            title = it.title,
                            priority = it.priority,
                            scope = TaskScope.DAY,
                            date = today,
                            timeMinutes = it.timeMinutes,
                        )
                    }
                    onAddAll(drafts)
                },
                enabled = rows.any { it.title.isNotBlank() },
                modifier = Modifier.weight(1f),
            ) { Text("Add to today") }
            OutlinedButton(onClick = onClose) { Text("Not now") }
        }
    }
}

@Composable
private fun QuickAddRow(row: QuickRow, onChange: (String, Int?, Priority) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = row.title,
            onValueChange = { onChange(it, row.timeMinutes, row.priority) },
            label = { Text("Task") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = row.timeMinutes == null,
                onClick = { onChange(row.title, null, row.priority) },
                label = { Text("No time") },
            )
            listOf(9 * 60, 12 * 60, 15 * 60, 18 * 60).forEach { minutes ->
                FilterChip(
                    selected = row.timeMinutes == minutes,
                    onClick = { onChange(row.title, minutes, row.priority) },
                    label = { Text(Format.timeOfDay(minutes)) },
                )
            }
            Priority.entries.reversed().forEach { priority ->
                FilterChip(
                    selected = row.priority == priority,
                    onClick = { onChange(row.title, row.timeMinutes, priority) },
                    label = { Text(priority.name.take(1) + priority.name.drop(1).lowercase()) },
                )
            }
        }
    }
}
