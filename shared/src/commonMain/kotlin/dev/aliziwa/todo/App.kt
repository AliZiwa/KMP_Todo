package dev.aliziwa.todo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Root composable hosted by every platform. [initialRoute] lets platforms deep-link
 * (e.g. notification tap -> "task/<id>", menu bar "Add" -> "add", daily prompt -> "plan").
 */
@Composable
fun App(graph: AppGraph, initialRoute: String? = null) {
    MaterialTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Personal Todo (skeleton) on ${graph.platform.capabilities.platformName}")
        }
    }
}
