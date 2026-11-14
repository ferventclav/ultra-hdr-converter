package com.example.ultrahdr.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.ultrahdr.processing.HdrStrength
import com.example.ultrahdr.processing.UltraHdrEngine
import com.example.ultrahdr.ui.theme.UhdrTheme

/**
 * A painted sunset run through the app's own conversion. On an HDR screen the sun and its path on
 * the water glow brighter than white, which shows what the app does better than words.
 */
@Composable
fun HdrDemo(modifier: Modifier = Modifier) {
    val picture: ImageBitmap? = remember { runCatching { DemoScene.hdr(720, 450).asImageBitmap() }.getOrNull() }
    Box(modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
        if (picture != null) {
            Image(picture, contentDescription = "A sunset over a lake, converted to HDR", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Text(
            text = "HDR",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .background(UhdrTheme.colors.glassPhoto, CircleShape)
                .border(1.dp, UhdrTheme.colors.linePhoto, CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

internal object DemoScene {

    /** The scene with the gain map the app would give it, or without one if that fails. */
    fun hdr(width: Int, height: Int): Bitmap {
        val base = paint(width, height)
        return runCatching {
            val plan = UltraHdrEngine.plan(base, HdrStrength.BALANCED)
            base.copy(Bitmap.Config.ARGB_8888, true).also { UltraHdrEngine.attach(it, plan) }
        }.getOrDefault(base)
    }

    fun paint(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        val horizon = h * 0.60f
        val sunX = w * 0.63f
        val sunY = horizon - h * 0.13f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // Sky: deep evening blue at the top, warm near the horizon.
        fill.shader = LinearGradient(
            0f, 0f, 0f, horizon,
            intArrayOf(0xFF16213B.toInt(), 0xFF34497A.toInt(), 0xFFD9845E.toInt(), 0xFFFFC98E.toInt()),
            floatArrayOf(0f, 0.42f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, horizon, fill)

        // The sun's glow, then the sun itself: the brightest thing in the picture.
        fill.shader = RadialGradient(
            sunX, sunY, h * 0.42f,
            intArrayOf(0xCCFFF1D6.toInt(), 0x55FFC88A, 0x00FFC88A),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(sunX, sunY, h * 0.42f, fill)
        fill.shader = null
        fill.color = 0xFFFFFDF6.toInt()
        canvas.drawCircle(sunX, sunY, h * 0.075f, fill)

        // Two ridges of hills, the far one paler.
        fun ridge(color: Int, base: Float, points: List<Pair<Float, Float>>) {
            val path = Path().apply {
                moveTo(0f, base)
                points.forEach { (x, y) -> lineTo(x * w, y * h) }
                lineTo(w, base)
                lineTo(w, horizon + 1f)
                lineTo(0f, horizon + 1f)
                close()
            }
            fill.color = color
            canvas.drawPath(path, fill)
        }
        ridge(0xFF4A3F62.toInt(), horizon - h * 0.08f, listOf(0.12f to 0.45f, 0.28f to 0.50f, 0.42f to 0.43f, 0.55f to 0.52f, 0.78f to 0.49f, 0.92f to 0.44f))
        ridge(0xFF262741.toInt(), horizon - h * 0.02f, listOf(0.08f to 0.53f, 0.22f to 0.49f, 0.36f to 0.56f, 0.70f to 0.57f, 0.86f to 0.52f))

        // Water, darker towards the viewer.
        fill.shader = LinearGradient(
            0f, horizon, 0f, h,
            intArrayOf(0xFF6E5C78.toInt(), 0xFF27304F.toInt(), 0xFF111628.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, horizon, w, h, fill)
        fill.shader = null

        // The sun's path on the water: short bright streaks, wider and dimmer towards the viewer.
        var y = horizon + h * 0.012f
        var row = 0
        while (y < h * 0.96f) {
            val depth = (y - horizon) / (h - horizon)
            val half = w * (0.025f + depth * 0.10f) * (if (row % 2 == 0) 1f else 0.7f)
            val alpha = (255 * (1f - depth * 0.75f)).toInt().coerceIn(40, 255)
            fill.color = (alpha shl 24) or 0xFFF4DC
            canvas.drawRoundRect(sunX - half, y, sunX + half, y + h * 0.008f, h * 0.004f, h * 0.004f, fill)
            y += h * (0.016f + depth * 0.03f)
            row++
        }

        // Near shore, almost black, framing the bottom left.
        val shore = Path().apply {
            moveTo(0f, h * 0.80f)
            cubicTo(w * 0.18f, h * 0.78f, w * 0.30f, h * 0.88f, w * 0.44f, h)
            lineTo(0f, h)
            close()
        }
        fill.color = 0xFF0B0D18.toInt()
        canvas.drawPath(shore, fill)
        return bitmap
    }
}
