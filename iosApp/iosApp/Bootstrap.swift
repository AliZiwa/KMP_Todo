import Foundation
import Shared

/// Builds the platform ports, starts the Kotlin layer and subscribes to its StateFlows.
/// Must run before any UI and before any notification is handled, hence the call from
/// `PersonalTodoApp.init` (SwiftUI runs it before the first scene).
final class Bootstrap {
    static let shared = Bootstrap()

    let alarms = IosAlarmScheduler()
    let alerts = IosAlertPresenter()
    let sound = IosSoundPlayer()
    let capabilities = IosCapabilities()

    private(set) var graph: AppGraph?
    private var subscriptions: [Cancellable] = []
    private var started = false

    private init() {}

    func start() {
        guard !started else { return }
        started = true

        Notif.registerCategories()

        // Apple Intelligence: no model file, no download. The shared layer sees a
        // `builtInModel` and defaults the assistant to the local engine.
        let engine: LocalLlmEngine = SwiftBackedLlmEngine(bridge: AppleFoundationModelEngine())

        let services = IosPlatformKt.buildIosPlatformServices(
            alarms: alarms,
            alerts: alerts,
            sound: sound,
            capabilities: capabilities,
            localLlmEngine: engine,
            appGroupId: AppGroup.identifier
        )
        graph = IosPlatformKt.startTodoApp(services: services)
        NSLog("[todo] shared layer started, db at \(services.paths.databasePath)")

        observeSharedState()
    }

    /// The Kotlin `DueEngine` drives the native surfaces through `AlertPresenter`; these
    /// subscriptions are what the *app* (routing, widget mirror) needs on top of that.
    private func observeSharedState() {
        subscriptions.append(
            TodoObservers.shared.observeStickySummary { summary in
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
            }
        )

        subscriptions.append(
            TodoObservers.shared.observeCurrentAlert { alert in
                guard let alert else { return }
                TaskTitleCache.put(taskId: alert.taskId, title: alert.title)
                AppRouter.shared.open(route: "alert/\(alert.taskId)")
            }
        )

        subscriptions.append(
            TodoObservers.shared.observeDailyPromptRequested {
                AppRouter.shared.open(route: "plan")
            }
        )
    }
}
