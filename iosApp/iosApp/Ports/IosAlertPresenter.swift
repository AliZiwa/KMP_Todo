import Foundation
import Shared
import UserNotifications

#if canImport(ActivityKit)
import ActivityKit
#endif

/// `AlertPresenter` for iOS.
///
/// * Due alert → immediate time-sensitive notification with the Done / +15 / Dismiss / Delete
///   actions (works with the app closed; the actions are handled by `NotificationDelegate`).
/// * Sticky summary → ActivityKit Live Activity (lock screen + Dynamic Island) with a live
///   countdown, plus a mirror into the App Group for the home-screen widget. When Live
///   Activities are unavailable (Settings toggle off, unsupported device) it degrades to a
///   silent, passive, permanent "today" notification.
final class IosAlertPresenter: NSObject, Shared.AlertPresenter {

    private let center = UNUserNotificationCenter.current()

    #if canImport(ActivityKit)
    private var activity: Activity<TodoActivityAttributes>?
    #endif
    private var liveActivityUnavailable = false

    // MARK: - Due alert

    func showDueAlert(alert: DueAlert) {
        TaskTitleCache.put(taskId: alert.taskId, title: alert.title)

        let content = UNMutableNotificationContent()
        content.title = alert.priority >= 2 ? "Due now (high priority)" : "Due now"
        content.body = alert.title
        content.sound = Notif.doorbellSound
        content.categoryIdentifier = Notif.taskDueCategory
        content.interruptionLevel = .timeSensitive
        content.threadIdentifier = "task-\(alert.taskId)"
        content.userInfo = [
            Notif.keyTaskId: alert.taskId,
            Notif.keyRoute: "alert/\(alert.taskId)",
            Notif.keyKind: "due",
        ]

        let request = UNNotificationRequest(
            identifier: Notif.immediateAlertIdentifier(taskId: alert.taskId),
            content: content,
            trigger: nil // deliver immediately; replaces an identical pending one (idempotent)
        )
        center.add(request) { error in
            if let error {
                NSLog("[todo] failed to present due alert: \(error.localizedDescription)")
            }
        }
    }

    func dismissDueAlert(taskId: String) {
        let ids = (0..<Notif.reAlertCount).map { Notif.alarmIdentifier(taskId: taskId, index: $0) }
            + [Notif.immediateAlertIdentifier(taskId: taskId)]
        center.removePendingNotificationRequests(withIdentifiers: ids)
        center.removeDeliveredNotifications(withIdentifiers: ids)
    }

    // MARK: - Sticky summary

    func updateSticky(summary: StickySummary) {
        let dueAt = summary.nextDueAtEpochMillis.map {
            Date(timeIntervalSince1970: TimeInterval(truncating: $0) / 1000.0)
        }
        StickySnapshot(
            nextTaskTitle: summary.nextTaskTitle,
            nextDueAt: dueAt,
            dueNowCount: Int(summary.dueNowCount),
            remainingTodayCount: Int(summary.remainingTodayCount),
            completedTodayCount: Int(summary.completedTodayCount)
        ).write()

        #if canImport(ActivityKit)
        if #available(iOS 16.2, *), !liveActivityUnavailable {
            updateLiveActivity(summary: summary, dueAt: dueAt)
            return
        }
        #endif
        updateStickyNotification(summary: summary, dueAt: dueAt)
    }

    #if canImport(ActivityKit)
    @available(iOS 16.2, *)
    private func updateLiveActivity(summary: StickySummary, dueAt: Date?) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            liveActivityUnavailable = true
            updateStickyNotification(summary: summary, dueAt: dueAt)
            return
        }

        let hasAnything = summary.nextTaskTitle != nil
            || summary.dueNowCount > 0
            || summary.remainingTodayCount > 0

        let state = TodoActivityAttributes.ContentState(
            taskTitle: summary.nextTaskTitle ?? "Nothing scheduled",
            dueAt: dueAt,
            dueNowCount: Int(summary.dueNowCount),
            remainingTodayCount: Int(summary.remainingTodayCount),
            completedTodayCount: Int(summary.completedTodayCount)
        )

        if let activity {
            if hasAnything {
                _Concurrency.Task { await activity.update(ActivityContent(state: state, staleDate: nil)) }
            } else {
                _Concurrency.Task {
                    await activity.end(ActivityContent(state: state, staleDate: nil), dismissalPolicy: .immediate)
                }
                self.activity = nil
            }
            return
        }

        guard hasAnything else { return }
        do {
            activity = try Activity.request(
                attributes: TodoActivityAttributes(),
                content: ActivityContent(state: state, staleDate: nil),
                pushType: nil
            )
        } catch {
            NSLog("[todo] Live Activity unavailable, falling back to notification: \(error.localizedDescription)")
            liveActivityUnavailable = true
            updateStickyNotification(summary: summary, dueAt: dueAt)
        }
    }
    #endif

    /// Fallback persistent surface: one silent passive notification that is replaced in place.
    private func updateStickyNotification(summary: StickySummary, dueAt: Date?) {
        guard summary.nextTaskTitle != nil || summary.remainingTodayCount > 0 else {
            center.removeDeliveredNotifications(withIdentifiers: [Notif.stickyId])
            return
        }

        let content = UNMutableNotificationContent()
        content.title = "Today"
        var lines: [String] = []
        if let title = summary.nextTaskTitle {
            if let dueAt {
                let formatter = DateFormatter()
                formatter.timeStyle = .short
                lines.append("Next: \(title) at \(formatter.string(from: dueAt))")
            } else {
                lines.append("Next: \(title)")
            }
        }
        lines.append("\(summary.remainingTodayCount) left · \(summary.completedTodayCount) done")
        content.body = lines.joined(separator: "\n")
        content.sound = nil
        content.interruptionLevel = .passive
        content.categoryIdentifier = Notif.stickyCategory
        content.userInfo = [Notif.keyRoute: "today"]

        center.add(UNNotificationRequest(identifier: Notif.stickyId, content: content, trigger: nil))
    }

    // MARK: - Daily prompt

    func showDailyPrompt() {
        let content = UNMutableNotificationContent()
        content.title = "Plan your day"
        content.body = "What do you want to get done today?"
        content.sound = .default
        content.categoryIdentifier = Notif.dailyPromptCategory
        content.interruptionLevel = .active
        content.userInfo = [Notif.keyRoute: "plan", Notif.keyKind: "daily-prompt"]
        center.add(UNNotificationRequest(identifier: Notif.dailyPromptNowId, content: content, trigger: nil))
        AppRouter.shared.open(route: "plan")
    }

    func dismissDailyPrompt() {
        center.removeDeliveredNotifications(withIdentifiers: [Notif.dailyPromptNowId])
    }

    // MARK: - Live Activity lifecycle

    /// Called when the app is torn down / the user turns the feature off.
    func endLiveActivity() {
        #if canImport(ActivityKit)
        if #available(iOS 16.2, *), let activity {
            _Concurrency.Task { await activity.end(nil, dismissalPolicy: .immediate) }
            self.activity = nil
        }
        #endif
    }
}
