package dev.aliziwa.todo

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point used by `iosApp` to host the shared Compose UI.
 *
 * Swift: `MainViewControllerKt.MainViewController(initialRoute: "alert/123")`.
 * [TodoApp.start] MUST have been called first (see `IosPlatformKt.startTodoApp`).
 */
fun MainViewController(initialRoute: String?): UIViewController =
    ComposeUIViewController { App(TodoApp.graph(), initialRoute) }
