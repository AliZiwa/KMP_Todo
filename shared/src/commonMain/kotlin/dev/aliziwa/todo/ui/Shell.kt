package dev.aliziwa.todo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.domain.DateMath
import kotlinx.datetime.LocalDate

/** The four task boards. Which one is showing is shell state, not a route. */
enum class BoardTab(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), RECURRING("Routines") }

/** Everything the rail / bottom bar can take you to. */
sealed interface NavTarget {
    data class Board(val tab: BoardTab) : NavTarget
    data object Chat : NavTarget
    data object Settings : NavTarget
}

internal class NavEntry(
    val target: NavTarget,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
)

internal val navEntries: List<NavEntry> = listOf(
    NavEntry(NavTarget.Board(BoardTab.DAY), "Day", Icons.Filled.Today, Icons.Outlined.Today),
    NavEntry(NavTarget.Board(BoardTab.WEEK), "Week", Icons.Filled.DateRange, Icons.Outlined.DateRange),
    NavEntry(NavTarget.Board(BoardTab.MONTH), "Month", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    NavEntry(NavTarget.Board(BoardTab.RECURRING), "Daily", Icons.Filled.Autorenew, Icons.Outlined.Autorenew),
    NavEntry(NavTarget.Chat, "Chat", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    NavEntry(NavTarget.Settings, "Setup", Icons.Filled.Settings, Icons.Outlined.Settings),
)

private val RailItemHeight = 70.dp
private val RailWidth = 92.dp

/**
 * Desktop navigation: a rail with a selection indicator that springs between items and icons that
 * bounce when they become selected.
 */
@Composable
fun AppNavRail(
    selected: NavTarget?,
    onSelect: (NavTarget) -> Unit,
    modifier: Modifier = Modifier,
    dueNowCount: Int = 0,
) {
    val palette = LocalTodoPalette.current
    val selectedIndex = navEntries.indexOfFirst { it.target == selected }
    val indicatorOffset by animateDpAsState(
        RailItemHeight * selectedIndex.coerceAtLeast(0),
        Motion.bouncyDp,
        label = "railIndicator",
    )
    val indicatorAlpha by animateFloatAsState(if (selectedIndex >= 0) 1f else 0f, tween(160), label = "railAlpha")

    Column(
        modifier
            .width(RailWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.fabBrush),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(22.dp))

        Box(Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .offset(y = indicatorOffset)
                    .padding(horizontal = 10.dp)
                    .fillMaxWidth()
                    .height(RailItemHeight)
                    .graphicsLayer { alpha = indicatorAlpha },
            ) {}
            Column(Modifier.fillMaxWidth()) {
                navEntries.forEach { entry ->
                    RailItem(
                        entry = entry,
                        selected = entry.target == selected,
                        badge = if (entry.target == NavTarget.Board(BoardTab.DAY)) dueNowCount else 0,
                        onClick = { onSelect(entry.target) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RailItem(entry: NavEntry, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val palette = LocalTodoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pop = rememberPop(selected, peak = if (selected) 1.45f else 1f)
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(180),
        label = "railTint",
    )
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        color = androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(RailItemHeight)
            .padding(horizontal = 10.dp)
            .bouncyPress(interaction, squish = 0.92f),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box {
                Icon(
                    if (selected) entry.selectedIcon else entry.icon,
                    contentDescription = entry.label,
                    tint = tint,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = pop
                            scaleY = pop
                        },
                )
                if (badge > 0) {
                    Surface(
                        color = palette.high,
                        shape = Pill,
                        modifier = Modifier.offset(x = 14.dp, y = (-6).dp),
                    ) {
                        Text(
                            badge.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                entry.label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Phone-width fallback so the same UI keeps working on Android and iOS. */
@Composable
fun AppBottomBar(
    selected: NavTarget?,
    onSelect: (NavTarget) -> Unit,
    modifier: Modifier = Modifier,
    dueNowCount: Int = 0,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navEntries.forEach { entry ->
                BottomItem(
                    entry = entry,
                    selected = entry.target == selected,
                    badge = if (entry.target == NavTarget.Board(BoardTab.DAY)) dueNowCount else 0,
                    onClick = { onSelect(entry.target) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomItem(
    entry: NavEntry,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTodoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pop = rememberPop(selected, peak = if (selected) 1.4f else 1f)
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        tween(180),
        label = "bottomContainer",
    )
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(180),
        label = "bottomTint",
    )
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        color = container,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.padding(horizontal = 2.dp).bouncyPress(interaction, squish = 0.9f),
    ) {
        Column(
            Modifier.padding(vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Icon(
                    if (selected) entry.selectedIcon else entry.icon,
                    contentDescription = entry.label,
                    tint = tint,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = pop
                            scaleY = pop
                        },
                )
                if (badge > 0) {
                    Surface(color = palette.high, shape = Pill, modifier = Modifier.offset(x = 12.dp, y = (-5).dp)) {
                        Text(
                            badge.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            Text(entry.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        }
    }
}

/** "Good morning, Ali" + today's date + a progress ring for today. */
@Composable
fun GreetingHeader(
    name: String,
    today: LocalDate,
    hourOfDay: Int,
    done: Int,
    remaining: Int,
    dueNow: Int,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTodoPalette.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .background(palette.headerBrush)
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${greetingFor(hourOfDay)}, $name",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    DateMath.shortLabel(today),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dueNow > 0) {
                        InfoPill(
                            text = if (dueNow == 1) "1 due now" else "$dueNow due now",
                            containerColor = palette.highContainer,
                            contentColor = palette.onHigh,
                        )
                    }
                    InfoPill(
                        text = when (remaining) {
                            0 -> "all clear"
                            1 -> "1 to go"
                            else -> "$remaining to go"
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ProgressRing(done = done, total = done + remaining, modifier = Modifier.size(78.dp))
        }
    }
}

internal fun greetingFor(hourOfDay: Int): String = when (hourOfDay) {
    in 0..4 -> "Still up"
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

/** Content never stretches wider than a comfortable reading column. */
@Composable
fun ContentColumn(modifier: Modifier = Modifier, maxWidth: Int = 760, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth.dp).fillMaxSize()) { content() }
    }
}

/** The plus that rotates into an X while the editor is open. */
@Composable
fun GradientFab(
    editorOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTodoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val rotation by animateFloatAsState(if (editorOpen) 135f else 0f, Motion.bouncyFloat, label = "fabRotation")
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(22.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        shadowElevation = 8.dp,
        modifier = modifier.size(62.dp).bouncyPress(interaction, squish = 0.88f),
    ) {
        Box(
            Modifier.fillMaxSize().background(palette.fabBrush),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = if (editorOpen) "Close" else "Add task",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(30.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
    }
}
