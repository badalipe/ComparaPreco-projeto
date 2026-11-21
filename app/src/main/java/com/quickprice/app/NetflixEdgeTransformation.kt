package com.quickprice.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import coil.size.Size
import coil.transform.Transformation

/**
 * Efeito "Netflix": a foto vai sumindo (degradê suave) nas laterais, em cima e,
 * principalmente, embaixo, misturando com o fundo escuro do app.
 */
class NetflixEdgeTransformation(
    private val side: Float = 0.18f,
    private val top: Float = 0.08f,
    private val bottom: Float = 0.34f
) : Transformation {

    override val cacheKey: String = "$ID:$side:$top:$bottom"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            density = input.density
            setHasAlpha(true)
        }

        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(input, 0f, 0f, null)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        // Laterais: some nas duas bordas
        paint.shader = gradient(0f, 0f, w.toFloat(), 0f, side, side)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // Vertical: some em cima (leve) e embaixo (forte)
        paint.shader = gradient(0f, 0f, 0f, h.toFloat(), top, bottom)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.xfermode = null

        return result
    }

    /** Degradê com curva suave (smoothstep) para não aparecer "faixa". */
    private fun gradient(
        x0: Float, y0: Float, x1: Float, y1: Float,
        startFade: Float, endFade: Float
    ): LinearGradient {
        val steps = 8
        val colors = ArrayList<Int>()
        val positions = ArrayList<Float>()

        for (i in 0..steps) {
            val t = i / steps.toFloat()
            colors.add(alpha(smooth(t)))
            positions.add(t * startFade)
        }
        positions.add(1f - endFade)
        colors.add(alpha(1f))
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            colors.add(alpha(smooth(1f - t)))
            positions.add(1f - endFade + t * endFade)
        }
        // Remove posições repetidas/fora de ordem
        val cleanPos = ArrayList<Float>()
        val cleanCol = ArrayList<Int>()
        for (i in positions.indices) {
            val p = positions[i].coerceIn(0f, 1f)
            if (cleanPos.isEmpty() || p > cleanPos.last()) {
                cleanPos.add(p); cleanCol.add(colors[i])
            }
        }
        return LinearGradient(
            x0, y0, x1, y1,
            cleanCol.toIntArray(), cleanPos.toFloatArray(), Shader.TileMode.CLAMP
        )
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
    private fun alpha(a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), 0, 0, 0)

    companion object {
        private const val ID = "com.quickprice.app.NetflixEdgeTransformation.coil.v1"
    }
}
