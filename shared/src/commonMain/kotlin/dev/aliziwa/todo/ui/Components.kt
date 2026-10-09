package dev.aliziwa.todo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.domain.DateMath
import dev.aliziwa.todo.domain.Due
import dev.aliziwa.todo.domain.DueState
import dev.aliziwa.todo.domain.ExtensionRules
import dev.aliziwa.todo.domain.Priority
import dev.aliziwa.todo.domain.TaskItem
import dev.aliziwa.todo.domain.TaskScope
import dev.aliziwa.todo.domain.TaskStatus
import dev.aliziwa.todo.util.Format
import kotlinx.datetime.TimeZone

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Row(
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(Pill)
                .background(accent ?: MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The app's standard pill: used for times, countdowns, priorities and counters. */
@Composable
fun InfoPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(color = containerColor, shape = Pill, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/** Priority pill. Breathes gently while the task is actually ringing. */
@Composable
fun PriorityBadge(
    priority: Priority,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    val palette = LocalTodoPalette.current
    val label = when (priority) {
        Priority.HIGH -> "HIGH"
        Priority.MEDIUM -> "MED"
        Priority.LOW -> "LOW"
    }
    val breath = rememberBreath(pulsing)
    InfoPill(
        text = label,
        containerColor = palette.container(priority),
        contentColor = palette.onContainer(priority),
        modifier = modifier.graphicsLayer {
            scaleX = breath
            scaleY = breath
        },
    )
}

/** Ticks every second via [nowMillis]; flips digits, and turns coral once past due. */
@Composable
fun CountdownChip(item: TaskItem, nowMillis: Long, modifier: Modifier = Modifier) {
    val palette = LocalTodoPalette.current
    val state = Due.stateOf(item, nowMillis)
    if (item.dueAt == null) return
    val delta = item.dueAt - nowMillis
    val (bg, fg, text) = when (state) {
        DueState.OVERDUE, DueState.DUE -> Triple(
            palette.highContainer,
            palette.onHigh,
            Format.countdown(delta),
        )
        DueState.DISMISSED -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "snoozed · " + Format.countdown(delta),
        )
        DueState.DONE -> Triple(
            palette.lowContainer,
            palette.onLow,
            "done",
        )
        else -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Format.countdown(delta),
        )
    }
    Surface(color = bg, shape = Pill, modifier = modifier) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            AnimatedContent(
                targetState = text,
                transitionSpec = {
                    (slideInVertically { it } + fadeIn(tween(140))) togetherWith
                        (slideOutVertically { -it } + fadeOut(tween(140))) using
                        SizeTransform(clip = false)
                },
                label = "countdown",
            ) { value ->
                Text(value, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
            }
        }
    }
}

/** Checkbox with a scale overshoot and a one-off sparkle burst when it is ticked. */
@Composable
fun BouncyCheck(
    done: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val palette = LocalTodoPalette.current
    var bursts by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(done) }
    if (done != seen) {
        if (done) bursts += 1
        seen = done
    }
    val pop = rememberPop(done, peak = if (done) 1.45f else 1.12f)
    val boxColor by animateColorAsState(
        if (done) tint else Color.Transparent,
        tween(180),
        label = "checkFill",
    )

    Box(modifier.size(42.dp), contentAlignment = Alignment.Center) {
        SparkleBurst(
            trigger = bursts,
            colors = palette.confetti,
            modifier = Modifier.size(42.dp),
        )
        IconButton(onClick = onToggle, modifier = Modifier.size(42.dp)) {
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = boxColor,
                border = if (done) null else BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .size(23.dp)
                    .graphicsLayer {
                        scaleX = pop
                        scaleY = pop
                    },
            ) {
                AnimatedVisibility(
                    visible = done,
                    enter = scaleIn(Motion.bouncyFloat) + fadeIn(tween(90)),
                    exit = scaleOut(tween(90)) + fadeOut(tween(90)),
                ) {
                    Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
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
    }
}

/**
 * A task row: coloured priority spine, bouncy tick, meta pills, and — on desktop — hover-revealed
 * Done / Snooze / Edit / Delete buttons. Completed rows fade, strike through and sink a little.
 */
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
    zone: TimeZone? = null,
) {
    val palette = LocalTodoPalette.current
    val use24Hour = LocalUse24HourClock.current
    var menuOpen by remember { mutableStateOf(false) }
    var customExtendOpen by remember { mutableStateOf(false) }
    var confirmRecurringDelete by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val done = item.status == TaskStatus.DONE
    val isRecurring = item.scope == TaskScope.RECURRING_DAILY
    val ringing = Due.isRinging(item, nowMillis)
    val spine = palette.spine(item.priority)

    val sink by animateFloatAsState(if (done) 1f else 0f, Motion.bouncyFloat, label = "sink")
    val lift by animateFloatAsState(if (hovered && !done) 1f else 0f, Motion.bouncyFloat, label = "lift")

    if (confirmRecurringDelete) {
        RecurringDeleteConfirmDialog(
            title = item.title,
            onConfirm = { confirmRecurringDelete = false; onDelete() },
            onCancel = { confirmRecurringDelete = false },
        )
    }

    if (customExtendOpen) {
        ExtendMinutesDialog(
            onConfirm = { minutes -> customExtendOpen = false; onExtend(minutes) },
            onCancel = { customExtendOpen = false },
        )
    }

    Surface(
        onClick = onOpen,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.medium,
        // Opaque on purpose: a translucent surface with elevation shows its own drop shadow
        // through itself (and Skia's occluder rect through that), which looks like a smudge.
        color = when {
            done -> MaterialTheme.colorScheme.surfaceContainerLow
            ringing -> lerp(MaterialTheme.colorScheme.surfaceContainerLowest, palette.highContainer, 0.75f)
            else -> MaterialTheme.colorScheme.surfaceContainerLowest
        },
        tonalElevation = 0.dp,
        shadowElevation = (1.5f + lift * 4f).dp,
        border = if (ringing) BorderStroke(1.5.dp, palette.high.copy(alpha = 0.55f)) else null,
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .graphicsLayer {
                alpha = 1f - 0.38f * sink
                translationY = 3f * sink
                scaleX = 1f - 0.012f * sink
                scaleY = 1f - 0.012f * sink
            },
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Priority spine
            Box(
                Modifier
                    .padding(start = 6.dp, top = 8.dp, bottom = 8.dp)
                    .width(5.dp)
                    .fillMaxHeight()
                    .clip(Pill)
                    .background(if (done) MaterialTheme.colorScheme.outlineVariant else spine),
            )

            BouncyCheck(done = done, onToggle = onToggleDone, tint = spine)

            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.isRecurringInstance) {
                        Icon(
                            Icons.Default.Repeat,
                            contentDescription = "Repeats daily",
                            tint = palette.low,
                            modifier = Modifier.size(15.dp).padding(end = 1.dp),
                        )
                        Spacer(Modifier.width(5.dp))
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
                    modifier = Modifier.padding(top = 5.dp),
                ) {
                    PriorityBadge(item.priority, pulsing = ringing)
                    if (zone != null && item.dueAt != null) {
                        InfoPill(
                            text = TimeText.formatCompact(DateMath.minutesOfDay(item.dueAt, zone), use24Hour),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CountdownChip(item, nowMillis)
                    if (item.extensionCount > 0) {
                        InfoPill(
                            text = "pushed ×${item.extensionCount}",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Desktop: hovering a row reveals the four things you actually want to do to it.
            AnimatedVisibility(
                visible = hovered,
                enter = fadeIn(tween(110)) + scaleIn(Motion.bouncyFloat, initialScale = 0.7f),
                exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.8f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowAction(Icons.Default.Check, if (done) "Reopen" else "Done", palette.low, onToggleDone)
                    RowAction(Icons.Outlined.Snooze, "Push back 15m", palette.medium) { onExtend(15) }
                    RowAction(Icons.Outlined.EditCalendar, "Edit", MaterialTheme.colorScheme.onSurfaceVariant, onOpen)
                    RowAction(Icons.Outlined.DeleteOutline, "Delete", palette.high) {
                        if (isRecurring) confirmRecurringDelete = true else onDelete()
                    }
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    ExtensionRules.presetMinutes.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text("Push back +${minutes}m") },
                            onClick = { menuOpen = false; onExtend(minutes) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Push back by…") },
                        onClick = { menuOpen = false; customExtendOpen = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { menuOpen = false; onOpen() },
                    )
                    if (item.status == TaskStatus.PENDING) {
                        DropdownMenuItem(
                            text = { Text("Dismiss") },
                            leadingIcon = { Icon(Icons.Outlined.NotificationsOff, null) },
                            onClick = { menuOpen = false; onDismiss() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (isRecurring) "Delete (all days)" else "Delete") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
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

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier.size(36.dp).bouncyPress(interaction, squish = 0.85f),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
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
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        title = { Text("Delete \"$title\"?") },
        text = { Text("Delete this daily task for all days? Use Dismiss to skip just today.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete for all days") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep it") } },
    )
}

/** Friendly empty state with Canvas artwork. */
@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    art: EmptyArt = EmptyArt.COFFEE,
) {
    val palette = LocalTodoPalette.current
    val pop = rememberPop(art, peak = 1.12f, start = 0.8f)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyIllustration(
            art = art,
            modifier = Modifier
                .size(130.dp)
                .graphicsLayer {
                    scaleX = pop
                    scaleY = pop
                },
            primary = MaterialTheme.colorScheme.primary,
            secondary = palette.low,
            accent = palette.medium,
            soft = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Progress ring for "4 of 7 done", with a gradient sweep that springs to its new value. */
@Composable
fun ProgressRing(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier.size(74.dp),
) {
    val palette = LocalTodoPalette.current
    val target = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(target, Motion.bouncyFloat, label = "ring")
    val brush = Brush.linearGradient(listOf(palette.medium, MaterialTheme.colorScheme.primary))
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val stroke = size.minDimension * 0.12f
            val inset = stroke / 2f
            drawArc(
                color = palette.ringTrack,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (fraction > 0f) {
                drawArc(
                    brush = brush,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceAtMost(1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (total <= 0) "–" else "$done/$total",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "done",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
