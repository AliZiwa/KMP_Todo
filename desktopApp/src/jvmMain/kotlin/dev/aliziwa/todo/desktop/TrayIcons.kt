package dev.aliziwa.todo.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Menu-bar artwork, built as [ImageVector]s so the tray renders crisply at any menu-bar scale
 * (Compose rasterises the `Painter` for the AWT `SystemTray` at the right size).
 *
 * The brand teal is used rather than black/white because Compose Desktop does not hand AWT a macOS
 * *template* image, so a monochrome icon would vanish on either the light or the dark menu bar.
 */
object TrayIcons {
    private val Teal = Color(0xFF14A08F)
    private val Amber = Color(0xFFE2574C)

    private const val BADGE_BODY = "M4 2h16a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"
    private const val CHECK = "M7 12.6L10.4 16L17 8.6"
    private const val DUE_DOT = "M18.2 5.8a3.4 3.4 0 1 0 0.001 0z"

    /** Normal state. */
    val Idle: ImageVector = build(withDueDot = false)

    /** Something is due right now — adds a red pip so the menu bar reads at a glance. */
    val DueNow: ImageVector = build(withDueDot = true)

    private fun build(withDueDot: Boolean): ImageVector =
        ImageVector.Builder(
            name = if (withDueDot) "TodoTrayDue" else "TodoTray",
            defaultWidth = 22.dp,
            defaultHeight = 22.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(pathData = addPathNodes(BADGE_BODY), fill = SolidColor(Teal))
            addPath(
                pathData = addPathNodes(CHECK),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            if (withDueDot) {
                addPath(pathData = addPathNodes(DUE_DOT), fill = SolidColor(Amber))
                addPath(
                    pathData = addPathNodes(DUE_DOT),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.2f,
                )
            }
        }.build()
}
