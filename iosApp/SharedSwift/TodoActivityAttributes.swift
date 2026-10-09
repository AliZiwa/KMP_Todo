import Foundation

#if canImport(ActivityKit)
import ActivityKit

/// Live Activity payload. Declared in `SharedSwift` because both the app (which starts and
/// updates the activity) and `TodoWidgets` (which renders it) must see the same type.
public struct TodoActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        public var taskTitle: String
        /// Absolute due date; the widget renders a live countdown from it.
        public var dueAt: Date?
        public var dueNowCount: Int
        public var remainingTodayCount: Int
        public var completedTodayCount: Int

        public init(
            taskTitle: String,
            dueAt: Date?,
            dueNowCount: Int,
            remainingTodayCount: Int,
            completedTodayCount: Int
        ) {
            self.taskTitle = taskTitle
            self.dueAt = dueAt
            self.dueNowCount = dueNowCount
            self.remainingTodayCount = remainingTodayCount
            self.completedTodayCount = completedTodayCount
        }
    }

    public var appName: String

    public init(appName: String = "Personal Todo") {
        self.appName = appName
    }
}
#endif
