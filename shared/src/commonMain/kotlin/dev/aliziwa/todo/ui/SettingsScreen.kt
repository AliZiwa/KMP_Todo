package dev.aliziwa.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.data.AppSettings
import dev.aliziwa.todo.data.LlmBackend
import dev.aliziwa.todo.llm.DownloadState
import dev.aliziwa.todo.llm.ModelSuggestion
import dev.aliziwa.todo.platform.EngineState
import dev.aliziwa.todo.util.Format

/** Everything configurable: LLM backend, model download, daily prompt, sound, launch at login. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    engineState: EngineState?,
    hasLocalEngine: Boolean,
    supportedExtensions: List<String>,
    suggestions: List<ModelSuggestion>,
    downloadState: DownloadState,
    testResult: String?,
    assistantInManualFallback: Boolean,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDownload: (url: String, fileName: String?) -> Unit,
    onCancelDownload: () -> Unit,
    onUseModel: (path: String) -> Unit,
    onTestBackend: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onReenableAssistant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var customUrl by remember { mutableStateOf("") }
    var ollamaUrl by remember(settings.ollamaBaseUrl) { mutableStateOf(settings.ollamaBaseUrl) }
    var ollamaModel by remember(settings.ollamaModel) { mutableStateOf(settings.ollamaModel) }
    var promptTime by remember(settings.dailyPromptHour, settings.dailyPromptMinute) {
        mutableStateOf(Format.timeOfDay(settings.dailyPromptHour * 60 + settings.dailyPromptMinute))
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (assistantInManualFallback) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Assistant unavailable — switched to manual",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "Two attempts in a row failed. The built-in parser still works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = onReenableAssistant) { Text("Re-enable assistant") }
                }
            }
        }

        SectionHeader("Assistant engine")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.llmBackend == LlmBackend.RULES_ONLY,
                onClick = { onUpdate { it.copy(llmBackend = LlmBackend.RULES_ONLY) } },
                label = { Text("Rules only") },
            )
            FilterChip(
                selected = settings.llmBackend == LlmBackend.LOCAL_ENGINE,
                onClick = { onUpdate { it.copy(llmBackend = LlmBackend.LOCAL_ENGINE) } },
                enabled = hasLocalEngine,
                label = { Text("On-device model") },
            )
            FilterChip(
                selected = settings.llmBackend == LlmBackend.OLLAMA,
                onClick = { onUpdate { it.copy(llmBackend = LlmBackend.OLLAMA) } },
                label = { Text("Local server (Ollama)") },
            )
        }
        if (!hasLocalEngine) {
            Text(
                "This build has no on-device engine; use rules or a local server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (settings.llmBackend == LlmBackend.LOCAL_ENGINE && hasLocalEngine) {
            HorizontalDivider()
            SectionHeader("On-device model")
            Text(engineStateLabel(engineState), style = MaterialTheme.typography.bodyMedium)
            settings.localModelPath?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Accepted file types: ${supportedExtensions.joinToString(", ") { ".$it" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            suggestions.forEach { suggestion ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(suggestion.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            suggestion.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDownload(suggestion.url, suggestion.fileName) }) { Text("Download") }
                            OutlinedButton(onClick = { onOpenUrl(suggestion.browseUrl) }) { Text("Browse") }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text("Or paste a Hugging Face resolve URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onDownload(customUrl.trim(), null) },
                    enabled = customUrl.trim().startsWith("http"),
                ) { Text("Download URL") }
                if (downloadState is DownloadState.Running || downloadState is DownloadState.Preparing) {
                    OutlinedButton(onClick = onCancelDownload) { Text("Cancel") }
                }
            }
            DownloadStatus(downloadState, onUseModel)
        }

        if (settings.llmBackend == LlmBackend.OLLAMA) {
            HorizontalDivider()
            SectionHeader("Local server")
            OutlinedTextField(
                value = ollamaUrl,
                onValueChange = { ollamaUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ollamaModel,
                onValueChange = { ollamaModel = it },
                label = { Text("Model name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onUpdate { it.copy(ollamaBaseUrl = ollamaUrl.trim(), ollamaModel = ollamaModel.trim()) }
                }) { Text("Save") }
                OutlinedButton(onClick = onTestBackend) { Text("Test") }
            }
            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Only local-network addresses are expected; nothing else ever leaves the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        SectionHeader("Daily plan prompt")
        ToggleRow("Ask me to plan my day", settings.dailyPromptEnabled) { enabled ->
            onUpdate { it.copy(dailyPromptEnabled = enabled) }
        }
        OutlinedTextField(
            value = promptTime,
            onValueChange = { text ->
                promptTime = text
                dev.aliziwa.todo.domain.DateMath.parseTimeOfDay(text)?.let { minutes ->
                    onUpdate { it.copy(dailyPromptHour = minutes / 60, dailyPromptMinute = minutes % 60) }
                }
            },
            label = { Text("Prompt time (HH:MM)") },
            enabled = settings.dailyPromptEnabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()
        SectionHeader("Alerts")
        ToggleRow("Doorbell sound", settings.soundEnabled) { enabled ->
            onUpdate { it.copy(soundEnabled = enabled) }
        }

        HorizontalDivider()
        SectionHeader("System")
        ToggleRow("Launch at login", settings.launchAtLogin) { enabled ->
            onUpdate { it.copy(launchAtLogin = enabled) }
        }
        Text(
            "Keeps alarms exact on desktop. Ignored where the platform does not support it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DownloadStatus(state: DownloadState, onUseModel: (String) -> Unit) {
    when (state) {
        DownloadState.Idle -> Unit
        is DownloadState.Preparing -> Text("Preparing ${state.fileName}…", style = MaterialTheme.typography.bodySmall)
        is DownloadState.Running -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val fraction = state.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                "${state.fileName} — ${state.downloadedBytes / 1_000_000} MB" +
                    (state.totalBytes?.let { " / ${it / 1_000_000} MB" } ?: "") +
                    (if (state.resumed) " (resumed)" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is DownloadState.Completed -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Downloaded ${state.fileName}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onUseModel(state.path) }) { Text("Use this model") }
        }
        is DownloadState.Failed -> Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is DownloadState.Cancelled -> Text(
            "Cancelled — ${state.keptBytes / 1_000_000} MB kept, downloading again resumes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun engineStateLabel(state: EngineState?): String = when (state) {
    null -> "No on-device engine on this platform."
    EngineState.NotLoaded -> "Model not loaded."
    is EngineState.Loading -> "Loading…" + (state.progress?.let { " ${(it * 100).toInt()}%" } ?: "")
    is EngineState.Ready -> "Ready: ${state.modelName}"
    is EngineState.Error -> "Engine error: ${state.message}"
}
