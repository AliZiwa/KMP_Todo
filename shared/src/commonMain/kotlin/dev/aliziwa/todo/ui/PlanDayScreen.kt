package dev.aliziwa.todo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskDraft
import dev.aliziwa.todo.domain.TaskItem
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskStatus
import kotlinx.datetime.LocalDate

private class QuickRow(val title: String, val timeMinutes: Int?, val priority: Priority)

/**
 * The morning "Plan your day" sheet: shows what is already on today's plate and lets the user
 * quick-add several tasks in one go, each with the shared time picker.
 */
/**
 * Below this content width the time field and the priority chips share one wrapping row; above it
 * the roomier desktop layout is kept exactly as it was.
 */
private val NARROW_ROW_BREAKPOINT = 420.dp

@Composable
fun PlanDayScreen(
    today: LocalDate,
    existing: List<TaskItem>,
    nowMillis: Long,
    onAddAll: (List<TaskDraft>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    nowMinutes: Int? = null,
) {
    val palette = LocalTodoPalette.current
    val rows = remember { mutableStateListOf(QuickRow("", null, Priority.MEDIUM)) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(
                Modifier.background(palette.headerBrush).fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Plan your day", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        DateMath.shortLabel(today),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EmptyIllustration(
                    art = EmptyArt.SPARKLE,
                    modifier = Modifier.size(72.dp),
                    primary = MaterialTheme.colorScheme.primary,
                    secondary = palette.low,
                    accent = palette.medium,
                    soft = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
                )
            }
        }

        val pending = existing.filter { it.status == TaskStatus.PENDING }
        if (pending.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    SectionHeader("Already on today (${pending.size})")
                    pending.take(8).forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 5.dp),
                        ) {
                            PriorityBadge(item.priority)
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            CountdownChip(item, nowMillis)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                SectionHeader("What's the plan?")
                rows.forEachIndexed { index, row ->
                    QuickAddRow(
                        row = row,
                        nowMinutes = nowMinutes,
                        canRemove = rows.size > 1,
                        onRemove = { rows.removeAt(index) },
                        onChange = { title, time, priority -> rows[index] = QuickRow(title, time, priority) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { rows.add(QuickRow("", null, Priority.MEDIUM)) }, shape = Pill) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Another one")
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        ) {
            val interaction = remember { MutableInteractionSource() }
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
                shape = Pill,
                interactionSource = interaction,
                modifier = Modifier.weight(1f).height(52.dp).bouncyPress(interaction),
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add to today", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onClose, shape = Pill) { Text("Not now") }
        }
    }
}

@Composable
private fun QuickAddRow(
    row: QuickRow,
    nowMinutes: Int?,
    canRemove: Boolean,
    onRemove: () -> Unit,
    onChange: (String, Int?, Priority) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = row.title,
                onValueChange = { onChange(it, row.timeMinutes, row.priority) },
                label = { Text("Task") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Remove this line",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // At phone width the big time pill squeezes the priority chips onto three lines, so below
        // the breakpoint the time field drops to chip scale and shares ONE FlowRow with them.
        BoxWithConstraints {
            val narrow = maxWidth < NARROW_ROW_BREAKPOINT
            val priorityChips: @Composable () -> Unit = {
                Priority.entries.reversed().forEach { priority ->
                    SelectableChip(
                        text = priority.name.lowercase().replaceFirstChar { it.uppercase() },
                        selected = row.priority == priority,
                        onClick = { onChange(row.title, row.timeMinutes, priority) },
                    )
                }
            }
            if (narrow) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TimePickerField(
                        minutes = row.timeMinutes,
                        onChange = { onChange(row.title, it, row.priority) },
                        nowMinutes = nowMinutes,
                        compact = true,
                    )
                    priorityChips()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimePickerField(
                        minutes = row.timeMinutes,
                        onChange = { onChange(row.title, it, row.priority) },
                        nowMinutes = nowMinutes,
                    )
                    Spacer(Modifier.width(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) { priorityChips() }
                }
            }
        }
    }
}
