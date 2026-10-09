package dev.aliziwa.todo.desktop

import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.platform.StickySummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The bridge between the (non-composable) platform ports and Compose's `application { }`
 * composition. Ports push state here from any thread; the composition observes these flows and
 * creates/destroys windows accordingly.
 *
 * Keeping all window state in one place is what makes "closing the window hides it, the process
 * keeps running" work: nothing in the composition ever calls `exitApplication` except [requestQuit].
 */
class DesktopUiController {

    private val _sticky = MutableStateFlow<StickySummary?>(null)
    val sticky: StateFlow<StickySummary?> = _sticky.asStateFlow()

    private val _alert = MutableStateFlow<DueAlert?>(null)
    val alert: StateFlow<DueAlert?> = _alert.asStateFlow()

    private val _dailyPromptVisible = MutableStateFlow(false)
    val dailyPromptVisible: StateFlow<Boolean> = _dailyPromptVisible.asStateFlow()

    private val _mainWindowVisible = MutableStateFlow(true)
    val mainWindowVisible: StateFlow<Boolean> = _mainWindowVisible.asStateFlow()

    /** Route handed to `App(graph, initialRoute)`; bumping [routeNonce] re-creates the window. */
    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route.asStateFlow()

    private val _routeNonce = MutableStateFlow(0)
    val routeNonce: StateFlow<Int> = _routeNonce.asStateFlow()

    /** Incremented whenever something wants the main window raised above other apps. */
    private val _frontNonce = MutableStateFlow(0)
    val frontNonce: StateFlow<Int> = _frontNonce.asStateFlow()

    private val _quitRequested = MutableStateFlow(false)
    val quitRequested: StateFlow<Boolean> = _quitRequested.asStateFlow()

    fun updateSticky(summary: StickySummary?) {
        _sticky.value = summary
    }

    fun showAlert(alert: DueAlert) {
        _alert.value = alert
    }

    fun dismissAlert(taskId: String) {
        _alert.update { if (it == null || it.taskId == taskId) null else it }
    }

    fun clearAlert() {
        _alert.value = null
    }

    fun setDailyPromptVisible(visible: Boolean) {
        _dailyPromptVisible.value = visible
    }

    /** Opens (or re-focuses) the main window, optionally deep-linking to [route]. */
    fun openMainWindow(route: String? = null) {
        if (route != null && route != _route.value) {
            _route.value = route
            _routeNonce.update { it + 1 }
        }
        _mainWindowVisible.value = true
        bringToFront()
    }

    /** Window close button: hide, never exit. */
    fun hideMainWindow() {
        _mainWindowVisible.value = false
    }

    fun bringToFront() {
        _frontNonce.update { it + 1 }
    }

    fun requestQuit() {
        _quitRequested.value = true
    }
}
