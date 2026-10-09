package dev.aliziwa.todo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Empty-state artwork, drawn with Canvas only — no raster assets anywhere in the app, so the same
 * illustrations ship to desktop, Android and iOS and recolour themselves with the theme.
 */
enum class EmptyArt { COFFEE, CALENDAR, SPROUT, CHAT, SPARKLE }

@Composable
fun EmptyIllustration(
    art: EmptyArt,
    modifier: Modifier = Modifier.size(132.dp),
    primary: Color,
    secondary: Color,
    accent: Color,
    soft: Color,
) {
    Canvas(modifier) {
        // Everything is drawn inside a 100x100 box and scaled to whatever size we are given.
        val unit = size.minDimension / 100f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun s(v: Float) = v * unit

        // Friendly blob behind the subject.
        drawCircle(soft, radius = s(44f), center = p(50f, 52f))

        when (art) {
            EmptyArt.COFFEE -> drawCoffee(::p, ::s, primary, secondary, accent)
            EmptyArt.CALENDAR -> drawCalendar(::p, ::s, primary, secondary, accent)
            EmptyArt.SPROUT -> drawSprout(::p, ::s, primary, secondary, accent)
            EmptyArt.CHAT -> drawChat(::p, ::s, primary, secondary, accent)
            EmptyArt.SPARKLE -> drawSparkle(::p, ::s, primary, secondary, accent)
        }
    }
}

private fun DrawScope.drawCoffee(
    p: (Float, Float) -> Offset,
    s: (Float) -> Float,
    primary: Color,
    secondary: Color,
    accent: Color,
) {
    // Cup
    val cup = Rect(p(28f, 48f), Size(s(38f), s(30f)))
    drawRoundRect(
        color = primary,
        topLeft = cup.topLeft,
        size = cup.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s(6f), s(6f)),
    )
    // Handle
    drawArc(
        color = primary,
        startAngle = -70f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = p(62f, 52f),
        size = Size(s(20f), s(20f)),
        style = Stroke(width = s(5f), cap = StrokeCap.Round),
    )
    // Saucer
    drawRoundRect(
        color = secondary,
        topLeft = p(22f, 79f),
        size = Size(s(52f), s(6f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s(3f), s(3f)),
    )
    // Steam
    for (i in 0..1) {
        val x = 38f + i * 14f
        val path = Path().apply {
            moveTo(p(x, 42f).x, p(x, 42f).y)
            cubicTo(
                p(x + 7f, 36f).x, p(x + 7f, 36f).y,
                p(x - 7f, 30f).x, p(x - 7f, 30f).y,
                p(x + 2f, 22f).x, p(x + 2f, 22f).y,
            )
        }
        drawPath(path, accent, style = Stroke(width = s(3.4f), cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawCalendar(
    p: (Float, Float) -> Offset,
    s: (Float) -> Float,
    primary: Color,
    secondary: Color,
    accent: Color,
) {
    drawRoundRect(
        color = primary.copy(alpha = 0.18f),
        topLeft = p(24f, 30f),
        size = Size(s(52f), s(48f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s(8f), s(8f)),
    )
    drawRoundRect(
        color = primary,
        topLeft = p(24f, 30f),
        size = Size(s(52f), s(13f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s(8f), s(8f)),
    )
    drawRoundRect(
        color = primary,
        topLeft = p(24f, 30f),
        size = Size(s(52f), s(48f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s(8f), s(8f)),
        style = Stroke(width = s(3f)),
    )
    // Rings
    drawRoundRect(accent, p(34f, 22f), Size(s(5f), s(13f)), androidx.compose.ui.geometry.CornerRadius(s(3f), s(3f)))
    drawRoundRect(accent, p(61f, 22f), Size(s(5f), s(13f)), androidx.compose.ui.geometry.CornerRadius(s(3f), s(3f)))
    // A single ticked day
    drawCircle(secondary, radius = s(8f), center = p(50f, 60f))
    val tick = Path().apply {
        moveTo(p(46f, 60f).x, p(46f, 60f).y)
        lineTo(p(49f, 64f).x, p(49f, 64f).y)
        lineTo(p(55f, 56f).x, p(55f, 56f).y)
    }
    drawPath(tick, Color.White, style = Stroke(width = s(3f), cap = StrokeCap.Round))
}

private fun DrawScope.drawSprout(
    p: (Float, Float) -> Offset,
    s: (Float) -> Float,
    primary: Color,
    secondary: Color,
    accent: Color,
) {
    // Pot
    val pot = Path().apply {
        moveTo(p(34f, 60f).x, p(34f, 60f).y)
        lineTo(p(66f, 60f).x, p(66f, 60f).y)
        lineTo(p(61f, 82f).x, p(61f, 82f).y)
        lineTo(p(39f, 82f).x, p(39f, 82f).y)
        close()
    }
    drawPath(pot, primary)
    drawRoundRect(accent, p(30f, 54f), Size(s(40f), s(8f)), androidx.compose.ui.geometry.CornerRadius(s(4f), s(4f)))
    // Stem + two leaves
    drawLine(
        secondary,
        start = p(50f, 54f),
        end = p(50f, 30f),
        strokeWidth = s(3.4f),
        cap = StrokeCap.Round,
    )
    drawOval(secondary, topLeft = p(30f, 26f), size = Size(s(20f), s(13f)))
    drawOval(secondary.copy(alpha = 0.75f), topLeft = p(50f, 18f), size = Size(s(20f), s(13f)))
}

private fun DrawScope.drawChat(
    p: (Float, Float) -> Offset,
    s: (Float) -> Float,
    primary: Color,
    secondary: Color,
    accent: Color,
) {
    drawRoundRect(
        color = primary,
        topLeft = p(20f, 30f),
        size = Size(s(46f), s(32f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s(12f), s(12f)),
    )
    val tail = Path().apply {
        moveTo(p(30f, 60f).x, p(30f, 60f).y)
        lineTo(p(30f, 74f).x, p(30f, 74f).y)
        lineTo(p(44f, 61f).x, p(44f, 61f).y)
        close()
    }
    drawPath(tail, primary)
    for (i in 0..2) drawCircle(Color.White, radius = s(3.2f), center = p(31f + i * 12f, 46f))
    drawRoundRect(
        color = secondary,
        topLeft = p(52f, 52f),
        size = Size(s(32f), s(24f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s(10f), s(10f)),
    )
    drawCircle(accent, radius = s(4f), center = p(76f, 34f))
}

private fun DrawScope.drawSparkle(
    p: (Float, Float) -> Offset,
    s: (Float) -> Float,
    primary: Color,
    secondary: Color,
    accent: Color,
) {
    drawCircle(primary, radius = s(26f), center = p(50f, 52f))
    val tick = Path().apply {
        moveTo(p(39f, 52f).x, p(39f, 52f).y)
        lineTo(p(47f, 61f).x, p(47f, 61f).y)
        lineTo(p(63f, 42f).x, p(63f, 42f).y)
    }
    drawPath(tick, Color.White, style = Stroke(width = s(6f), cap = StrokeCap.Round))
    // Little four-point stars around it
    listOf(Triple(20f, 26f, 7f), Triple(82f, 38f, 5f), Triple(74f, 80f, 6f)).forEach { (x, y, r) ->
        val colour = if (r > 6f) accent else secondary
        drawLine(colour, p(x - r, y), p(x + r, y), strokeWidth = s(2.6f), cap = StrokeCap.Round)
        drawLine(colour, p(x, y - r), p(x, y + r), strokeWidth = s(2.6f), cap = StrokeCap.Round)
    }
}
