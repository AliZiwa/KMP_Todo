package dev.aliziwa.todo.platform.ios

import dev.aliziwa.todo.AppGraph
import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.platform.StickySummary
import kotlinx.coroutines.flow.StateFlow

/**
 * The three shared streams the iOS layer observes, in one place. Kept as functions so the
 * Swift-facing helpers in [TodoObservers] never reach into the graph themselves.
 */
internal fun AppGraph.stickySummaryFlow(): StateFlow<StickySummary> = dueEngine.stickySummary

internal fun AppGraph.currentAlertFlow(): StateFlow<DueAlert?> = dueEngine.currentAlert

internal fun AppGraph.dailyPromptRequestedFlow(): StateFlow<Boolean> = dueEngine.dailyPromptRequested
