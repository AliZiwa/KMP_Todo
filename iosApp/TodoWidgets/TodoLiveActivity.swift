import ActivityKit
import SwiftUI
import WidgetKit

/// Live Activity: next task title + live countdown on the lock screen and in the Dynamic
/// Island. The app starts/updates it from `IosAlertPresenter.updateSticky`.
@available(iOS 16.2, *)
struct TodoLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TodoActivityAttributes.self) { context in
            LockScreenView(state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.35))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label("\(context.state.remainingTodayCount)", systemImage: "checklist")
                        .font(.caption)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    CountdownView(dueAt: context.state.dueAt)
                        .font(.caption.monospacedDigit())
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.taskTitle)
                        .font(.headline)
                        .lineLimit(1)
                }
            } compactLeading: {
                Image(systemName: context.state.dueNowCount > 0 ? "bell.badge.fill" : "bell")
            } compactTrailing: {
                CountdownView(dueAt: context.state.dueAt)
                    .font(.caption2.monospacedDigit())
            } minimal: {
                Image(systemName: context.state.dueNowCount > 0 ? "bell.badge.fill" : "bell")
            }
        }
    }
}

@available(iOS 16.2, *)
private struct LockScreenView: View {
    let state: TodoActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: state.dueNowCount > 0 ? "bell.badge.fill" : "bell")
                Text("Next task").font(.caption).foregroundStyle(.secondary)
                Spacer()
                CountdownView(dueAt: state.dueAt).font(.caption.monospacedDigit())
            }
            Text(state.taskTitle)
                .font(.headline)
                .lineLimit(2)
            Text("\(state.remainingTodayCount) left · \(state.completedTodayCount) done")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(14)
    }
}

/// Live countdown without any timer: `Text(_:style:.timer)` is updated by the system.
struct CountdownView: View {
    let dueAt: Date?

    var body: some View {
        if let dueAt {
            if dueAt > Date() {
                Text(dueAt, style: .timer)
            } else {
                Text("due").foregroundStyle(.red)
            }
        } else {
            Text("—").foregroundStyle(.secondary)
        }
    }
}
