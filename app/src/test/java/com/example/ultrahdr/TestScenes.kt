package com.example.ultrahdr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Painted stand-ins for photos. Tests use them where the app would show a real picture, so no
 * personal photo is ever needed to draw a screen or to try the HDR maths on something photo-like.
 */
object TestScenes {

    const val SUNSET = 0
    const val NIGHT_STREET = 1
    const val BEACH = 2
    const val FOREST = 3
    const val LAMP = 4
    const val SNOW = 5
    const val DOCUMENT = 6
    const val COUNT = 7

    fun bitmap(kind: Int, width: Int = 480, height: Int = 360): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        when (((kind % COUNT) + COUNT) % COUNT) {
            SUNSET -> sunset(canvas, w, h)
            NIGHT_STREET -> nightStreet(canvas, w, h)
            BEACH -> beach(canvas, w, h)
            FOREST -> forest(canvas, w, h)
            LAMP -> lamp(canvas, w, h)
            SNOW -> snow(canvas, w, h)
            else -> document(canvas, w, h)
        }
        return bitmap
    }

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun vertical(canvas: Canvas, w: Float, top: Float, bottom: Float, colors: IntArray, stops: FloatArray? = null) {
        val p = paint()
        p.shader = LinearGradient(0f, top, 0f, bottom, colors, stops, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top, w, bottom, p)
    }

    private fun glow(canvas: Canvas, x: Float, y: Float, radius: Float, core: Int, halo: Int) {
        val p = paint()
        p.shader = RadialGradient(
            x, y, radius,
            intArrayOf(core, core, halo, Color.TRANSPARENT),
            floatArrayOf(0f, 0.16f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, radius, p)
    }

    private fun sunset(canvas: Canvas, w: Float, h: Float) {
        vertical(
            canvas, w, 0f, h * 0.64f,
            intArrayOf(0xFF1B2A5C.toInt(), 0xFF7A4A7C.toInt(), 0xFFE8743B.toInt(), 0xFFFFC46B.toInt()),
            floatArrayOf(0f, 0.45f, 0.82f, 1f)
        )
        glow(canvas, w * 0.66f, h * 0.56f, h * 0.30f, Color.WHITE, 0xCCFFD27A.toInt())
        vertical(canvas, w, h * 0.64f, h, intArrayOf(0xFF3A2C3A.toInt(), 0xFF120E14.toInt()))
        // Light on the water
        val p = paint().apply { color = 0xFFFFE2A6.toInt() }
        for (i in 0 until 7) {
            val y = h * (0.67f + i * 0.04f)
            val half = w * (0.05f + i * 0.012f)
            p.alpha = 220 - i * 26
            canvas.drawRoundRect(w * 0.66f - half, y, w * 0.66f + half, y + h * 0.012f, 6f, 6f, p)
        }
        // Hills
        val hills = Path().apply {
            moveTo(0f, h * 0.64f)
            cubicTo(w * 0.15f, h * 0.50f, w * 0.30f, h * 0.60f, w * 0.44f, h * 0.64f)
            close()
        }
        canvas.drawPath(hills, paint().apply { color = 0xFF1A1420.toInt() })
    }

    private fun nightStreet(canvas: Canvas, w: Float, h: Float) {
        vertical(canvas, w, 0f, h, intArrayOf(0xFF070A18.toInt(), 0xFF151A2E.toInt(), 0xFF0B0C12.toInt()), floatArrayOf(0f, 0.6f, 1f))
        val building = paint().apply { color = 0xFF10131F.toInt() }
        canvas.drawRect(0f, h * 0.22f, w * 0.30f, h * 0.80f, building)
        canvas.drawRect(w * 0.62f, h * 0.10f, w, h * 0.80f, building)
        val window = paint().apply { color = 0xFFFFD98A.toInt() }
        for (row in 0 until 5) for (col in 0 until 3) {
            if ((row * 3 + col) % 4 == 1) continue
            val x = w * (0.05f + col * 0.08f)
            val y = h * (0.28f + row * 0.09f)
            canvas.drawRect(x, y, x + w * 0.04f, y + h * 0.045f, window)
        }
        for (row in 0 until 6) for (col in 0 until 4) {
            if ((row + col * 2) % 3 == 0) continue
            val x = w * (0.67f + col * 0.08f)
            val y = h * (0.16f + row * 0.09f)
            canvas.drawRect(x, y, x + w * 0.04f, y + h * 0.045f, window)
        }
        glow(canvas, w * 0.40f, h * 0.34f, h * 0.20f, Color.WHITE, 0xAAFFC774.toInt())
        glow(canvas, w * 0.53f, h * 0.44f, h * 0.12f, Color.WHITE, 0xAAFFC774.toInt())
        glow(canvas, w * 0.47f, h * 0.86f, h * 0.10f, 0xFFFF5A4A.toInt(), 0x88FF3B30.toInt())
        canvas.drawRect(0f, h * 0.80f, w, h, paint().apply { color = 0xFF0C0D13.toInt() })
        glow(canvas, w * 0.40f, h * 0.90f, h * 0.16f, 0x66FFD9A0, 0x33FFC774)
    }

    private fun beach(canvas: Canvas, w: Float, h: Float) {
        vertical(canvas, w, 0f, h * 0.48f, intArrayOf(0xFF3F8FDB.toInt(), 0xFFBFE3FA.toInt()))
        vertical(canvas, w, h * 0.48f, h * 0.70f, intArrayOf(0xFF2A7FB0.toInt(), 0xFF58B5C9.toInt()))
        vertical(canvas, w, h * 0.70f, h, intArrayOf(0xFFE6D3A3.toInt(), 0xFFC9B07C.toInt()))
        val glint = paint().apply { color = Color.WHITE }
        for (i in 0 until 14) {
            val x = w * (0.30f + ((i * 37) % 40) / 100f)
            val y = h * (0.50f + ((i * 13) % 18) / 100f)
            canvas.drawOval(x, y, x + w * 0.03f, y + h * 0.008f, glint)
        }
        val cloud = paint().apply { color = 0xFFFDFEFF.toInt() }
        canvas.drawOval(w * 0.10f, h * 0.12f, w * 0.34f, h * 0.22f, cloud)
        canvas.drawOval(w * 0.22f, h * 0.08f, w * 0.44f, h * 0.20f, cloud)
    }

    private fun forest(canvas: Canvas, w: Float, h: Float) {
        vertical(canvas, w, 0f, h, intArrayOf(0xFF2F5A2C.toInt(), 0xFF1D3A20.toInt(), 0xFF10220F.toInt()), floatArrayOf(0f, 0.5f, 1f))
        val trunk = paint().apply { color = 0xFF1B1611.toInt() }
        for (i in 0 until 5) {
            val x = w * (0.08f + i * 0.21f)
            canvas.drawRect(x, 0f, x + w * 0.035f, h, trunk)
        }
        // Sky seen through the leaves
        for (i in 0 until 9) {
            val x = w * (0.12f + ((i * 29) % 80) / 100f)
            val y = h * (0.06f + ((i * 17) % 30) / 100f)
            glow(canvas, x, y, h * 0.07f, 0xFFFBFFE8.toInt(), 0x99E9F5B0.toInt())
        }
        glow(canvas, w * 0.55f, h * 0.78f, h * 0.14f, 0x88DDEB8F.toInt(), 0x44B7D060)
    }

    private fun lamp(canvas: Canvas, w: Float, h: Float) {
        vertical(canvas, w, 0f, h, intArrayOf(0xFF4A372B.toInt(), 0xFF2A1F19.toInt(), 0xFF17110E.toInt()), floatArrayOf(0f, 0.55f, 1f))
        glow(canvas, w * 0.60f, h * 0.36f, h * 0.44f, 0x99FFD79A.toInt(), 0x44FFB65C)
        val shade = Path().apply {
            moveTo(w * 0.53f, h * 0.24f); lineTo(w * 0.67f, h * 0.24f)
            lineTo(w * 0.72f, h * 0.46f); lineTo(w * 0.48f, h * 0.46f); close()
        }
        canvas.drawPath(shade, paint().apply { color = 0xFFFFF4D6.toInt() })
        canvas.drawRect(w * 0.592f, h * 0.46f, w * 0.608f, h * 0.70f, paint().apply { color = 0xFF2B2018.toInt() })
        canvas.drawRect(0f, h * 0.70f, w, h, paint().apply { color = 0xFF3B281C.toInt() })
        // A face-coloured shape that must not be boosted
        canvas.drawOval(w * 0.16f, h * 0.36f, w * 0.34f, h * 0.68f, paint().apply { color = 0xFFC99673.toInt() })
    }

    private fun snow(canvas: Canvas, w: Float, h: Float) {
        vertical(canvas, w, 0f, h * 0.55f, intArrayOf(0xFF5E9BD6.toInt(), 0xFFCFE4F5.toInt()))
        val peak = Path().apply {
            moveTo(0f, h * 0.58f); lineTo(w * 0.28f, h * 0.24f); lineTo(w * 0.46f, h * 0.44f)
            lineTo(w * 0.66f, h * 0.16f); lineTo(w, h * 0.52f); lineTo(w, h * 0.60f); close()
        }
        canvas.drawPath(peak, paint().apply { color = 0xFFF4F7FB.toInt() })
        vertical(canvas, w, h * 0.55f, h, intArrayOf(0xFFFBFCFE.toInt(), 0xFFDDE6F0.toInt()))
        val rock = paint().apply { color = 0xFF55606E.toInt() }
        canvas.drawOval(w * 0.12f, h * 0.74f, w * 0.26f, h * 0.82f, rock)
        canvas.drawOval(w * 0.70f, h * 0.84f, w * 0.80f, h * 0.90f, rock)
    }

    private fun document(canvas: Canvas, w: Float, h: Float) {
        canvas.drawColor(0xFFF7F6F2.toInt())
        val line = paint().apply { color = 0xFF3A3A3F.toInt() }
        for (i in 0 until 12) {
            val y = h * (0.10f + i * 0.07f)
            val end = if (i % 4 == 3) 0.55f else 0.88f
            canvas.drawRect(w * 0.10f, y, w * end, y + h * 0.012f, line)
        }
    }
}
