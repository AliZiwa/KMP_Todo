import Foundation
import UserNotifications

/// Everything about the notification "protocol" between the app and iOS, in one place so the
/// scheduler, the presenter and the delegate cannot drift apart.
enum Notif {
    // Categories
    static let taskDueCategory = "TASK_DUE"
    static let dailyPromptCategory = "DAILY_PROMPT"
    static let stickyCategory = "STICKY_SUMMARY"

    // Actions
    static let actionDone = "ACTION_DONE"
    static let actionExtend15 = "ACTION_EXTEND_15"
    static let actionDismiss = "ACTION_DISMISS"
    static let actionDelete = "ACTION_DELETE"
    static let actionPlan = "ACTION_PLAN"

    // userInfo keys
    static let keyTaskId = "taskId"
    static let keyRoute = "route"
    static let keyKind = "kind"

    // Identifier shapes
    static let dailyPromptId = "daily-prompt"
    static let dailyPromptNowId = "daily-prompt-now"
    static let stickyId = "sticky-summary"

    /// Re-alert chain: `task-<id>-<n>`, n = 0..<reAlertCount, 2 minutes apart.
    static let reAlertCount = 10
    static let reAlertIntervalSeconds: TimeInterval = 120

    static func alarmIdentifier(taskId: String, index: Int) -> String {
        "task-\(taskId)-\(index)"
    }

    static func immediateAlertIdentifier(taskId: String) -> String {
        "alert-\(taskId)"
    }

    static let doorbellSound = UNNotificationSound(named: UNNotificationSoundName("doorbell.caf"))

    /// Registers the action buttons. Called once at launch, before permission is requested,
    /// so notifications delivered while the app is dead already carry their actions.
    static func registerCategories() {
        let done = UNNotificationAction(
            identifier: actionDone,
            title: "Done",
            options: [.authenticationRequired]
        )
        let extend = UNNotificationAction(
            identifier: actionExtend15,
            title: "+15 min",
            options: []
        )
        let dismiss = UNNotificationAction(
            identifier: actionDismiss,
            title: "Dismiss",
            options: []
        )
        let delete = UNNotificationAction(
            identifier: actionDelete,
            title: "Delete",
            options: [.destructive, .authenticationRequired]
        )
        let plan = UNNotificationAction(
            identifier: actionPlan,
            title: "Plan my day",
            options: [.foreground]
        )

        let taskDue = UNNotificationCategory(
            identifier: taskDueCategory,
            actions: [done, extend, dismiss, delete],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        let dailyPrompt = UNNotificationCategory(
            identifier: dailyPromptCategory,
            actions: [plan],
            intentIdentifiers: [],
            options: []
        )
        let sticky = UNNotificationCategory(
            identifier: stickyCategory,
            actions: [],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([taskDue, dailyPrompt, sticky])
    }

    static func requestAuthorization(_ completion: ((Bool) -> Void)? = nil) {
        // No .provisional in production: provisional notifications are delivered quietly,
        // which defeats the point of a doorbell alarm. We want the real prompt.
        var options: UNAuthorizationOptions = [.alert, .sound, .badge]
        #if DEBUG
        // -todoDebugProvisional 1 skips the (untappable) prompt in a headless simulator.
        // `.provisional` is only granted silently when asked for WITHOUT `.alert` — combined
        // with it, iOS still shows the prompt, which blocks screenshots.
        if UserDefaults.standard.bool(forKey: "todoDebugProvisional") {
            options = [.provisional, .sound, .badge]
        }
        #endif
        UNUserNotificationCenter.current().requestAuthorization(options: options) { granted, error in
            if let error {
                NSLog("[todo] notification authorization failed: \(error.localizedDescription)")
            }
            completion?(granted)
        }
    }
}

/// Last known title for a task key. The shared layer calls the 3-arg
/// `AlarmScheduler.schedule(taskId:atEpochMillis:title:)`, which fills this in; the cache only
/// has to cover the 2-arg overload and anything else that knows an id but not a title.
/// Lives in the App Group so a notification scheduled in a previous process still has it.
/// Keys are shared-layer task keys, so they may contain `@` (recurring: `<templateId>@yyyy-MM-dd`).
enum TaskTitleCache {
    private static let key = "task.titles.v1"

    static func put(taskId: String, title: String) {
        guard let defaults = AppGroup.defaults else { return }
        var map = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
        map[taskId] = title
        defaults.set(map, forKey: key)
    }

    static func title(for taskId: String) -> String? {
        guard let defaults = AppGroup.defaults else { return nil }
        let map = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
        return map[taskId]
    }

    static func remove(taskId: String) {
        guard let defaults = AppGroup.defaults else { return }
        var map = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
        map.removeValue(forKey: taskId)
        defaults.set(map, forKey: key)
    }
}
