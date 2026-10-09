package dev.aliziwa.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.domain.Boards
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.TaskItem
import dev.aliziwa.todo.domain.TaskSnapshot
import dev.aliziwa.todo.domain.TaskStatus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** The callbacks every list row needs. Bundled so screens stay readable. */
class TaskActions(
    val onToggleDone: (TaskItem) -> Unit,
    val onOpen: (TaskItem) -> Unit,
    val onExtend: (TaskItem, Int) -> Unit,
    val onDismiss: (TaskItem) -> Unit,
    val onDelete: (TaskItem) -> Unit,
)

private val tabTitles = listOf("Day", "Week", "Month", "Recurring")

@Composable
fun TodayScreen(
    snapshot: TaskSnapshot,
    today: LocalDate,
    zone: TimeZone,
    nowMillis: Long,
    actions: TaskActions,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    var weekAnchor by remember(today) { mutableStateOf(today) }
    var monthAnchor by remember(today) { mutableStateOf(today) }
    var dayAnchor by remember(today) { mutableStateOf(today) }

    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            tabTitles.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> DayTab(snapshot, dayAnchor, today, zone, nowMillis, actions) { dayAnchor = it }
            1 -> RangeTab(
                label = rangeLabel(DateMath.weekRange(weekAnchor)),
                items = Boards.week(snapshot, weekAnchor).items,
                nowMillis = nowMillis,
                actions = actions,
                onPrev = { weekAnchor = weekAnchor.plus(-7, DateTimeUnit.DAY) },
                onNext = { weekAnchor = weekAnchor.plus(7, DateTimeUnit.DAY) },
                emptyTitle = "Nothing this week",
            )
            2 -> RangeTab(
                label = rangeLabel(DateMath.monthRange(monthAnchor)),
                items = Boards.month(snapshot, monthAnchor).items,
                nowMillis = nowMillis,
                actions = actions,
                onPrev = { monthAnchor = monthAnchor.plus(-1, DateTimeUnit.MONTH) },
                onNext = { monthAnchor = monthAnchor.plus(1, DateTimeUnit.MONTH) },
                emptyTitle = "Nothing this month",
            )
            else -> RecurringTab(snapshot, today, zone, nowMillis, actions)
        }
    }
}

@Composable
private fun DayTab(
    snapshot: TaskSnapshot,
    anchor: LocalDate,
    today: LocalDate,
    zone: TimeZone,
    nowMillis: Long,
    actions: TaskActions,
    onAnchorChange: (LocalDate) -> Unit,
) {
    val board = Boards.day(snapshot, anchor, zone)
    val showOverdue = anchor == today && board.overdue.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        RangeHeader(
            label = if (anchor == today) "Today · ${DateMath.shortLabel(anchor)}" else DateMath.shortLabel(anchor),
            onPrev = { onAnchorChange(anchor.plus(-1, DateTimeUnit.DAY)) },
            onNext = { onAnchorChange(anchor.plus(1, DateTimeUnit.DAY)) },
        )
        if (board.items.isEmpty() && !showOverdue) {
            EmptyState("Nothing scheduled", "Tap + to add a task, or ask the assistant.")
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (showOverdue) {
                item("overdue-header") { SectionHeader("Overdue (${board.overdue.size})") }
                items(board.overdue, key = { it.key }) { item -> Row(item, nowMillis, actions) }
            }
            if (board.items.isNotEmpty()) {
                item("today-header") { SectionHeader(if (showOverdue) "Today" else "Tasks") }
                items(board.items, key = { it.key }) { item -> Row(item, nowMillis, actions) }
            }
        }
    }
}

@Composable
private fun RangeTab(
    label: String,
    items: List<TaskItem>,
    nowMillis: Long,
    actions: TaskActions,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    emptyTitle: String,
) {
    Column(Modifier.fillMaxSize()) {
        RangeHeader(label, onPrev, onNext)
        if (items.isEmpty()) {
            EmptyState(emptyTitle, "Add a task and choose this range.")
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items, key = { it.key }) { item -> Row(item, nowMillis, actions) }
        }
    }
}

@Composable
private fun RecurringTab(
    snapshot: TaskSnapshot,
    today: LocalDate,
    zone: TimeZone,
    nowMillis: Long,
    actions: TaskActions,
) {
    val items = Boards.recurring(snapshot, today, zone)
    Column(Modifier.fillMaxSize()) {
        Text(
            "Daily routines — ticking one off only affects ${DateMath.shortLabel(today)}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        if (items.isEmpty()) {
            EmptyState("No daily routines", "Add a task and pick \"Every day\".")
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val done = items.count { it.status == TaskStatus.DONE }
            item("progress") { SectionHeader("$done of ${items.size} done today") }
            items(items, key = { it.key }) { item -> Row(item, nowMillis, actions) }
        }
    }
}

@Composable
private fun Row(item: TaskItem, nowMillis: Long, actions: TaskActions) {
    TaskRow(
        item = item,
        nowMillis = nowMillis,
        onToggleDone = { actions.onToggleDone(item) },
        onOpen = { actions.onOpen(item) },
        onExtend = { minutes -> actions.onExtend(item, minutes) },
        onDismiss = { actions.onDismiss(item) },
        onDelete = { actions.onDelete(item) },
    )
}

@Composable
private fun RangeHeader(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous") }
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext) { Icon(Icons.Default.ChevronRight, contentDescription = "Next") }
    }
}

private fun rangeLabel(range: Pair<LocalDate, LocalDate>): String =
    "${DateMath.shortLabel(range.first)} – ${DateMath.shortLabel(range.second)}"
