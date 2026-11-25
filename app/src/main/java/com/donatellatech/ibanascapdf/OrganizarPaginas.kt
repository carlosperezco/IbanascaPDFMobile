package com.donatellatech.ibanascapdf

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Organizar páginas: miniaturas en rejilla que se reordenan manteniéndolas pulsadas
 * y arrastrándolas. Al aceptar se aplica el nuevo orden en un solo paso (se puede deshacer).
 */
class OrganizarPaginas(
    private val actividad: Activity,
    private val tamanos: FloatArray,
    private val fuente: VisorPdf.Fuente,
    private val generacion: Int,
    private val alAceptar: (IntArray) -> Unit,
) {
    private val densidad = actividad.resources.displayMetrics.density
    private fun dp(v: Int) = (v * densidad).roundToInt()

    private val orden = MutableList(tamanos.size / 2) { it }
    private val miniaturas = HashMap<Int, Bitmap>()
    private val pedidas = HashSet<Int>()
    private val anchoMiniatura = dp(96)

    private val adaptador = object : BaseAdapter() {
        override fun getCount() = orden.size
        override fun getItem(pos: Int) = orden[pos]
        override fun getItemId(pos: Int) = orden[pos].toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val caja = (convertView as? LinearLayout) ?: crearCelda()
            val pagina = orden[pos]
            val imagen = caja.getChildAt(0) as ImageView
            val numero = caja.getChildAt(1) as TextView
            val ancho = tamanos[2 * pagina]
            val alto = tamanos[2 * pagina + 1]
            imagen.layoutParams.height = (anchoMiniatura * alto / ancho).roundToInt()
            imagen.setImageBitmap(miniaturas[pagina])
            numero.text = (pagina + 1).toString()
            caja.tag = pos
            if (pagina !in miniaturas && pedidas.add(pagina)) {
                val h = (anchoMiniatura * alto / ancho).roundToInt().coerceAtLeast(1)
                fuente.pedir(pagina, anchoMiniatura, h, generacion) { bmp ->
                    if (bmp != null) {
                        miniaturas[pagina] = bmp
                        notifyDataSetChanged()
                    }
                }
            }
            return caja
        }
    }

    private fun crearCelda(): LinearLayout = LinearLayout(actividad).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        addView(ImageView(actividad).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(0xC5, 0xCD, 0xC2))
            }
        }, LinearLayout.LayoutParams(anchoMiniatura, dp(120)))
        addView(TextView(actividad).apply { gravity = Gravity.CENTER })
        setOnLongClickListener { v ->
            v.startDragAndDrop(ClipData.newPlainText("", ""), View.DragShadowBuilder(v), v.tag, 0)
            true
        }
        setOnDragListener { v, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    v.setBackgroundColor(Color.argb(50, 0x2E, 0x7D, 0x32))
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    v.background = null
                    true
                }
                DragEvent.ACTION_DROP -> {
                    v.background = null
                    val desde = e.localState as? Int ?: return@setOnDragListener false
                    val hasta = v.tag as? Int ?: return@setOnDragListener false
                    if (desde != hasta) {
                        orden.add(hasta, orden.removeAt(desde))
                        adaptador.notifyDataSetChanged()
                    }
                    true
                }
                else -> true
            }
        }
    }

    fun mostrar() {
        val rejilla = GridView(actividad).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = anchoMiniatura + dp(12)
            stretchMode = GridView.STRETCH_SPACING_UNIFORM
            verticalSpacing = dp(4)
            adapter = adaptador
        }
        val caja = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
            addView(TextView(actividad).apply {
                text = T.tr("android.organizar.ayuda")
                setPadding(0, 0, 0, dp(8))
            })
            val alto = (actividad.resources.displayMetrics.heightPixels * 0.6f).roundToInt()
            addView(rejilla, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, alto))
        }
        AlertDialog.Builder(actividad)
            .setTitle(T.tr("android.organizar"))
            .setView(caja)
            .setNegativeButton(T.tr("boton.cancelar"), null)
            .setPositiveButton(T.tr("boton.aceptar")) { _, _ ->
                if (orden != orden.indices.toList()) alAceptar(orden.toIntArray())
            }
            .show()
    }
}
