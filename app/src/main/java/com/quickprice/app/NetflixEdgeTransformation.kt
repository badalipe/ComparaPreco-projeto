package com.quickprice.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.MultiTransformation
import java.security.MessageDigest

/**
 * Efeito "Netflix": a foto vai sumindo (degradê suave) nas laterais, em cima e,
 * principalmente, embaixo, misturando com o fundo escuro do app.
 */
class NetflixEdgeTransformation(
    private val side: Float = 0.30f,
    private val top: Float = 0.12f,
    private val bottom: Float = 0.45f
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        val w = toTransform.width
        val h = toTransform.height
        val result = pool.get(w, h, Bitmap.Config.ARGB_8888)
        result.setHasAlpha(true)

        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(toTransform, 0f, 0f, null)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        // Laterais: some nas duas bordas
        paint.shader = gradient(0f, 0f, w.toFloat(), 0f, side, side)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // Vertical: some em cima (leve) e embaixo (forte)
        paint.shader = gradient(0f, 0f, 0f, h.toFloat(), top, bottom)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

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

    override fun equals(other: Any?) =
        other is NetflixEdgeTransformation &&
            other.side == side && other.top == top && other.bottom == bottom

    override fun hashCode() = ID.hashCode() + (side * 100).toInt() * 31 +
        (top * 100).toInt() * 17 + (bottom * 100).toInt()

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID$side$top$bottom".toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val ID = "com.quickprice.app.NetflixEdgeTransformation.v2"

        /** CenterCrop + degradê nas bordas (CenterCrop precisa vir junto porque
         *  o Glide ignora o scaleType do ImageView quando há transform manual). */
        @JvmStatic
        fun getTransformation(): Transformation<Bitmap> =
            MultiTransformation(CenterCrop(), NetflixEdgeTransformation())
    }
}
