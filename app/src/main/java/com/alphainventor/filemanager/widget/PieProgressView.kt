package com.alphainventor.filemanager.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.alphainventor.filemanager.theme.ThemeManager

/**
 * Orijinal Dosya Yöneticisi + 3.6.7 içerisindeki com.alphainventor.filemanager.widget.PieProgress
 * bileşeninin birebir karşılığı: Bellek Analizi dairesel pasta grafiğini çizer.
 * AMOLED ve Koyu temada yüksek kontrastlı renklerle her zaman net görünür.
 */
class PieProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var progressPercent: Int = 66
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * resources.displayMetrics.density
    }

    private val ovalRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val isDark = ThemeManager.isDarkOrAmoled(context)
        if (isDark) {
            circlePaint.color = Color.parseColor("#546E7A")
            slicePaint.color = Color.parseColor("#90A4AE")
            borderPaint.color = Color.parseColor("#CFD8DC")
            separatorPaint.color = Color.parseColor("#121212")
        } else {
            circlePaint.color = Color.parseColor("#9E9E9E")
            slicePaint.color = Color.parseColor("#757575")
            borderPaint.color = Color.parseColor("#616161")
            separatorPaint.color = Color.parseColor("#FFFFFF")
        }

        val pad = 4f * resources.displayMetrics.density
        val size = minOf(width, height).toFloat() - pad * 2
        if (size <= 0f) return
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        ovalRect.set(left, top, left + size, top + size)

        // Arka plan daire
        canvas.drawOval(ovalRect, circlePaint)

        // Kullanılan yüzde dilimi (-90 dereceden başlayarak saat yönünde)
        val sweep = (progressPercent / 100f) * 360f
        canvas.drawArc(ovalRect, -90f, sweep, true, slicePaint)

        // Dilim ayırıcı çizgiler
        val cx = ovalRect.centerX()
        val cy = ovalRect.centerY()
        val r = size / 2f
        canvas.drawLine(cx, cy, cx, cy - r, separatorPaint)
        val rad = Math.toRadians((-90f + sweep).toDouble())
        val ex = cx + (r * Math.cos(rad)).toFloat()
        val ey = cy + (r * Math.sin(rad)).toFloat()
        canvas.drawLine(cx, cy, ex, ey, separatorPaint)

        // Dış çember
        canvas.drawOval(ovalRect, borderPaint)
    }
}
