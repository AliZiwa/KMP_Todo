package dev.aliziwa.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** The routes platforms may deep-link to. */
sealed interface Route {
    data object Today : Route
    data object Add : Route
    data object Plan : Route
    data class Task(val key: String) : Route
    data class Alert(val key: String) : Route
    data object Chat : Route
    data object Settings : Route
}

/** Parses the `initialRoute` string platforms pass to `App`. Unknown input falls back to Today. */
fun parseRoute(raw: String?): Route {
    val text = raw?.trim()?.removePrefix("/")?.takeIf { it.isNotEmpty() } ?: return Route.Today
    val head = text.substringBefore('/')
    val tail = text.substringAfter('/', "")
    return when (head) {
        "today" -> Route.Today
        "add" -> Route.Add
        "plan" -> Route.Plan
        "chat" -> Route.Chat
        "settings" -> Route.Settings
        "task" -> if (tail.isNotEmpty()) Route.Task(tail) else Route.Today
        "alert" -> if (tail.isNotEmpty()) Route.Alert(tail) else Route.Today
        else -> Route.Today
    }
}

/** Tiny back-stack. No navigation library needed for seven destinations. */
class Navigator(start: Route) {
    private val stack = mutableStateListOf(start)

    var current: Route by mutableStateOf(start)
        private set

    val canGoBack: Boolean get() = stack.size > 1

    fun push(route: Route) {
        stack.add(route)
        current = route
    }

    /** Replaces the whole stack — used for the home tab. */
    fun reset(route: Route) {
        stack.clear()
        stack.add(route)
        current = route
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        current = stack.last()
        return true
    }
}

@Composable
fun rememberNavigator(start: Route): Navigator = remember(start) { Navigator(start) }
