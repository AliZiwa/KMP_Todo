import Foundation
import SwiftUI

/// Single source of truth for the Compose route the app is currently showing.
/// Routes come from the shared layer's list: `today`, `add`, `plan`, `task/<id>`,
/// `alert/<id>`, `chat`, `settings`.
final class AppRouter: ObservableObject {
    static let shared = AppRouter()

    @Published var route: String = "today"

    private init() {}

    func open(route newRoute: String) {
        if Thread.isMainThread {
            route = newRoute
        } else {
            DispatchQueue.main.async { [weak self] in self?.route = newRoute }
        }
    }

    /// Route carried by a notification's `userInfo`, with a fallback derived from its id.
    static func route(from userInfo: [AnyHashable: Any], identifier: String) -> String {
        if let route = userInfo[Notif.keyRoute] as? String { return route }
        if let taskId = userInfo[Notif.keyTaskId] as? String { return "alert/\(taskId)" }
        if identifier.hasPrefix(Notif.dailyPromptId) { return "plan" }
        return "today"
    }
}
