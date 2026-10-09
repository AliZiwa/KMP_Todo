package dev.aliziwa.todo.android

import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.platform.StickySummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide, UI-independent mirror of what the shared layer has pushed through
 * [dev.aliziwa.todo.platform.AlertPresenter].
 *
 * Why it exists: the presenter is called from the shared layer (possibly with no UI and no
 * service running), while the surfaces that render it — [StickyForegroundService] and
 * [DueAlertActivity] — come and go. They observe this instead of being called directly.
 */
object AlertBus {
    private val _alerts = MutableStateFlow<List<DueAlert>>(emptyList())

    /** Active (not yet acted on) due alerts, oldest first. The head is the one being shown. */
    val alerts: StateFlow<List<DueAlert>> = _alerts.asStateFlow()

    private val _sticky = MutableStateFlow<StickySummary?>(null)
    val sticky: StateFlow<StickySummary?> = _sticky.asStateFlow()

    private val _dailyPromptVisible = MutableStateFlow(false)
    val dailyPromptVisible: StateFlow<Boolean> = _dailyPromptVisible.asStateFlow()

    fun addAlert(alert: DueAlert) = _alerts.update { current ->
        if (current.any { it.taskId == alert.taskId }) {
            current.map { if (it.taskId == alert.taskId) alert else it }
        } else {
            current + alert
        }
    }

    fun removeAlert(taskId: String) = _alerts.update { it.filterNot { a -> a.taskId == taskId } }

    fun alertFor(taskId: String): DueAlert? = _alerts.value.firstOrNull { it.taskId == taskId }

    fun setSticky(summary: StickySummary) { _sticky.value = summary }

    fun setDailyPromptVisible(visible: Boolean) { _dailyPromptVisible.value = visible }
}
