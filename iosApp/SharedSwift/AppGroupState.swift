import Foundation

/// Code shared between the app target and the `TodoWidgets` extension.
/// The App Group is how the sticky summary reaches the widget (the extension cannot talk to
/// the Kotlin framework — it is not linked there).
public enum AppGroup {
    public static let identifier = "group.dev.aliziwa.todo"
    public static let stickyKey = "sticky.summary.v1"
    public static let liveActivityId = "todo.next-task"

    public static var defaults: UserDefaults? {
        UserDefaults(suiteName: identifier)
    }
}

/// Plain Codable mirror of the Kotlin `StickySummary`.
public struct StickySnapshot: Codable, Hashable {
    public var nextTaskTitle: String?
    public var nextDueAt: Date?
    public var dueNowCount: Int
    public var remainingTodayCount: Int
    public var completedTodayCount: Int
    public var updatedAt: Date

    public init(
        nextTaskTitle: String? = nil,
        nextDueAt: Date? = nil,
        dueNowCount: Int = 0,
        remainingTodayCount: Int = 0,
        completedTodayCount: Int = 0,
        updatedAt: Date = Date()
    ) {
        self.nextTaskTitle = nextTaskTitle
        self.nextDueAt = nextDueAt
        self.dueNowCount = dueNowCount
        self.remainingTodayCount = remainingTodayCount
        self.completedTodayCount = completedTodayCount
        self.updatedAt = updatedAt
    }

    public static let empty = StickySnapshot()

    public func write() {
        guard let defaults = AppGroup.defaults,
              let data = try? JSONEncoder().encode(self) else { return }
        defaults.set(data, forKey: AppGroup.stickyKey)
    }

    public static func read() -> StickySnapshot {
        guard let defaults = AppGroup.defaults,
              let data = defaults.data(forKey: AppGroup.stickyKey),
              let decoded = try? JSONDecoder().decode(StickySnapshot.self, from: data)
        else { return .empty }
        return decoded
    }
}
