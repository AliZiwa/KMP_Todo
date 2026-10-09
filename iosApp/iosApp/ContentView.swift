import Shared
import SwiftUI
import UIKit

/// Hosts the shared Compose UI. The route is passed as `initialRoute`, so a route change
/// (notification tap, daily prompt) rebuilds the view controller via `.id(...)`.
struct ComposeView: UIViewControllerRepresentable {
    let route: String

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(initialRoute: route)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @EnvironmentObject private var router: AppRouter

    var body: some View {
        ComposeView(route: router.route)
            .id(router.route)
            .ignoresSafeArea(.all)
    }
}
