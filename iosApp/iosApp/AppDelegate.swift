import BackgroundTasks
import Shared
import UIKit
import UserNotifications

/// Handles everything that reaches the app from outside SwiftUI: notification taps and
/// action buttons (which can launch the app from scratch) and background refresh.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    static let refreshTaskId = "dev.aliziwa.jukizareminder.refresh"
    static let resyncTaskId = "dev.aliziwa.jukizareminder.resync"

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Safety net: SwiftUI's App.init already did this, but a notification-action launch
        // must never reach TodoApp.graph() before start().
        Bootstrap.shared.start()

        UNUserNotificationCenter.current().delegate = self
        Notif.requestAuthorization { granted in
            NSLog("[todo] notification permission granted=\(granted)")
            guard granted else {
                // The in-app banner is the shared layer's job; just make sure it knows.
                NSLog("[todo] running without notification permission - alerts only while open")
                return
            }
            // Anything the DueEngine tried to schedule before the user answered the prompt
            // was rejected by UNUserNotificationCenter ("source is not authorized"), so
            // re-arm everything now that we are allowed to.
            TodoActions.shared.resyncAlarms()
            TodoActions.shared.onAppForegrounded()
        }

        registerBackgroundTasks()
        applyDebugLaunchFlags()
        scheduleDebugAlarmIfRequested()
        return true
    }

    /// Screenshot / demo hooks:
    ///   xcrun simctl launch booted dev.aliziwa.jukizareminder -todoDebugSeedDemo 1 -todoDebugRoute chat
    private func applyDebugLaunchFlags() {
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "todoDebugSeedDemo"), let graph = Bootstrap.shared.graph {
            DemoDataKt.seedDemoDataAsync(graph: graph) {
                NSLog("[todo] demo data seeded")
            }
        }

        // -todoDebugAssistantPrompt "dentist friday at 3pm" exercises the assistant without a
        // keyboard. Waits for the engine to report ready-ish before sending.
        if let prompt = UserDefaults.standard.string(forKey: "todoDebugAssistantPrompt"), !prompt.isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                NSLog("[todo] debug assistant prompt: \(prompt)")
                TodoActions.shared.sendAssistantMessage(text: prompt)
            }
        }

        // `today`, `chat`, `settings`, `plan`, `add`, or any shared route (`task/<id>`).
        if let route = UserDefaults.standard.string(forKey: "todoDebugRoute"), !route.isEmpty {
            NSLog("[todo] debug route \(route)")
            AppRouter.shared.open(route: route)
        }
        #endif
    }

    /// Dev-only hook used to verify the alarm path end to end without a UI:
    ///   xcrun simctl launch booted dev.aliziwa.jukizareminder -todoDebugAlarmSeconds 10
    private func scheduleDebugAlarmIfRequested() {
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "todoDebugSticky") {
            // -todoDebugSticky 1 pushes a fake summary so the Live Activity can be eyeballed
            // before the shared layer has any real tasks.
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                let dueAt = Int64(Date().addingTimeInterval(20 * 60).timeIntervalSince1970 * 1000)
                Bootstrap.shared.alerts.updateSticky(
                    summary: StickySummary(
                        nextTaskTitle: "Call the dentist",
                        nextDueAtEpochMillis: KotlinLong(longLong: dueAt),
                        dueNowCount: 0,
                        remainingTodayCount: 3,
                        completedTodayCount: 1
                    )
                )
                NSLog("[todo] debug sticky summary pushed")
            }
        }

        let seconds = UserDefaults.standard.integer(forKey: "todoDebugAlarmSeconds")
        guard seconds > 0 else { return }
        let taskId = UserDefaults.standard.string(forKey: "todoDebugAlarmTaskId") ?? "debug-task"
        // Wait out the startup resync (DueEngine.resyncAlarms calls cancelAll), otherwise the
        // chain we add here is wiped again a moment later.
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            let fireAt = Date().addingTimeInterval(TimeInterval(seconds))
            Bootstrap.shared.alarms.schedule(
                taskId: taskId,
                atEpochMillis: Int64(fireAt.timeIntervalSince1970 * 1000),
                title: "Debug task"
            )
            NSLog("[todo] debug alarm for \(taskId) scheduled at \(fireAt)")
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
            UNUserNotificationCenter.current().getPendingNotificationRequests { requests in
                for request in requests.sorted(by: { $0.identifier < $1.identifier }) {
                    let content = request.content
                    NSLog("""
                    [todo] pending id=\(request.identifier) cat=\(content.categoryIdentifier) \
                    level=\(content.interruptionLevel.rawValue) sound=\(content.sound.map { "\($0)" } ?? "nil") \
                    body=\(content.body) trigger=\(request.trigger.map { "\($0)" } ?? "nil")
                    """)
                }
                UNUserNotificationCenter.current().getNotificationSettings { settings in
                    NSLog("[todo] auth status=\(settings.authorizationStatus.rawValue) alert=\(settings.alertSetting.rawValue) sound=\(settings.soundSetting.rawValue)")
                }
            }
        }
        #endif
    }

    // MARK: - UNUserNotificationCenterDelegate

    /// Show the alert even when the app is in the foreground — the doorbell is the point.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let userInfo = notification.request.content.userInfo
        if let taskId = userInfo[Notif.keyTaskId] as? String {
            // Foreground: let the shared layer decide (alert dialog + looping doorbell).
            TodoActions.shared.onAlarmFired(taskId: taskId)
            completionHandler([.banner, .list])
            return
        }
        if userInfo[Notif.keyKind] as? String == "daily-prompt" {
            TodoActions.shared.onDailyPromptFired()
            completionHandler([.banner, .list])
            return
        }
        completionHandler([.banner, .list, .sound])
    }

    /// Tap or action button. Works when the app was not running: iOS launches it first.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        Bootstrap.shared.start()

        let request = response.notification.request
        let userInfo = request.content.userInfo
        let taskId = userInfo[Notif.keyTaskId] as? String
        let actions = TodoActions.shared

        switch response.actionIdentifier {
        case Notif.actionDone:
            guard let taskId else { return completionHandler() }
            Bootstrap.shared.sound.stop()
            actions.completeTask(taskId: taskId) { completionHandler() }

        case Notif.actionExtend15:
            guard let taskId else { return completionHandler() }
            Bootstrap.shared.sound.stop()
            actions.extendTask(taskId: taskId, byMinutes: 15) { completionHandler() }

        case Notif.actionDismiss, UNNotificationDismissActionIdentifier:
            guard let taskId else { return completionHandler() }
            Bootstrap.shared.sound.stop()
            actions.dismissAlert(taskId: taskId) { completionHandler() }

        case Notif.actionDelete:
            guard let taskId else { return completionHandler() }
            Bootstrap.shared.sound.stop()
            TaskTitleCache.remove(taskId: taskId)
            actions.deleteTask(taskId: taskId) { completionHandler() }

        case Notif.actionPlan:
            actions.onDailyPromptFired()
            AppRouter.shared.open(route: "plan")
            completionHandler()

        default:
            // Plain tap (UNNotificationDefaultActionIdentifier) → deep link.
            let route = AppRouter.route(from: userInfo, identifier: request.identifier)
            if route == "plan" { actions.onDailyPromptFired() }
            AppRouter.shared.open(route: route)
            completionHandler()
        }
    }

    // MARK: - Background work

    private func registerBackgroundTasks() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.refreshTaskId,
            using: nil
        ) { task in
            TodoActions.shared.onAppForegrounded()
            self.scheduleAppRefresh()
            task.setTaskCompleted(success: true)
        }
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.resyncTaskId,
            using: nil
        ) { task in
            TodoActions.shared.resyncAlarms()
            task.setTaskCompleted(success: true)
        }
        scheduleAppRefresh()
    }

    private func scheduleAppRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshTaskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            NSLog("[todo] could not submit background refresh: \(error.localizedDescription)")
        }
    }
}
