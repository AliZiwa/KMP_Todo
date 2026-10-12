package dev.aliziwa.todo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.domain.DateMath
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Instant

/**
 * The app's one time control. Shows the current value as a big pill and opens a picker with
 * scrollable, snapping hour/minute wheels, an AM/PM toggle, quick chips, free-text entry
 * ("3pm", "15:30", "7.45", "noon") and Clear.
 *
 * Used by the task editor, the morning plan sheet and the daily-prompt setting, so a time is
 * entered the same way everywhere.
 */
@Composable
fun TimePickerField(
    minutes: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    helper: String? = null,
    use24Hour: Boolean = LocalUse24HourClock.current,
    /** Minutes-of-day "right now", enabling the +15m / +1h chips. */
    nowMinutes: Int? = null,
    allowClear: Boolean = true,
    initiallyOpen: Boolean = false,
    /**
     * Renders the pill at [SelectableChip] scale so the two can share a row at phone width.
     * Off by default, so the task editor and the settings screen keep the big, tappable pill.
     */
    compact: Boolean = false,
) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val palette = LocalTodoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hasValue = minutes != null

    Column(modifier) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp, start = 2.dp),
            )
        }
        Surface(
            onClick = { open = true },
            interactionSource = interaction,
            shape = Pill,
            color = if (hasValue) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .then(if (compact) Modifier.heightIn(min = 40.dp) else Modifier)
                .bouncyPress(interaction),
        ) {
            Row(
                if (compact) {
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                } else {
                    Modifier.padding(horizontal = 18.dp, vertical = 11.dp)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = if (hasValue) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (compact) 16.dp else 20.dp),
                )
                Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
                Text(
                    TimeText.formatOrNone(minutes, use24Hour),
                    style = if (compact) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    color = if (hasValue) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (helper != null) {
            Text(
                helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
    }

    if (open) {
        TimePickerDialog(
            initial = minutes,
            use24Hour = use24Hour,
            nowMinutes = nowMinutes,
            allowClear = allowClear,
            onConfirm = { open = false; onChange(it) },
            onCancel = { open = false },
        )
    }
}

@Composable
fun TimePickerDialog(
    initial: Int?,
    use24Hour: Boolean,
    nowMinutes: Int?,
    allowClear: Boolean,
    onConfirm: (Int?) -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalTodoPalette.current
    val start = initial ?: 9 * 60
    var value by remember { mutableIntStateOf(start) }
    var text by remember { mutableStateOf("") }

    val hourValues = remember(use24Hour) { TimeText.hourWheelValues(use24Hour) }
    val wheelHour = if (use24Hour) value / 60 else TimeText.displayHour12(value / 60)
    val pm = TimeText.isPm(value)

    fun setParts(hour: Int = wheelHour, minute: Int = value % 60, afternoon: Boolean = pm) {
        value = TimeText.fromParts(hour, minute, use24Hour, afternoon)
    }

    AlertDialog(
        onDismissRequest = onCancel,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        title = {
            Column {
                Text("Pick a time", style = MaterialTheme.typography.headlineSmall)
                Text(
                    TimeText.format(value, use24Hour),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WheelPicker(
                        values = hourValues,
                        label = { TimeText.hourWheelLabel(it, use24Hour) },
                        selected = wheelHour,
                        onSelected = { setParts(hour = it) },
                        modifier = Modifier.width(74.dp),
                    )
                    Text(":", style = MaterialTheme.typography.headlineMedium)
                    WheelPicker(
                        values = remember { (0..59).toList() },
                        label = { TimeText.pad(it) },
                        selected = value % 60,
                        onSelected = { setParts(minute = it) },
                        modifier = Modifier.width(74.dp),
                    )
                    if (!use24Hour) {
                        Spacer(Modifier.width(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MeridiemButton("AM", selected = !pm) { setParts(afternoon = false) }
                            MeridiemButton("PM", selected = pm) { setParts(afternoon = true) }
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickChip("Morning") { value = 9 * 60 }
                    QuickChip("Noon") { value = 12 * 60 }
                    QuickChip(TimeText.formatCompact(15 * 60, use24Hour)) { value = 15 * 60 }
                    QuickChip(TimeText.formatCompact(18 * 60, use24Hour)) { value = 18 * 60 }
                    if (nowMinutes != null) {
                        QuickChip("+15m") { value = TimeText.roundUpTo(TimeText.shift(nowMinutes, 15), 5) }
                        QuickChip("+1h") { value = TimeText.roundUpTo(TimeText.shift(nowMinutes, 60), 5) }
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { typed ->
                        text = typed
                        TimeText.parse(typed)?.let { value = it }
                    },
                    label = { Text("Or type it") },
                    placeholder = { Text("3pm · 15:30 · 7.45 · noon") },
                    isError = text.isNotBlank() && TimeText.parse(text) == null,
                    supportingText = {
                        Text(
                            when {
                                text.isBlank() -> "Understands \"3pm\", \"15:30\", \"7.45\", \"noon\"."
                                TimeText.parse(text) == null -> "Not a time I can read."
                                else -> "Reads as ${TimeText.format(TimeText.parse(text)!!, use24Hour)}"
                            },
                        )
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }, shape = Pill) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Set ${TimeText.format(value, use24Hour)}")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (allowClear) {
                    TextButton(onClick = { onConfirm(null) }) { Text("No time") }
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

/** Vertical snapping wheel. The centred row is the value; neighbours fade and shrink. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelPicker(
    values: List<Int>,
    label: (Int) -> String,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // 44dp is the smallest comfortable touch target; the fling snaps to whole items either way.
    itemHeight: Dp = 44.dp,
    visibleItems: Int = 5,
) {
    val startIndex = values.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val fling = rememberSnapFlingBehavior(listState)
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }

    val centred by remember(itemPx) {
        derivedStateOf {
            val offset = listState.firstVisibleItemScrollOffset
            listState.firstVisibleItemIndex + if (offset > itemPx / 2f) 1 else 0
        }
    }

    // Report the settled value upwards, and follow the value when it is changed elsewhere
    // (quick chips, typed text, AM/PM flip).
    LaunchedEffect(centred, listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            values.getOrNull(centred)?.let { if (it != selected) onSelected(it) }
        }
    }
    LaunchedEffect(selected) {
        val target = values.indexOf(selected)
        if (target >= 0 && target != centred && !listState.isScrollInProgress) {
            listState.animateScrollToItem(target)
        }
    }

    Box(
        modifier.height(itemHeight * visibleItems),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().height(itemHeight),
        ) {}
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = itemHeight * ((visibleItems - 1) / 2)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(values) { index, item ->
                val distance = abs(index - centred).coerceAtMost(3)
                val scale by animateFloatAsState(1f - distance * 0.13f, tween(140), label = "wheelScale")
                val colour by animateColorAsState(
                    if (distance == 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 1f - distance * 0.28f),
                    tween(140),
                    label = "wheelColour",
                )
                Box(
                    Modifier.fillMaxWidth().height(itemHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(item),
                        style = if (distance == 0) MaterialTheme.typography.headlineSmall
                        else MaterialTheme.typography.titleMedium,
                        color = colour,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MeridiemButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(160),
        label = "meridiem",
    )
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = Pill,
        color = container,
        modifier = Modifier.width(56.dp).bouncyPress(interaction),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 11.dp).fillMaxWidth(),
        )
    }
}

/** Small pill button used for the picker's presets. */
@Composable
fun QuickChip(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = Pill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.heightIn(min = 40.dp).bouncyPress(interaction),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * Date control: the familiar presets plus a real calendar. The chosen date always shows in the
 * pill, so there is never a hidden "which date is this?".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    date: LocalDate,
    onChange: (LocalDate) -> Unit,
    presets: List<Pair<String, LocalDate>>,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = { open = true },
                interactionSource = interaction,
                shape = Pill,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.bouncyPress(interaction),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        DateMath.shortLabel(date),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { open = true }) { Text("Pick a date…") }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { (text, value) ->
                SelectableChip(text = text, selected = date == value, onClick = { onChange(value) })
            }
        }
    }

    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.utcMillis())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                Button(
                    onClick = {
                        state.selectedDateMillis?.let { onChange(localDateOf(it)) }
                        open = false
                    },
                    shape = Pill,
                ) { Text("Use this date") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            DatePicker(state = state, showModeToggle = true)
        }
    }
}

/** The app's filter chip: pill-shaped, springy, colour-animated. */
@Composable
fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(180),
        label = "chipContainer",
    )
    val content by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(180),
        label = "chipContent",
    )
    val pop = rememberPop(selected, peak = if (selected) 1.1f else 1f)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = Pill,
        color = container,
        modifier = modifier
            // Touch target floor; phones need 40dp+ even though the label is small.
            .heightIn(min = 40.dp)
            .bouncyPress(interaction)
            .graphicsLayer {
                scaleX = pop
                scaleY = pop
            },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

/** "Push back by…" — a wheel over minutes instead of a bare number field. */
@Composable
fun ExtendMinutesDialog(
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
    initialMinutes: Int = 45,
) {
    var minutes by remember { mutableIntStateOf(initialMinutes) }
    val options = remember {
        (listOf(1, 2, 3, 5, 10, 15, 20, 25, 30, 40, 45, 50) + (60..240 step 15)).distinct().sorted()
    }
    AlertDialog(
        onDismissRequest = onCancel,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        title = { Text("Push back by") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WheelPicker(
                        values = options,
                        label = { it.toString() },
                        selected = if (minutes in options) minutes else options.first(),
                        onSelected = { minutes = it },
                        modifier = Modifier.width(90.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("minutes", style = MaterialTheme.typography.titleMedium)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30, 60, 120).forEach { preset ->
                        QuickChip("+${preset}m") { minutes = preset }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(minutes) }, shape = Pill) { Text("Push +${minutes}m") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Material's date picker speaks UTC millis; these two keep that conversion in one place. */
internal fun LocalDate.utcMillis(): Long = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

internal fun localDateOf(utcMillis: Long): LocalDate =
    Instant.fromEpochMilliseconds(utcMillis).toLocalDateTime(TimeZone.UTC).date
