import Shared
import SwiftUI

@main
struct PersonalTodoApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var router = AppRouter.shared

    init() {
        // Starts the Kotlin layer (Room, DueEngine, assistant) before any UI exists.
        Bootstrap.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(router)
                .ignoresSafeArea(.keyboard)
                .onChange(of: scenePhase) { phase in
                    guard phase == .active else { return }
                    // Catches alarms that fired while the process was dead and re-checks
                    // whether today's "plan your day" prompt is still owed.
                    TodoActions.shared.onAppForegrounded()
                    TodoActions.shared.resyncAlarms()
                }
        }
    }
}
