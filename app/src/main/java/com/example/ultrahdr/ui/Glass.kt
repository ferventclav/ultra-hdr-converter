package com.example.ultrahdr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Frosted glass: a surface that shows a blurred copy of whatever is behind it.
 *
 * How it works. The page ([glassSource]) is drawn into a recording as well as onto the screen.
 * Each glass surface ([GlassSurface]) plays that recording back at its own position through a
 * blur, then lays a see-through tint on top. The recording is a display list the graphics chip
 * replays, so nothing is drawn twice on the CPU and nothing is copied into a bitmap.
 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    /** Where the recorded page sits on screen. */
    internal var origin by mutableStateOf(Offset.Zero)

    /** Goes up every time the page is redrawn, so glass on top of it redraws as well. */
    internal var frame by mutableIntStateOf(0)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Marks the content that glass surfaces blur. The glass itself must not be inside it. */
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
        // Counted without being watched: the page must not redraw because of its own counter.
        Snapshot.withoutReadObservation { backdrop.frame++ }
    }

/**
 * The edge of the screen behind a floating bar, as One UI and the Knowledge app draw it: the page
 * blurs and fades into the page colour towards the edge, so content melts away under the bar
 * instead of showing through it. [fromTop] puts the solid end at the top (app bar), otherwise at
 * the bottom (toolbar).
 */
@Composable
fun FadingGlass(
    backdrop: GlassBackdrop,
    color: Color,
    fromTop: Boolean,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 16.dp
) {
    val blurred = rememberGraphicsLayer()
    val masked = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .onGloballyPositioned { position = it.positionInRoot() }
            .drawWithCache {
                val radius = blurRadius.toPx()
                val layerSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
                blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                val solid = if (fromTop) 0f else 1f
                val clear = 1f - solid
                // Gradient stops must run top to bottom, whichever end is solid.
                fun brush(vararg stops: Pair<Float, Color>) = Brush.verticalGradient(*stops.sortedBy { it.first }.toTypedArray())
                // Fully blurred for the first part, then gone: the "progressive" blur.
                val mask = brush(
                    solid to Color.Black,
                    (solid * 0.35f + clear * 0.65f) to Color.Black,
                    clear to Color.Transparent
                )
                // The page colour on top, strong at the edge and fading out, so a title on it stays readable.
                val wash = brush(
                    solid to color,
                    (solid * 0.45f + clear * 0.55f) to color.copy(alpha = color.alpha * 0.78f),
                    clear to color.copy(alpha = 0f)
                )
                masked.compositingStrategy = CompositingStrategy.Offscreen
                onDrawBehind {
                    @Suppress("UNUSED_VARIABLE")
                    val frame = backdrop.frame
                    val shift = backdrop.origin - position
                    blurred.record(size = layerSize) {
                        translate(shift.x, shift.y) { drawLayer(backdrop.layer) }
                    }
                    // The blurred page, faded out by the mask in a layer of its own.
                    masked.record(size = layerSize) {
                        drawLayer(blurred)
                        drawRect(mask, blendMode = BlendMode.DstIn)
                    }
                    drawLayer(masked)
                    drawRect(wash)
                }
            }
    )
}

/**
 * A container with a frosted-glass background.
 *
 * [tint] decides how readable things on the glass are: the more opaque it is, the calmer the
 * surface and the less of the page shows through.
 */
@Composable
fun GlassSurface(
    backdrop: GlassBackdrop,
    shape: Shape,
    tint: Color,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    blurRadius: Dp = 26.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val blurred = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { position = it.positionInRoot() }
            .drawWithCache {
                val clip = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
                val radius = blurRadius.toPx()
                val layerSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
                blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                onDrawBehind {
                    // Reading the counter is what ties this surface to the page's redraws.
                    @Suppress("UNUSED_VARIABLE")
                    val frame = backdrop.frame
                    val shift = backdrop.origin - position
                    blurred.record(size = layerSize) {
                        translate(shift.x, shift.y) { drawLayer(backdrop.layer) }
                    }
                    clipPath(clip) {
                        drawLayer(blurred)
                        drawRect(tint)
                    }
                }
            }
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clip(shape),
        content = content
    )
}
