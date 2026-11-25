package com.donatellatech.ibanascapdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Lienzo para firmar con el dedo (o el lápiz). Guarda los trazos como puntos y los
 * entrega en el formato de dibujo.h ("ibzd1;ancho,alto,grosor;x,y,…;…"); el núcleo
 * los suaviza y los escribe en el PDF como trazado vectorial.
 */
class LienzoFirma(context: Context) : View(context) {
    private val densidad = resources.displayMetrics.density
    private val grosor = 3f * densidad
    private val trazos = ArrayList<ArrayList<Float>>() // x0, y0, x1, y1, …

    /** Color del trazo (el elegido en el diálogo). */
    var color: Int = Color.rgb(0x1F, 0x3A, 0x93)
        set(v) {
            field = v
            pTrazo.color = v
            invalidate()
        }

    private val pTrazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = grosor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = this@LienzoFirma.color
    }
    private val pLinea = Paint().apply {
        color = Color.rgb(0xCC, 0xD1, 0xCC)
        strokeWidth = densidad
    }
    private val pIndicacion = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x9E, 0xA6, 0x9E)
        textSize = 16 * densidad
        textAlign = Paint.Align.CENTER
    }
    private val camino = Path()

    init {
        setBackgroundColor(Color.WHITE)
        minimumHeight = (170 * densidad).toInt()
        contentDescription = T.tr("param.dibujo")
    }

    fun borrar() {
        trazos.clear()
        invalidate()
    }

    fun vacio() = trazos.none { it.isNotEmpty() }

    /** Valor para el núcleo; vacío si no se dibujó nada. */
    fun valor(): String {
        if (vacio()) return ""
        val s = StringBuilder("ibzd1;").append(width).append(',').append(height).append(',').append(numero(grosor))
        for (t in trazos) {
            if (t.isEmpty()) continue
            s.append(';')
            for (i in t.indices) {
                if (i > 0) s.append(',')
                s.append(numero(t[i]))
            }
        }
        return s.toString()
    }

    private fun numero(v: Float): String {
        val r = Math.round(v * 10) / 10f
        return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
    }

    override fun onMeasure(anchoSpec: Int, altoSpec: Int) {
        val ancho = MeasureSpec.getSize(anchoSpec)
        setMeasuredDimension(ancho, resolveSize((170 * densidad).toInt(), altoSpec))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Que el ScrollView del diálogo no se lleve el gesto mientras se firma.
                parent?.requestDisallowInterceptTouchEvent(true)
                trazos.add(arrayListOf(e.x, e.y))
            }
            MotionEvent.ACTION_MOVE -> {
                val t = trazos.lastOrNull() ?: return true
                // Puntos intermedios que el sistema agrupó: trazo más fiel.
                for (h in 0 until e.historySize) agregar(t, e.getHistoricalX(h), e.getHistoricalY(h))
                agregar(t, e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        invalidate()
        return true
    }

    private fun agregar(t: ArrayList<Float>, x: Float, y: Float) {
        val n = t.size
        if (n >= 2 && hypot(x - t[n - 2], y - t[n - 1]) < densidad * 0.75f) return
        t.add(x.coerceIn(0f, width.toFloat()))
        t.add(y.coerceIn(0f, height.toFloat()))
    }

    override fun onDraw(canvas: Canvas) {
        val linea = height * 0.78f
        canvas.drawLine(16 * densidad, linea, width - 16 * densidad, linea, pLinea)
        if (vacio()) canvas.drawText(T.tr("dibujo.indicacion"), width / 2f, linea - 10 * densidad, pIndicacion)
        for (t in trazos) {
            if (t.isEmpty()) continue
            camino.reset()
            camino.moveTo(t[0], t[1])
            if (t.size == 2) camino.lineTo(t[0] + 0.1f, t[1]) // un toque: punto
            var i = 2
            while (i + 1 < t.size) {
                camino.lineTo(t[i], t[i + 1])
                i += 2
            }
            canvas.drawPath(camino, pTrazo)
        }
    }
}
