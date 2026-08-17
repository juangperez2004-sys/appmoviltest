package com.juan.asistenciaapp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Dibuja la caja del rostro y la etiqueta de estado sobre la vista previa. */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var caja: RectF? = null
    private var colorCaja: Int = Color.YELLOW
    private var etiqueta: String = ""

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 44f
        isFakeBoldText = true
        setShadowLayer(10f, 0f, 0f, Color.BLACK)
    }

    /** Actualiza la caja y la etiqueta, y redibuja. */
    fun mostrar(caja: RectF?, color: Int, etiqueta: String) {
        this.caja = caja
        this.colorCaja = color
        this.etiqueta = etiqueta
        invalidate()
    }

    fun limpiar() {
        caja = null
        etiqueta = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = caja ?: return
        paint.color = colorCaja
        canvas.drawRect(c, paint)
        if (etiqueta.isNotBlank()) {
            textPaint.color = colorCaja
            val x = c.left.coerceAtLeast(8f)
            val y = (c.top - 24f).coerceAtLeast(textPaint.textSize + 12f)
            canvas.drawText(etiqueta, x, y, textPaint)
        }
    }
}
