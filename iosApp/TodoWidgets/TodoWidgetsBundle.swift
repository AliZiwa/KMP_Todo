import SwiftUI
import WidgetKit

@main
struct TodoWidgetsBundle: WidgetBundle {
    var body: some Widget {
        TodoStatusWidget()
        if #available(iOS 16.2, *) {
            TodoLiveActivity()
        }
    }
}
