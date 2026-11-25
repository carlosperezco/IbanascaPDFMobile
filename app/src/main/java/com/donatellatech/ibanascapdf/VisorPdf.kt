package com.donatellatech.ibanascapdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Visor continuo de páginas: desplazamiento, zoom con dos dedos, doble toque y, en
 * modo «colocar», toque sobre la página para elegir la posición de un texto, una
 * imagen o una firma.
 *
 * No llama al núcleo: pide cada página dibujada a [fuente] (que usa el hilo de trabajo).
 */
class VisorPdf(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Proveedor de páginas dibujadas (lo implementa MainActivity con [Trabajo]). */
    fun interface Fuente {
        fun pedir(pagina: Int, ancho: Int, alto: Int, generacion: Int, listo: (Bitmap?) -> Unit)
    }

    var fuente: Fuente? = null

    /** Toque en modo colocar: página y punto en píxeles dentro de la página dibujada. */
    var alColocar: ((pagina: Int, x: Int, y: Int, anchoPx: Int, altoPx: Int) -> Unit)? = null

    /** La página del centro cambió (para la barra de estado). */
    var alCambiarPagina: ((Int) -> Unit)? = null

    var modoColocar = false
        set(v) {
            field = v
            invalidate()
        }

    private val densidad = resources.displayMetrics.density
    private val separacion = 10 * densidad
    private val margen = 8 * densidad

    private var tamanos = FloatArray(0) // [ancho0, alto0, ancho1, alto1, …] en puntos
    private var zoom = 1f                // 1 = ajustar al ancho
    private var desplX = 0f
    private var desplY = 0f
    private var escala = 1f              // píxeles por punto
    private var arriba = FloatArray(0)   // borde superior de cada página en el contenido
    private var anchoMax = 1f            // página más ancha, en puntos
    /** Cambia con cada documento: las páginas pedidas antes ya no sirven. */
    @Volatile var generacion = 0
        private set
    private var ultimaPagina = -1

    private class Imagen(val bitmap: Bitmap, val generacion: Int)

    private val cache = object : LruCache<Int, Imagen>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Int, value: Imagen) = value.bitmap.byteCount
    }
    private val pendientes = HashMap<Int, Int>() // página → ancho pedido

    private val pFondo = Paint().apply { color = Color.rgb(0xE6, 0xEA, 0xE4) }
    private val pHoja = Paint().apply { color = Color.WHITE }
    private val pBorde = Paint().apply {
        color = Color.rgb(0xC5, 0xCD, 0xC2)
        style = Paint.Style.STROKE
        strokeWidth = densidad
    }
    private val pImagen = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pColocar = Paint().apply {
        color = Color.argb(40, 0x2E, 0x7D, 0x32)
    }
    private val origen = Rect()
    private val destino = RectF()

    private val desplazador = OverScroller(context)

    private val gestos = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            desplazador.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            mover(dx, dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            desplazador.fling(
                desplX.roundToInt(), desplY.roundToInt(), -vx.roundToInt(), -vy.roundToInt(),
                0, maxX().roundToInt(), 0, maxY().roundToInt(),
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!modoColocar) return false
            tocar(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (modoColocar) return false
            cambiarZoom(if (zoom < 1.5f) 2f else 1f, e.x, e.y)
            return true
        }
    })

    private val pellizco = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            cambiarZoom(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    // ------------------------------------------------------------ API

    /** Nuevo documento o documento cambiado. */
    fun establecer(nuevos: FloatArray, conservarPosicion: Boolean) {
        val pagina = paginaActual()
        tamanos = nuevos
        generacion++
        cache.evictAll()
        pendientes.clear()
        if (!conservarPosicion) {
            zoom = 1f
            desplX = 0f
            desplY = 0f
            ultimaPagina = -1
        }
        recalcular()
        if (conservarPosicion && paginas() > 0) {
            val p = pagina.coerceIn(0, paginas() - 1)
            desplY = arriba[p] - margen
            limitar()
        }
        invalidate()
        avisarPagina()
    }

    fun paginas() = tamanos.size / 2

    /** Página (base 0) que ocupa el centro de la vista. */
    fun paginaActual(): Int {
        if (paginas() == 0) return 0
        val centro = desplY + height / 2f
        for (i in 0 until paginas()) if (centro < arriba[i] + altoPx(i) + separacion / 2) return i
        return paginas() - 1
    }

    fun irAPagina(p: Int) {
        if (paginas() == 0) return
        desplazador.forceFinished(true)
        desplY = arriba[p.coerceIn(0, paginas() - 1)] - margen
        limitar()
        invalidate()
        avisarPagina()
    }

    fun ajustarAncho() {
        val p = paginaActual()
        zoom = 1f
        recalcular()
        irAPagina(p)
    }

    fun ajustarPagina() {
        if (paginas() == 0 || height == 0) return
        val p = paginaActual()
        val base = baseEscala()
        val alto = tamanos[2 * p + 1] * base
        zoom = min(1f, (height - 2 * margen) / max(1f, alto))
        recalcular()
        irAPagina(p)
    }

    // ------------------------------------------------------------ geometría

    private fun baseEscala(): Float = max(1f, width - 2 * margen) / anchoMax

    private fun recalcular() {
        anchoMax = 1f
        for (i in 0 until paginas()) anchoMax = max(anchoMax, tamanos[2 * i])
        escala = baseEscala() * zoom
        arriba = FloatArray(paginas())
        var y = margen
        for (i in 0 until paginas()) {
            arriba[i] = y
            y += tamanos[2 * i + 1] * escala + separacion
        }
        limitar()
    }

    private fun anchoPx(i: Int) = tamanos[2 * i] * escala
    private fun altoPx(i: Int) = tamanos[2 * i + 1] * escala
    private fun anchoContenido() = anchoMax * escala + 2 * margen
    private fun altoContenido(): Float = if (paginas() == 0) 0f else arriba.last() + altoPx(paginas() - 1) + margen
    private fun maxX() = max(0f, anchoContenido() - width)
    private fun maxY() = max(0f, altoContenido() - height)

    /** Desplazamiento horizontal para centrar el contenido si es más estrecho que la vista. */
    private fun centradoX() = max(0f, (width - anchoContenido()) / 2)

    private fun izquierda(i: Int) = margen + (anchoMax - tamanos[2 * i]) * escala / 2

    private fun limitar() {
        desplX = desplX.coerceIn(0f, maxX())
        desplY = desplY.coerceIn(0f, maxY())
    }

    private fun mover(dx: Float, dy: Float) {
        desplX += dx
        desplY += dy
        limitar()
        invalidate()
        avisarPagina()
    }

    private fun cambiarZoom(nuevo: Float, fx: Float, fy: Float) {
        if (paginas() == 0) return
        // Punto del contenido bajo el foco: página y fracción, para conservarlo.
        val cy = desplY + fy
        var p = paginas() - 1
        for (i in 0 until paginas()) if (cy < arriba[i] + altoPx(i)) { p = i; break }
        val fraccionY = (cy - arriba[p]) / max(1f, altoPx(p))
        val cx = (desplX + fx - centradoX()) / max(1f, anchoContenido())

        zoom = nuevo.coerceIn(0.4f, 6f)
        recalcular()
        desplY = arriba[p] + fraccionY * altoPx(p) - fy
        desplX = cx * anchoContenido() - fx + centradoX()
        limitar()
        invalidate()
    }

    private fun avisarPagina() {
        val p = paginaActual()
        if (p != ultimaPagina) {
            ultimaPagina = p
            alCambiarPagina?.invoke(p)
        }
    }

    private fun tocar(x: Float, y: Float) {
        val cx = x + desplX - centradoX()
        val cy = y + desplY
        for (i in 0 until paginas()) {
            val l = izquierda(i)
            if (cy >= arriba[i] && cy <= arriba[i] + altoPx(i) && cx >= l && cx <= l + anchoPx(i)) {
                alColocar?.invoke(i, (cx - l).roundToInt(), (cy - arriba[i]).roundToInt(),
                    anchoPx(i).roundToInt(), altoPx(i).roundToInt())
                return
            }
        }
    }

    // ------------------------------------------------------------ dibujo

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (paginas() > 0) {
            val p = paginaActual()
            recalcular()
            if (ow != w) irAPagina(p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        pellizco.onTouchEvent(e)
        if (!pellizco.isInProgress) gestos.onTouchEvent(e)
        return true
    }

    override fun computeScroll() {
        if (desplazador.computeScrollOffset()) {
            desplX = desplazador.currX.toFloat()
            desplY = desplazador.currY.toFloat()
            limitar()
            postInvalidateOnAnimation()
            avisarPagina()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFondo)
        if (paginas() == 0) return
        val dx = centradoX() - desplX
        // Ancho máximo de lo que se dibuja: suficiente nitidez sin agotar la memoria.
        val limite = min(4096f, max(width, height) * 2.5f)
        for (i in 0 until paginas()) {
            val top = arriba[i] - desplY
            val alto = altoPx(i)
            if (top > height) break
            if (top + alto < 0) continue
            val left = izquierda(i) + dx
            destino.set(left, top, left + anchoPx(i), top + alto)
            canvas.drawRect(destino, pHoja)

            val deseado = min(anchoPx(i), limite * anchoPx(i) / max(anchoPx(i), alto)).roundToInt().coerceAtLeast(1)
            val img = cache.get(i)
            if (img != null) {
                origen.set(0, 0, img.bitmap.width, img.bitmap.height)
                canvas.drawBitmap(img.bitmap, origen, destino, pImagen)
            }
            if (img == null || abs(img.bitmap.width - deseado) > deseado * 0.15f) pedir(i, deseado)
            canvas.drawRect(destino, pBorde)
            if (modoColocar) canvas.drawRect(destino, pColocar)
        }
    }

    private fun pedir(i: Int, ancho: Int) {
        if (pendientes[i] == ancho) return
        val f = fuente ?: return
        pendientes[i] = ancho
        val alto = (ancho * tamanos[2 * i + 1] / tamanos[2 * i]).roundToInt().coerceAtLeast(1)
        val gen = generacion
        f.pedir(i, ancho, alto, gen) { bmp ->
            if (gen != generacion) return@pedir
            if (pendientes[i] == ancho) pendientes.remove(i)
            if (bmp != null) {
                cache.put(i, Imagen(bmp, gen))
                invalidate()
            }
        }
    }
}
