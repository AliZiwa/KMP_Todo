package dev.aliziwa.todo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.assistant.AssistantMode
import dev.aliziwa.todo.assistant.ChatMessage
import dev.aliziwa.todo.assistant.ChatRole
import dev.aliziwa.todo.assistant.PlanSource
import dev.aliziwa.todo.assistant.PlanState
import dev.aliziwa.todo.assistant.PlannedAction
import dev.aliziwa.todo.assistant.ResolvedPlan

/**
 * Assistant chat. Every plan arrives as a confirmation card — nothing is written to the database
 * until the user taps Apply.
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    mode: AssistantMode,
    busy: Boolean,
    statusLine: String,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
    onReject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onReenable: () -> Unit,
    /**
     * Name of the recommended on-device model, shown in the empty state when none is set up.
     * MUST be null on platforms whose engine ships an OS-provided model — there is nothing to
     * download there, so the copy must never say "download X".
     */
    suggestedModelName: String? = null,
    /** Name of the OS-provided model (e.g. "Apple Intelligence") when the engine has one. */
    builtInModelName: String? = null,
    /** The built-in engine is in an error state; the user-facing reason is on the Settings screen. */
    builtInModelFailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        BackendBanner(mode, statusLine, onOpenSettings, onReenable)

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        "Just tell me",
                        "\"gym tomorrow at 7\" · \"call mum every day at 18:00\" · \"done with laundry\"",
                        art = EmptyArt.CHAT,
                    )
                    if (mode != AssistantMode.READY) {
                        Text(
                            when {
                                builtInModelName != null && builtInModelFailed ->
                                    "$builtInModelName is unavailable right now — Settings says why. " +
                                        "The built-in parser still works."
                                builtInModelName != null ->
                                    "$builtInModelName runs this on your device. The built-in parser " +
                                        "covers the basics meanwhile."
                                suggestedModelName != null ->
                                    "Works offline without a model. For full natural language, " +
                                        "download $suggestedModelName in Settings."
                                else ->
                                    "Works offline without a model. Connect a local server in Settings " +
                                        "for full natural language."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                        )
                        SelectableChip(
                            text = if (builtInModelName != null) "Open Settings" else "Set up a model",
                            selected = false,
                            onClick = onOpenSettings,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        onConfirm = onConfirm,
                        onReject = onReject,
                        modifier = Modifier.animateItem(placementSpec = Motion.bouncyOffset),
                    )
                }
                if (busy) {
                    item("typing") { TypingBubble() }
                }
            }
        }

        // The composer must never hide under the software keyboard. `WindowInsets.safeDrawing` at
        // the app Scaffold already includes the IME and `windowInsetsPadding` CONSUMES what it
        // applies, so this is a no-op there and never double-pads — it only kicks in if the shell
        // ever stops padding the IME itself.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Tell me what to do…") },
                    enabled = !busy,
                    maxLines = 3,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                )
                if (busy) {
                    TypingDots(Modifier.padding(horizontal = 4.dp))
                    TextButton(onClick = onCancel, shape = Pill) { Text("Stop") }
                } else {
                    val sendInteraction = remember { MutableInteractionSource() }
                    val enabled = input.isNotBlank()
                    Surface(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                onSend(text)
                                input = ""
                            }
                        },
                        enabled = enabled,
                        interactionSource = sendInteraction,
                        shape = Pill,
                        color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(50.dp).bouncyPress(sendInteraction, squish = 0.86f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackendBanner(
    mode: AssistantMode,
    statusLine: String,
    onOpenSettings: () -> Unit,
    onReenable: () -> Unit,
) {
    val palette = LocalTodoPalette.current
    val (bg, fg) = when (mode) {
        AssistantMode.READY -> palette.lowContainer to palette.onLow
        AssistantMode.RULES_ONLY ->
            MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        AssistantMode.MANUAL_FALLBACK -> palette.highContainer to palette.onHigh
    }
    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(Pill).background(fg.copy(alpha = 0.7f)))
            Spacer(Modifier.width(10.dp))
            Text(statusLine, style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.weight(1f))
            when (mode) {
                AssistantMode.MANUAL_FALLBACK -> TextButton(onClick = onReenable, shape = Pill) { Text("Retry") }
                AssistantMode.RULES_ONLY -> TextButton(onClick = onOpenSettings, shape = Pill) { Text("Settings") }
                AssistantMode.READY -> Unit
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    onConfirm: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == ChatRole.USER
    val isSystem = message.role == ChatRole.SYSTEM
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = when {
                isUser -> MaterialTheme.colorScheme.primary
                isSystem -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
            shape = if (isUser) {
                RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)
            } else {
                RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp)
            },
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Text(
                message.text,
                style = if (isSystem) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                color = when {
                    isUser -> MaterialTheme.colorScheme.onPrimary
                    isSystem -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            )
        }
        val plan = message.plan
        if (plan != null && (plan.actions.isNotEmpty() || plan.warnings.isNotEmpty())) {
            PlanCard(
                plan = plan,
                state = message.planState,
                onConfirm = { onConfirm(message.id) },
                onReject = { onReject(message.id) },
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 460.dp),
            )
        }
    }
}

/** Three dots bouncing in sequence — only ever on screen while the assistant is thinking. */
@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0f at 0
                        -7f at 150 + index * 110
                        0f at 320 + index * 110
                        0f at 900
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(8.dp)
                    .graphicsLayer { translationY = lift }
                    .clip(Pill)
                    .background(color),
            )
        }
    }
}

@Composable
private fun TypingBubble() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp),
    ) {
        TypingDots(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun PlanCard(
    plan: ResolvedPlan,
    state: PlanState,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTodoPalette.current
    val nothingApplicable = plan.actions.isEmpty()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (nothingApplicable) palette.highContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (nothingApplicable) "Nothing to apply" else "Here's the plan",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (nothingApplicable) palette.onHigh else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                InfoPill(
                    text = if (plan.source == PlanSource.RULES) "built-in parser" else "model",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            plan.actions.forEach { action ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = iconFor(action, palette)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = Pill,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        action.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            plan.warnings.forEach { warning ->
                Text(
                    "⚠ $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (nothingApplicable) palette.onHigh else palette.high,
                )
            }
            when (state) {
                PlanState.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val applyInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = onConfirm,
                        shape = Pill,
                        interactionSource = applyInteraction,
                        modifier = Modifier.bouncyPress(applyInteraction),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Apply")
                    }
                    TextButton(onClick = onReject, shape = Pill) {
                        Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Discard")
                    }
                }
                PlanState.CONFIRMED -> AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + scaleIn(Motion.bouncyFloat) + slideInVertically(),
                    exit = fadeOut(),
                ) {
                    InfoPill(
                        text = "Applied",
                        containerColor = palette.lowContainer,
                        contentColor = palette.onLow,
                        leading = {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = palette.onLow,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
                PlanState.REJECTED -> InfoPill(
                    text = "Discarded",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlanState.NONE -> Unit
            }
        }
    }
}

private fun iconFor(action: PlannedAction, palette: TodoPalette): Pair<ImageVector, Color> = when (action) {
    is PlannedAction.Add -> Icons.Outlined.AddCircleOutline to palette.low
    is PlannedAction.Update -> Icons.Outlined.EditCalendar to palette.medium
    is PlannedAction.Complete -> Icons.Outlined.CheckCircle to palette.low
    is PlannedAction.Delete -> Icons.Outlined.DeleteOutline to palette.high
    is PlannedAction.Extend -> Icons.Outlined.Snooze to palette.medium
    is PlannedAction.ListTasks -> Icons.AutoMirrored.Outlined.List to palette.medium
}
