package dev.aliziwa.todo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.aliziwa.todo.APP_DISPLAY_NAME
import dev.aliziwa.todo.AppLinks
import dev.aliziwa.todo.data.AppSettings
import dev.aliziwa.todo.data.LlmBackend
import dev.aliziwa.todo.llm.DownloadState
import dev.aliziwa.todo.llm.ModelSuggestion
import dev.aliziwa.todo.platform.BuiltInModel
import dev.aliziwa.todo.platform.EngineState

/** Everything configurable: your name, LLM backend, model, daily prompt, sound, system, about. */
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
    /** Non-null when the on-device engine uses an OS-provided model: no downloads, just a status. */
    builtInModel: BuiltInModel? = null,
    /** From [dev.aliziwa.todo.platform.PlatformCapabilities]; empty hides the version line. */
    appVersion: String = "",
    platformName: String = "",
    /** Only macOS can install a login item; the card is hidden everywhere else. */
    supportsLaunchAtLogin: Boolean = false,
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
    // Deliberately un-keyed: the field saves on every keystroke, and re-keying on the round-tripped
    // value would fight the user's cursor.
    var userName by remember { mutableStateOf(settings.userName ?: "") }
    var showAcknowledgements by remember { mutableStateOf(false) }
    var ollamaUrl by remember(settings.ollamaBaseUrl) { mutableStateOf(settings.ollamaBaseUrl) }
    var ollamaModel by remember(settings.ollamaModel) { mutableStateOf(settings.ollamaModel) }
    val palette = LocalTodoPalette.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedVisibility(
            visible = assistantInManualFallback,
            enter = fadeIn(tween(160)) + expandVertically(Motion.bouncyFinite()),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(140)),
        ) {
            Surface(
                color = palette.highContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Assistant unavailable — switched to manual",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.onHigh,
                    )
                    Text(
                        "Two attempts in a row failed. The built-in parser still works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onHigh,
                    )
                    TextButton(onClick = onReenableAssistant, shape = Pill) { Text("Re-enable assistant") }
                }
            }
        }

        SettingsCard("You", Icons.Outlined.Person) {
            OutlinedTextField(
                value = userName,
                onValueChange = { typed ->
                    userName = typed
                    onUpdate { it.copy(userName = typed.trim().takeIf { name -> name.isNotEmpty() }) }
                },
                label = { Text("Your name") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("Only used for the greeting.")
        }

        SettingsCard("Assistant engine", Icons.Outlined.AutoAwesome) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectableChip(
                    text = "Rules only",
                    selected = settings.llmBackend == LlmBackend.RULES_ONLY,
                    onClick = { onUpdate { it.copy(llmBackend = LlmBackend.RULES_ONLY) } },
                )
                if (hasLocalEngine) {
                    SelectableChip(
                        // An OS-provided model has a name worth showing ("Apple Intelligence");
                        // a file-based engine is just "the on-device model".
                        text = builtInModel?.displayName ?: "On-device model",
                        selected = settings.llmBackend == LlmBackend.LOCAL_ENGINE,
                        onClick = { onUpdate { it.copy(llmBackend = LlmBackend.LOCAL_ENGINE) } },
                        leading = {
                            Icon(
                                if (builtInModel != null) {
                                    Icons.Outlined.AutoAwesome
                                } else {
                                    Icons.Outlined.Computer
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (settings.llmBackend == LlmBackend.LOCAL_ENGINE) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                    )
                }
                SelectableChip(
                    text = "Local server (Ollama)",
                    selected = settings.llmBackend == LlmBackend.OLLAMA,
                    onClick = { onUpdate { it.copy(llmBackend = LlmBackend.OLLAMA) } },
                    leading = {
                        Icon(
                            Icons.Outlined.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (settings.llmBackend == LlmBackend.OLLAMA) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }
            if (!hasLocalEngine) {
                Hint("This build has no on-device engine; use rules or a local server.")
            }
        }

        if (settings.llmBackend == LlmBackend.LOCAL_ENGINE && hasLocalEngine && builtInModel != null) {
            // An OS-provided model: nothing to download, nothing to point at, nothing to accept.
            SettingsCard(builtInModel.displayName, Icons.Outlined.AutoAwesome) {
                Text(
                    builtInEngineLabel(engineState),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (engineState is EngineState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Hint("Runs entirely on this device. Nothing leaves your phone.")
                testResult?.let { Hint(it) }
                TextButton(onClick = onTestBackend, shape = Pill) { Text("Test") }
            }
        }

        if (settings.llmBackend == LlmBackend.LOCAL_ENGINE && hasLocalEngine && builtInModel == null) {
            SettingsCard("On-device model", Icons.Outlined.CloudDownload) {
                Text(engineStateLabel(engineState), style = MaterialTheme.typography.bodyLarge)
                settings.localModelPath?.let { Hint(it) }
                Hint("Accepted file types: ${supportedExtensions.joinToString(", ") { ".$it" }}")

                suggestions.forEach { suggestion ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(suggestion.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                suggestion.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onDownload(suggestion.url, suggestion.fileName) },
                                    shape = Pill,
                                ) { Text("Download") }
                                TextButton(onClick = { onOpenUrl(suggestion.browseUrl) }, shape = Pill) {
                                    Text("Browse")
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("Or paste a Hugging Face resolve URL") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onDownload(customUrl.trim(), null) },
                        enabled = customUrl.trim().startsWith("http"),
                        shape = Pill,
                    ) { Text("Download URL") }
                    if (downloadState is DownloadState.Running || downloadState is DownloadState.Preparing) {
                        TextButton(onClick = onCancelDownload, shape = Pill) { Text("Cancel") }
                    }
                }
                DownloadStatus(downloadState, onUseModel)
            }
        }

        if (settings.llmBackend == LlmBackend.OLLAMA) {
            SettingsCard("Local server", Icons.Outlined.Dns) {
                OutlinedTextField(
                    value = ollamaUrl,
                    onValueChange = { ollamaUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ollamaModel,
                    onValueChange = { ollamaModel = it },
                    label = { Text("Model name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onUpdate { it.copy(ollamaBaseUrl = ollamaUrl.trim(), ollamaModel = ollamaModel.trim()) }
                        },
                        shape = Pill,
                    ) { Text("Save") }
                    TextButton(onClick = onTestBackend, shape = Pill) { Text("Test") }
                }
                testResult?.let { Hint(it) }
                Hint("Only local-network addresses are expected; nothing else ever leaves the device.")
            }
        }

        SettingsCard("Morning plan", Icons.Outlined.WbSunny) {
            ToggleRow("Ask me to plan my day", settings.dailyPromptEnabled) { enabled ->
                onUpdate { it.copy(dailyPromptEnabled = enabled) }
            }
            AnimatedVisibility(
                visible = settings.dailyPromptEnabled,
                enter = fadeIn(tween(160)) + expandVertically(Motion.bouncyFinite()),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(140)),
            ) {
                TimePickerField(
                    minutes = settings.dailyPromptHour * 60 + settings.dailyPromptMinute,
                    onChange = { minutes ->
                        val value = minutes ?: 0
                        onUpdate { it.copy(dailyPromptHour = value / 60, dailyPromptMinute = value % 60) }
                    },
                    label = "Prompt time",
                    allowClear = false,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SettingsCard("Alerts", Icons.Outlined.NotificationsActive) {
            ToggleRow("Doorbell sound", settings.soundEnabled) { enabled ->
                onUpdate { it.copy(soundEnabled = enabled) }
            }
            ToggleRow("24-hour clock", settings.use24HourClock) { enabled ->
                onUpdate { it.copy(use24HourClock = enabled) }
            }
            Hint("Times show as ${if (settings.use24HourClock) "15:30" else "3:30 PM"} everywhere.")
        }

        // Only shown where a login item exists. `platformName == "macOS"` keeps desktop working
        // until its capabilities class returns supportsLaunchAtLogin = true.
        if (supportsLaunchAtLogin || platformName == "macOS") {
            SettingsCard("System", Icons.Outlined.Schedule) {
                ToggleRow("Launch at login", settings.launchAtLogin) { enabled ->
                    onUpdate { it.copy(launchAtLogin = enabled) }
                }
                Hint("Keeps alarms exact: the app stays resident so a due time never slips.")
            }
        }

        SettingsCard("About", Icons.Outlined.Info) {
            Text(APP_DISPLAY_NAME, style = MaterialTheme.typography.titleMedium)
            val versionLine = listOfNotNull(
                appVersion.takeIf { it.isNotBlank() }?.let { "Version $it" },
                platformName.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (versionLine.isNotEmpty()) Hint(versionLine)
            LinkRow("Privacy policy", external = true) { onOpenUrl(AppLinks.PRIVACY) }
            LinkRow("Support", external = true) { onOpenUrl(AppLinks.SUPPORT) }
            LinkRow("Acknowledgements", external = false) { showAcknowledgements = true }
        }

        Spacer(Modifier.height(80.dp))
    }

    if (showAcknowledgements) {
        AcknowledgementsDialog(onClose = { showAcknowledgements = false })
    }
}

/** A tappable settings row: label on the left, affordance on the right, 48dp tall. */
@Composable
private fun LinkRow(label: String, external: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (external) {
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Open-source components we ship. Short by design; the licences are permissive. */
private val ACKNOWLEDGEMENTS = listOf(
    "Compose Multiplatform — Apache-2.0",
    "AndroidX Room — Apache-2.0",
    "Koog — Apache-2.0",
    "Ktor — Apache-2.0",
    "kotlinx (coroutines, serialization, datetime) — Apache-2.0",
    "llama.cpp — MIT (desktop only)",
    "LiteRT-LM — Apache-2.0 (Android only)",
)

@Composable
private fun AcknowledgementsDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        title = { Text("Acknowledgements", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ACKNOWLEDGEMENTS.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { Button(onClick = onClose, shape = Pill) { Text("Close") } },
    )
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = Pill,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
        is DownloadState.Preparing -> Hint("Preparing ${state.fileName}…")
        is DownloadState.Running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val fraction = state.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Hint(
                "${state.fileName} — ${state.downloadedBytes / 1_000_000} MB" +
                    (state.totalBytes?.let { " / ${it / 1_000_000} MB" } ?: "") +
                    (if (state.resumed) " (resumed)" else ""),
            )
        }
        is DownloadState.Completed -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Hint("Downloaded ${state.fileName}")
            Button(onClick = { onUseModel(state.path) }, shape = Pill) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Use this model")
            }
        }
        is DownloadState.Failed -> Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is DownloadState.Cancelled ->
            Hint("Cancelled — ${state.keptBytes / 1_000_000} MB kept, downloading again resumes.")
    }
}

/**
 * Status for an OS-provided model. An error message here is the platform's own user-facing reason
 * ("Apple Intelligence is turned off in Settings"), so it is shown verbatim.
 */
private fun builtInEngineLabel(state: EngineState?): String = when (state) {
    null, EngineState.NotLoaded -> "Ready when you ask."
    is EngineState.Loading -> "Warming up…"
    is EngineState.Ready -> "Ready."
    is EngineState.Error -> state.message
}

private fun engineStateLabel(state: EngineState?): String = when (state) {
    null -> "No on-device engine on this platform."
    EngineState.NotLoaded -> "Model not loaded."
    is EngineState.Loading -> "Loading…" + (state.progress?.let { " ${(it * 100).toInt()}%" } ?: "")
    is EngineState.Ready -> "Ready: ${state.modelName}"
    is EngineState.Error -> "Engine error: ${state.message}"
}
