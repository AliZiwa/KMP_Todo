package dev.aliziwa.todo.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
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

/**
 * The task boards. Which board is showing is chosen by the navigation rail / bottom bar and passed
 * in as [tab]; each board keeps its own date anchor so stepping through weeks does not reset the
 * day you were looking at.
 */
/**
 * Bottom breathing room on every board list: the [dev.aliziwa.todo.ui.GradientFab] floats over the
 * bottom-right corner (62dp + 26dp inset), and on a phone it would otherwise sit on top of the last
 * row's overflow button.
 */
private val FAB_CLEARANCE = 96.dp

@Composable
fun TodayScreen(
    snapshot: TaskSnapshot,
    today: LocalDate,
    zone: TimeZone,
    nowMillis: Long,
    actions: TaskActions,
    tab: BoardTab = BoardTab.DAY,
    modifier: Modifier = Modifier,
) {
    var weekAnchor by remember(today) { mutableStateOf(today) }
    var monthAnchor by remember(today) { mutableStateOf(today) }
    var dayAnchor by remember(today) { mutableStateOf(today) }

    Column(modifier.fillMaxSize()) {
        when (tab) {
            BoardTab.DAY -> DayTab(snapshot, dayAnchor, today, zone, nowMillis, actions) { dayAnchor = it }
            BoardTab.WEEK -> RangeTab(
                label = rangeLabel(DateMath.weekRange(weekAnchor)),
                items = Boards.week(snapshot, weekAnchor).items,
                nowMillis = nowMillis,
                zone = zone,
                actions = actions,
                onPrev = { weekAnchor = weekAnchor.plus(-7, DateTimeUnit.DAY) },
                onNext = { weekAnchor = weekAnchor.plus(7, DateTimeUnit.DAY) },
                onToday = { weekAnchor = today },
                isNow = DateMath.weekKey(weekAnchor) == DateMath.weekKey(today),
                emptyTitle = "Nothing this week",
                emptySubtitle = "A clear week. Add something, or leave it clear.",
            )
            BoardTab.MONTH -> RangeTab(
                label = rangeLabel(DateMath.monthRange(monthAnchor)),
                items = Boards.month(snapshot, monthAnchor).items,
                nowMillis = nowMillis,
                zone = zone,
                actions = actions,
                onPrev = { monthAnchor = monthAnchor.plus(-1, DateTimeUnit.MONTH) },
                onNext = { monthAnchor = monthAnchor.plus(1, DateTimeUnit.MONTH) },
                onToday = { monthAnchor = today },
                isNow = DateMath.monthKey(monthAnchor) == DateMath.monthKey(today),
                emptyTitle = "Nothing this month",
                emptySubtitle = "Big-picture stuff goes here — the gym membership, the dentist.",
            )
            BoardTab.RECURRING -> RecurringTab(snapshot, today, zone, nowMillis, actions)
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
    val palette = LocalTodoPalette.current
    val board = Boards.day(snapshot, anchor, zone)
    val showOverdue = anchor == today && board.overdue.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        RangeHeader(
            label = if (anchor == today) "Today · ${DateMath.shortLabel(anchor)}" else DateMath.shortLabel(anchor),
            isNow = anchor == today,
            onPrev = { onAnchorChange(anchor.plus(-1, DateTimeUnit.DAY)) },
            onNext = { onAnchorChange(anchor.plus(1, DateTimeUnit.DAY)) },
            onToday = { onAnchorChange(today) },
        )
        if (board.items.isEmpty() && !showOverdue) {
            EmptyState(
                title = "Nothing due",
                subtitle = "Go enjoy your day ☕",
                art = EmptyArt.COFFEE,
            )
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = FAB_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (showOverdue) {
                item("overdue-header") {
                    SectionHeader("Spilled over (${board.overdue.size})", accent = palette.high)
                }
                items(board.overdue, key = { it.key }) { item ->
                    Row(item, nowMillis, zone, actions, Modifier.animateItem(placementSpec = Motion.bouncyOffset))
                }
            }
            if (board.items.isNotEmpty()) {
                item("today-header") {
                    SectionHeader(if (showOverdue) "Today" else "On the plan")
                }
                items(board.items, key = { it.key }) { item ->
                    Row(item, nowMillis, zone, actions, Modifier.animateItem(placementSpec = Motion.bouncyOffset))
                }
            }
        }
    }
}

@Composable
private fun RangeTab(
    label: String,
    items: List<TaskItem>,
    nowMillis: Long,
    zone: TimeZone,
    actions: TaskActions,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    isNow: Boolean,
    emptyTitle: String,
    emptySubtitle: String,
) {
    Column(Modifier.fillMaxSize()) {
        RangeHeader(label, isNow, onPrev, onNext, onToday)
        if (items.isEmpty()) {
            EmptyState(emptyTitle, emptySubtitle, art = EmptyArt.CALENDAR)
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = FAB_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.key }) { item ->
                Row(item, nowMillis, zone, actions, Modifier.animateItem(placementSpec = Motion.bouncyOffset))
            }
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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        )
        if (items.isEmpty()) {
            EmptyState(
                "No routines yet",
                "Add a task and pick \"Every day\" — water the plants, stretch, call home.",
                art = EmptyArt.SPROUT,
            )
            return@Column
        }
        val done = items.count { it.status == TaskStatus.DONE }
        LazyColumn(
            contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = FAB_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("progress") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("$done of ${items.size} done today")
                }
            }
            items(items, key = { it.key }) { item ->
                Row(item, nowMillis, zone, actions, Modifier.animateItem(placementSpec = Motion.bouncyOffset))
            }
        }
    }
}

@Composable
private fun Row(
    item: TaskItem,
    nowMillis: Long,
    zone: TimeZone,
    actions: TaskActions,
    modifier: Modifier = Modifier,
) {
    TaskRow(
        item = item,
        nowMillis = nowMillis,
        onToggleDone = { actions.onToggleDone(item) },
        onOpen = { actions.onOpen(item) },
        onExtend = { minutes -> actions.onExtend(item, minutes) },
        onDismiss = { actions.onDismiss(item) },
        onDelete = { actions.onDelete(item) },
        modifier = modifier,
        zone = zone,
    )
}

@Composable
private fun RangeHeader(
    label: String,
    isNow: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(Icons.Default.ChevronLeft, "Previous", onPrev)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        if (!isNow) {
            SelectableChip(text = "Today", selected = false, onClick = onToday)
            Spacer(Modifier.width(6.dp))
        }
        StepButton(Icons.Default.ChevronRight, "Next", onNext)
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Pill,
        modifier = Modifier.size(36.dp).bouncyPress(interaction, squish = 0.86f),
    ) {
        IconButton(onClick = onClick, interactionSource = interaction) {
            Icon(
                icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun rangeLabel(range: Pair<LocalDate, LocalDate>): String =
    "${DateMath.shortLabel(range.first)} – ${DateMath.shortLabel(range.second)}"
