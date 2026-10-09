package dev.aliziwa.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.assistant.AssistantMode
import dev.aliziwa.todo.assistant.ChatMessage
import dev.aliziwa.todo.assistant.ChatRole
import dev.aliziwa.todo.assistant.PlanSource
import dev.aliziwa.todo.assistant.PlanState
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
                        "Ask for anything",
                        "\"gym tomorrow at 7\" · \"call mum every day at 18:00\" · \"done with laundry\"",
                    )
                    if (mode != AssistantMode.READY) {
                        OutlinedButton(onClick = onOpenSettings) { Text("Set up a model") }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(message, onConfirm = onConfirm, onReject = onReject)
                }
            }
        }

        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Tell me what to do…") },
                    enabled = !busy,
                    maxLines = 3,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    TextButton(onClick = onCancel) { Text("Stop") }
                } else {
                    IconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                onSend(text)
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank(),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
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
    val (bg, fg) = when (mode) {
        AssistantMode.READY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        AssistantMode.RULES_ONLY -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        AssistantMode.MANUAL_FALLBACK -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(statusLine, style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.weight(1f))
            when (mode) {
                AssistantMode.MANUAL_FALLBACK -> TextButton(onClick = onReenable) { Text("Retry") }
                AssistantMode.RULES_ONLY -> TextButton(onClick = onOpenSettings) { Text("Settings") }
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
) {
    val isUser = message.role == ChatRole.USER
    val isSystem = message.role == ChatRole.SYSTEM
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = when {
                isUser -> MaterialTheme.colorScheme.primary
                isSystem -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                message.text,
                style = if (isSystem) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = when {
                    isUser -> MaterialTheme.colorScheme.onPrimary
                    isSystem -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        val plan = message.plan
        if (plan != null && (plan.actions.isNotEmpty() || plan.warnings.isNotEmpty())) {
            PlanCard(
                plan = plan,
                state = message.planState,
                onConfirm = { onConfirm(message.id) },
                onReject = { onReject(message.id) },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
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
    val nothingApplicable = plan.actions.isEmpty()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (nothingApplicable) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (nothingApplicable) "Nothing to apply" else "Plan",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (nothingApplicable) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (plan.source == PlanSource.RULES) "built-in parser" else "model",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (nothingApplicable) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            plan.actions.forEach { action ->
                Text("• ${action.summary}", style = MaterialTheme.typography.bodyMedium)
            }
            plan.warnings.forEach { warning ->
                Text(
                    "⚠ $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (nothingApplicable) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.error,
                )
            }
            when (state) {
                PlanState.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirm) { Text("Apply") }
                    OutlinedButton(onClick = onReject) { Text("Discard") }
                }
                PlanState.CONFIRMED -> Text(
                    "Applied",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                PlanState.REJECTED -> Text(
                    "Discarded",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PlanState.NONE -> Unit
            }
        }
    }
}
