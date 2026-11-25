package com.donatellatech.ibanascapdf

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File

/** Lo que el diálogo necesita de la actividad: selectores del sistema. */
interface Selectores {
    /** Selector de documentos; devuelve las copias en caché (rutas) o lista vacía si se cancela. */
    fun elegirArchivos(tipos: Array<String>, multiple: Boolean, listo: (List<File>) -> Unit)

    /** Certificado instalado en Android (KeyChain); devuelve el alias o null. */
    fun elegirCertificadoInstalado(listo: (String?) -> Unit)
}

/** Datos del documento para los valores por defecto ("@actual", "@carpeta", "@nombre"). */
data class ContextoDialogo(val paginaActual: Int, val totalPaginas: Int, val carpetaSalida: File, val nombre: String)

/** Colores de la paleta (los mismos que en escritorio). */
val COLORES = listOf(
    "#000000" to "color.negro", "#1f3a93" to "color.azul_oscuro", "#1e66f5" to "color.azul",
    "#c62828" to "color.rojo", "#2e7d32" to "color.verde", "#ef6c00" to "color.naranja",
    "#6a1b9a" to "color.morado", "#616161" to "color.gris",
)

/**
 * Diálogo genérico de parámetros, construido a partir de la descripción del módulo
 * (igual que el de GTK en escritorio): un módulo nuevo del núcleo aparece aquí sin
 * escribir interfaz.
 */
class DialogoParametros(
    private val actividad: Activity,
    private val modulo: ModuloInfo,
    private val ctx: ContextoDialogo,
    private val selectores: Selectores,
    private val alAceptar: (MutableMap<String, String>) -> Unit,
) {
    private val preferencias = actividad.getSharedPreferences("ibanasca", Context.MODE_PRIVATE)
    private val densidad = actividad.resources.displayMetrics.density

    private class Fila(val p: ParametroInfo, val vista: View, val leer: () -> String)

    private val filas = ArrayList<Fila>()
    private var lienzo: LienzoFirma? = null

    private fun dp(v: Int) = (v * densidad).toInt()

    fun mostrar() {
        val caja = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        for (p in modulo.parametros) {
            val fila = crearFila(p) ?: continue
            filas.add(fila)
            caja.addView(fila.vista)
        }
        // El lienzo usa el color elegido.
        filas.firstOrNull { it.p.tipo == "color" }?.let { actualizarColorLienzo(it.leer()) }
        actualizarVisibles()

        val dialogo = AlertDialog.Builder(actividad)
            .setTitle(T.tr("modulo.${modulo.id}.nombre"))
            .setView(ScrollView(actividad).apply { addView(caja) })
            .setNegativeButton(T.tr("boton.cancelar"), null)
            .setPositiveButton(T.tr("boton.aceptar")) { _, _ -> aceptar() }
            .create()
        dialogo.show()
    }

    private fun aceptar() {
        val valores = LinkedHashMap<String, String>()
        val ed = preferencias.edit()
        for (f in filas) {
            if (f.p.tipo != "carpeta" && f.vista.visibility != View.VISIBLE) continue // oculto: no se envía
            val v = f.leer()
            valores[f.p.id] = v
            if (f.p.tipo == "opcion" || f.p.tipo == "entero" || f.p.tipo == "color")
                ed.putString(claveRecuerdo(f.p), v)
        }
        ed.apply()
        alAceptar(valores)
    }

    private fun claveRecuerdo(p: ParametroInfo) = "recordar.${modulo.id}.${p.id}"

    private fun defecto(p: ParametroInfo): String {
        val recordado = preferencias.getString(claveRecuerdo(p), null)
        val d = if (recordado != null && (p.tipo == "opcion" || p.tipo == "entero" || p.tipo == "color")) recordado else p.defecto
        return when (d) {
            "@actual" -> (ctx.paginaActual + 1).toString()
            "@carpeta" -> ctx.carpetaSalida.absolutePath
            "@nombre" -> ctx.nombre
            else -> d
        }
    }

    private fun etiqueta(texto: String) = TextView(actividad).apply {
        this.text = texto
        setPadding(0, dp(12), 0, dp(4))
        setTextColor(Color.rgb(0x4B, 0x5A, 0x48))
    }

    private fun contenedor(p: ParametroInfo, control: View): LinearLayout = LinearLayout(actividad).apply {
        orientation = LinearLayout.VERTICAL
        addView(etiqueta(T.tr(p.clave).trimEnd(':', ' ')))
        addView(control, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun crearFila(p: ParametroInfo): Fila? {
        val d = defecto(p)
        return when (p.tipo) {
            "entero" -> {
                val e = EditText(actividad).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(d)
                    setSelectAllOnFocus(true)
                }
                val maximo = if (p.maximo < 0) maxOf(p.minimo, ctx.totalPaginas) else p.maximo
                Fila(p, contenedor(p, e)) {
                    (e.text.toString().toIntOrNull() ?: d.toIntOrNull() ?: p.minimo).coerceIn(p.minimo, maxOf(p.minimo, maximo)).toString()
                }
            }
            "texto", "paginas", "contrasena" -> {
                val e = EditText(actividad).apply {
                    setSingleLine()
                    setText(d)
                    if (p.tipo == "paginas") hint = "1-3,5,8-"
                    if (p.tipo == "contrasena") inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                Fila(p, contenedor(p, e)) { e.text.toString() }
            }
            "texto_largo" -> {
                val e = EditText(actividad).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 3
                    gravity = Gravity.TOP or Gravity.START
                    setText(d)
                }
                Fila(p, contenedor(p, e)) { e.text.toString() }
            }
            "opcion" -> {
                val s = Spinner(actividad)
                s.adapter = ArrayAdapter(actividad, android.R.layout.simple_spinner_dropdown_item,
                    p.opciones.map { T.tr("${p.clave}.$it") })
                s.setSelection(p.opciones.indexOf(d).coerceAtLeast(0))
                s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) = actualizarVisibles()
                    override fun onNothingSelected(a: AdapterView<*>?) {}
                }
                Fila(p, contenedor(p, s)) { p.opciones.getOrElse(s.selectedItemPosition) { "" } }
            }
            "color" -> {
                val s = Spinner(actividad)
                s.adapter = object : ArrayAdapter<String>(actividad, android.R.layout.simple_spinner_dropdown_item,
                    COLORES.map { T.tr(it.second) }) {
                    override fun getView(pos: Int, v: View?, padre: ViewGroup): View = conMuestra(super.getView(pos, v, padre), pos)
                    override fun getDropDownView(pos: Int, v: View?, padre: ViewGroup): View =
                        conMuestra(super.getDropDownView(pos, v, padre), pos)
                }
                s.setSelection(COLORES.indexOfFirst { it.first.equals(d, ignoreCase = true) }.coerceAtLeast(0))
                s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                        actualizarColorLienzo(COLORES[pos].first)
                    override fun onNothingSelected(a: AdapterView<*>?) {}
                }
                Fila(p, contenedor(p, s)) { COLORES.getOrElse(s.selectedItemPosition) { COLORES[0] }.first }
            }
            "archivo_imagen" -> archivo(p, arrayOf("image/png"), null)
            "archivo_certificado" -> archivo(p, arrayOf("application/x-pkcs12", "application/octet-stream", "*/*"),
                T.tr("android.certificado_instalado"))
            "archivos_pdf" -> listaPdf(p)
            "carpeta" -> {
                // En Android los archivos se crean en la caché y después se copian a la
                // carpeta que el usuario elija con el selector del sistema.
                val v = View(actividad).apply { visibility = View.GONE }
                Fila(p, v) { ctx.carpetaSalida.absolutePath }
            }
            "dibujo" -> {
                val l = LienzoFirma(actividad)
                lienzo = l
                val borrar = Button(actividad).apply {
                    text = T.tr("dibujo.borrar")
                    setOnClickListener { l.borrar() }
                }
                val caja = LinearLayout(actividad).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)).apply {
                        topMargin = dp(4)
                    })
                    addView(borrar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
                }
                l.background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.rgb(0xC5, 0xCD, 0xC2))
                    cornerRadius = dp(6).toFloat()
                }
                Fila(p, contenedor(p, caja)) { l.valor() }
            }
            else -> null
        }
    }

    private fun conMuestra(v: View, pos: Int): View {
        (v as? TextView)?.let { tv ->
            val muestra = GradientDrawable().apply {
                setColor(Color.parseColor(COLORES[pos].first))
                setSize(dp(16), dp(16))
                cornerRadius = dp(3).toFloat()
            }
            tv.setCompoundDrawablesRelativeWithIntrinsicBounds(muestra, null, null, null)
            tv.compoundDrawablePadding = dp(10)
        }
        return v
    }

    /** Elegir un archivo (imagen o certificado); [otra] añade «Certificado instalado…». */
    private fun archivo(p: ParametroInfo, tipos: Array<String>, otra: String?): Fila {
        var valor = ""
        val nombre = TextView(actividad).apply {
            text = T.tr(if (otra != null) "android.certificado_ninguno" else "android.ninguno")
            setPadding(0, dp(4), 0, 0)
        }
        val botones = LinearLayout(actividad).apply { orientation = LinearLayout.HORIZONTAL }
        botones.addView(Button(actividad).apply {
            text = if (otra != null) T.tr("android.certificado_archivo") else T.tr("boton.elegir")
            setOnClickListener {
                selectores.elegirArchivos(tipos, false) { lista ->
                    lista.firstOrNull()?.let {
                        valor = it.absolutePath
                        nombre.text = it.name
                    }
                }
            }
        })
        if (otra != null) {
            botones.addView(Button(actividad).apply {
                text = otra
                setOnClickListener {
                    selectores.elegirCertificadoInstalado { alias ->
                        if (alias != null) {
                            valor = "keychain:$alias"
                            nombre.text = alias
                        }
                    }
                }
            })
        }
        val caja = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            addView(botones)
            addView(nombre)
        }
        return Fila(p, contenedor(p, caja)) { valor }
    }

    /** Lista ordenada de PDF para fusionar. */
    private fun listaPdf(p: ParametroInfo): Fila {
        val archivos = ArrayList<File>()
        val lista = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }

        fun redibujar() {
            lista.removeAllViews()
            archivos.forEachIndexed { i, f ->
                val fila = LinearLayout(actividad).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                fila.addView(TextView(actividad).apply { text = f.name },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fun mini(t: String, accion: () -> Unit) = Button(actividad, null, android.R.attr.borderlessButtonStyle).apply {
                    text = t
                    minWidth = 0
                    setOnClickListener {
                        accion()
                        redibujar()
                    }
                }
                fila.addView(mini("↑") { if (i > 0) archivos.add(i - 1, archivos.removeAt(i)) })
                fila.addView(mini("↓") { if (i + 1 < archivos.size) archivos.add(i + 1, archivos.removeAt(i)) })
                fila.addView(mini("✕") { archivos.removeAt(i) })
                lista.addView(fila)
            }
        }

        val anadir = Button(actividad).apply {
            text = T.tr("boton.anadir")
            setOnClickListener {
                selectores.elegirArchivos(arrayOf("application/pdf"), true) { nuevos ->
                    archivos.addAll(nuevos)
                    redibujar()
                }
            }
        }
        val caja = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            addView(lista)
            addView(anadir)
        }
        return Fila(p, contenedor(p, caja)) { archivos.joinToString("\n") { it.absolutePath } }
    }

    private fun actualizarColorLienzo(hex: String) {
        lienzo?.color = runCatching { Color.parseColor(hex) }.getOrDefault(Color.BLACK)
    }

    /** Muestra u oculta las filas según visibleSi (igual que en escritorio). */
    private fun actualizarVisibles() {
        val opcion = { id: String -> filas.firstOrNull { it.p.id == id && it.p.tipo == "opcion" }?.leer() ?: "" }
        for (f in filas) {
            if (f.p.tipo == "carpeta") continue
            f.vista.visibility = if (parametroVisible(f.p, opcion)) View.VISIBLE else View.GONE
        }
    }
}
