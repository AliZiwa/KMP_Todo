import SwiftUI
import WidgetKit

/// Home-screen widget. The extension cannot link the Kotlin framework, so it reads the
/// sticky summary the app mirrors into the shared App Group.
struct TodoStatusEntry: TimelineEntry {
    let date: Date
    let snapshot: StickySnapshot
}

struct TodoStatusProvider: TimelineProvider {
    func placeholder(in context: Context) -> TodoStatusEntry {
        TodoStatusEntry(date: Date(), snapshot: .empty)
    }

    func getSnapshot(in context: Context, completion: @escaping (TodoStatusEntry) -> Void) {
        completion(TodoStatusEntry(date: Date(), snapshot: StickySnapshot.read()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<TodoStatusEntry>) -> Void) {
        let entry = TodoStatusEntry(date: Date(), snapshot: StickySnapshot.read())
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(15 * 60))))
    }
}

struct TodoStatusWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "TodoStatusWidget", provider: TodoStatusProvider()) { entry in
            TodoStatusView(snapshot: entry.snapshot)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Next task")
        .description("Your next due task and today's progress.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct TodoStatusView: View {
    let snapshot: StickySnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Next").font(.caption2).foregroundStyle(.secondary)
            Text(snapshot.nextTaskTitle ?? "Nothing scheduled")
                .font(.headline)
                .lineLimit(2)
            CountdownView(dueAt: snapshot.nextDueAt)
                .font(.caption.monospacedDigit())
            Spacer(minLength: 0)
            Text("\(snapshot.remainingTodayCount) left · \(snapshot.completedTodayCount) done")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
