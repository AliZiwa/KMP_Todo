import Foundation
import Shared
import UserNotifications

/// `AlarmScheduler` on top of `UNUserNotificationCenter`.
///
/// A due task is not one notification but a **re-alert chain**: `task-<id>-0 … task-<id>-9`,
/// the first at the due time and the rest every 2 minutes, so an ignored alert keeps ringing
/// for ~20 minutes. Acting on the task (`cancel`) removes the whole chain. This is the only
/// mechanism that works when the app is not running — iOS has no AlarmManager equivalent.
///
/// `taskId` is the shared layer's *key*: a plain task id, or `<templateId>@yyyy-MM-dd` for one
/// day's instance of a recurring template. It is only ever interpolated into notification
/// identifiers / `userInfo` (both arbitrary strings, `@` included) and handed back verbatim to
/// `TodoApp.*` — never parsed here.
final class IosAlarmScheduler: NSObject, Shared.AlarmScheduler {

    private let center = UNUserNotificationCenter.current()

    /// The overload the shared layer always calls: the title is authoritative, and a rename
    /// re-arms the alarm so the body stays in sync.
    func schedule(taskId: String, atEpochMillis: Int64, title: String) {
        TaskTitleCache.put(taskId: taskId, title: title)
        schedule(taskId: taskId, atEpochMillis: atEpochMillis, body: title)
    }

    /// Legacy 2-arg port method: no title available, so fall back to the last one we saw.
    func schedule(taskId: String, atEpochMillis: Int64) {
        schedule(taskId: taskId, atEpochMillis: atEpochMillis, body: TaskTitleCache.title(for: taskId))
    }

    private func schedule(taskId: String, atEpochMillis: Int64, body: String?) {
        cancel(taskId: taskId)

        let dueDate = Date(timeIntervalSince1970: TimeInterval(atEpochMillis) / 1000.0)

        for index in 0..<Notif.reAlertCount {
            let fireDate = dueDate.addingTimeInterval(Double(index) * Notif.reAlertIntervalSeconds)
            guard let trigger = Self.trigger(for: fireDate) else { continue }

            let content = UNMutableNotificationContent()
            content.title = index == 0 ? "Task due" : "Still due"
            content.body = body ?? "A task is due now."
            content.sound = Notif.doorbellSound
            content.categoryIdentifier = Notif.taskDueCategory
            content.interruptionLevel = .timeSensitive
            content.threadIdentifier = "task-\(taskId)"
            content.userInfo = [
                Notif.keyTaskId: taskId,
                Notif.keyRoute: "alert/\(taskId)",
                Notif.keyKind: "due",
            ]
            if #available(iOS 15.0, *) {
                content.relevanceScore = 1.0
            }

            let request = UNNotificationRequest(
                identifier: Notif.alarmIdentifier(taskId: taskId, index: index),
                content: content,
                trigger: trigger
            )
            center.add(request) { error in
                if let error {
                    NSLog("[todo] failed to schedule \(request.identifier): \(error.localizedDescription)")
                }
            }
        }
    }

    func cancel(taskId: String) {
        let ids = (0..<Notif.reAlertCount).map { Notif.alarmIdentifier(taskId: taskId, index: $0) }
            + [Notif.immediateAlertIdentifier(taskId: taskId)]
        center.removePendingNotificationRequests(withIdentifiers: ids)
        center.removeDeliveredNotifications(withIdentifiers: ids)
    }

    func cancelAll() {
        // Keep the daily prompt and the sticky fallback; only task chains go away.
        center.getPendingNotificationRequests { [center] requests in
            let ids = requests
                .map(\.identifier)
                .filter { $0.hasPrefix("task-") || $0.hasPrefix("alert-") }
            guard !ids.isEmpty else { return }
            center.removePendingNotificationRequests(withIdentifiers: ids)
            center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    func scheduleDailyPrompt(hourOfDay: Int32, minute: Int32) {
        cancelDailyPrompt()

        var components = DateComponents()
        components.hour = Int(hourOfDay)
        components.minute = Int(minute)

        let content = UNMutableNotificationContent()
        content.title = "Plan your day"
        content.body = "What do you want to get done today?"
        content.sound = .default
        content.categoryIdentifier = Notif.dailyPromptCategory
        content.interruptionLevel = .active
        content.userInfo = [
            Notif.keyRoute: "plan",
            Notif.keyKind: "daily-prompt",
        ]

        let request = UNNotificationRequest(
            identifier: Notif.dailyPromptId,
            content: content,
            trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: true)
        )
        center.add(request) { error in
            if let error {
                NSLog("[todo] failed to schedule daily prompt: \(error.localizedDescription)")
            }
        }
    }

    func cancelDailyPrompt() {
        let ids = [Notif.dailyPromptId, Notif.dailyPromptNowId]
        center.removePendingNotificationRequests(withIdentifiers: ids)
        center.removeDeliveredNotifications(withIdentifiers: ids)
    }

    /// Calendar triggers survive a timezone change and a clock set backwards better than
    /// interval triggers, so they are preferred; a time already in the past fires in 1s.
    private static func trigger(for date: Date) -> UNNotificationTrigger? {
        let delta = date.timeIntervalSinceNow
        if delta <= 1 {
            // Due in the past (task created late, or process was dead): ring right away.
            return UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        }
        let components = Calendar.current.dateComponents(
            [.year, .month, .day, .hour, .minute, .second],
            from: date
        )
        return UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
    }
}
