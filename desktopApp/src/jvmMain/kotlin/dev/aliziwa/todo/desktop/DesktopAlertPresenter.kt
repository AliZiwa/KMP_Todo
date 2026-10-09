package dev.aliziwa.todo.desktop

import dev.aliziwa.todo.platform.AlertPresenter
import dev.aliziwa.todo.platform.DueAlert
import dev.aliziwa.todo.platform.SoundPlayer
import dev.aliziwa.todo.platform.StickySummary
import java.awt.Taskbar

/**
 * macOS surfaces for the shared scheduler's decisions.
 *
 * - `showDueAlert` raises an always-on-top undecorated window (one at a time; the shared DueEngine
 *   owns the queue, so a second call simply replaces what is shown) and starts the looping doorbell.
 * - `updateSticky` drives the menu-bar tooltip (through [DesktopUiController.sticky], which the
 *   tray composable observes and re-renders every second) and the Dock badge.
 * - `showDailyPrompt` opens the "Plan your day" window hosting `App(graph, initialRoute = "plan")`.
 *
 * Idempotent as the port requires: showing the same alert twice neither duplicates the window nor
 * restarts the doorbell.
 */
class DesktopAlertPresenter(
    private val ui: DesktopUiController,
    private val sound: SoundPlayer,
    private val soundEnabled: () -> Boolean = { true },
) : AlertPresenter {

    override fun showDueAlert(alert: DueAlert) {
        val already = ui.alert.value?.taskId == alert.taskId
        ui.showAlert(alert)
        ui.bringToFront()
        if (!already) {
            DesktopLog.i(TAG, "showing due alert for ${alert.taskId} (${alert.title})")
            if (soundEnabled()) sound.playDoorbell(loop = true)
        }
    }

    override fun dismissDueAlert(taskId: String) {
        if (ui.alert.value?.taskId == taskId || ui.alert.value == null) {
            sound.stop()
        }
        ui.dismissAlert(taskId)
        DesktopLog.i(TAG, "dismissed due alert for $taskId")
    }

    override fun updateSticky(summary: StickySummary) {
        ui.updateSticky(summary)
        updateDockBadge(summary)
    }

    override fun showDailyPrompt() {
        DesktopLog.i(TAG, "showing daily plan prompt")
        ui.setDailyPromptVisible(true)
        ui.bringToFront()
    }

    override fun dismissDailyPrompt() {
        ui.setDailyPromptVisible(false)
    }

    /**
     * Dock badge: the count of things demanding attention (due now, else remaining today).
     * `java.awt.Taskbar` is optional on every platform, so every call is guarded.
     */
    private fun updateDockBadge(summary: StickySummary) {
        val badge = when {
            summary.dueNowCount > 0 -> summary.dueNowCount.toString()
            summary.remainingTodayCount > 0 -> summary.remainingTodayCount.toString()
            else -> null
        }
        if (badge == lastBadge) return
        runCatching {
            if (!Taskbar.isTaskbarSupported()) {
                DesktopLog.i(TAG, "no Taskbar support; skipping Dock badge")
                lastBadge = badge
                return
            }
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) {
                taskbar.setIconBadge(badge)
                DesktopLog.i(TAG, "Dock badge = ${badge ?: "(cleared)"}")
            } else {
                DesktopLog.i(TAG, "ICON_BADGE_TEXT unsupported; skipping Dock badge")
            }
            lastBadge = badge
        }.onFailure { DesktopLog.w(TAG, "could not set Dock badge", it) }
    }

    @Volatile private var lastBadge: String? = NO_BADGE_YET

    private companion object {
        const val TAG = "alerts"
        /** Sentinel distinct from "no badge" so the first update always applies. */
        const val NO_BADGE_YET = "\u0000"
    }
}
