package dev.aliziwa.todo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.cos
import kotlin.math.sin

/**
 * One place for the app's "bouncy" feel. Everything that moves because the user did something uses
 * [Motion.bouncy]; nothing animates while idle except the deliberate breathing on a due task.
 */
object Motion {

    fun <T> bouncy(): AnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)

    fun <T> bouncyFinite(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)

    val bouncyFloat = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )

    val bouncyDp = spring<Dp>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
        visibilityThreshold = Dp.VisibilityThreshold,
    )

    val bouncyOffset = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )

    /** Snappier spring for things that should feel instant but still overshoot a touch. */
    val snappyFloat = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    const val POP_IN = 180
    const val POP_OUT = 140
}

/**
 * Scale pop: jumps to [peak] then springs home whenever [trigger] changes to a new value.
 * Used by the checkbox tick, nav icons and the priority badge.
 */
@Composable
fun rememberPop(trigger: Any?, peak: Float = 1.3f, start: Float = 1f): Float {
    val scale = remember { Animatable(start) }
    LaunchedEffect(trigger) {
        scale.snapTo(start)
        scale.animateTo(peak, tween(110))
        scale.animateTo(1f, Motion.bouncyFloat)
    }
    return scale.value
}

/** Subtle breathing, only ever used for "this is due right now". */
@Composable
fun rememberBreath(active: Boolean, min: Float = 1f, max: Float = 1.06f, periodMillis: Int = 1_100): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = "breath")
    val value by transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(tween(periodMillis), RepeatMode.Reverse),
        label = "breath",
    )
    return value
}

/** A left/right shake, for the due-alert window arriving. */
@Composable
fun rememberShake(trigger: Any?, amplitude: Float = 9f, cycles: Int = 3): Float {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(620))
    }
    val p = progress.value
    return sin(p * cycles * 2f * 3.14159f) * amplitude * (1f - p)
}

/** Press feedback: squish on press, spring back on release. */
@Composable
fun Modifier.bouncyPress(interactionSource: MutableInteractionSource, squish: Float = 0.94f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        if (pressed) scale.animateTo(squish, tween(70)) else scale.animateTo(1f, Motion.bouncyFloat)
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Short sparkle burst drawn straight onto a Canvas — no raster assets, no library. Fires whenever
 * [trigger] changes and then costs nothing until it fires again.
 */
@Composable
fun SparkleBurst(
    trigger: Int,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    particles: Int = 10,
    durationMillis: Int = 650,
) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis))
    }
    val p = progress.value
    if (p >= 1f) return
    Canvas(modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension * 0.9f
        for (i in 0 until particles) {
            // Deterministic spread, so the burst looks designed rather than random.
            val angle = (i * (360f / particles) + 11f * (i % 3)) * 3.14159f / 180f
            val reach = maxRadius * (0.55f + 0.45f * ((i % 4) / 3f))
            val distance = reach * easeOutCubic(p)
            val alpha = (1f - p * p).coerceIn(0f, 1f)
            val dot = (size.minDimension * 0.085f) * (1f - p * 0.65f)
            drawCircle(
                color = colors[i % colors.size].copy(alpha = alpha),
                radius = dot,
                center = Offset(
                    centre.x + cos(angle) * distance,
                    centre.y + sin(angle) * distance,
                ),
            )
        }
    }
}

internal fun easeOutCubic(t: Float): Float {
    val inv = 1f - t
    return 1f - inv * inv * inv
}
