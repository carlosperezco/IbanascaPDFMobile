package com.donatellatech.ibanascapdf

import android.graphics.Bitmap
import org.json.JSONArray

/**
 * Error del núcleo C++: [clave] es la clave de traducción ("error.contrasena", …)
 * y el mensaje ya viene traducido.
 */
class ErrorNucleo(val clave: String, mensaje: String) : Exception(mensaje)

/** Parámetro de un módulo, tal como lo describe el núcleo (ver modulo.h). */
data class ParametroInfo(
    val id: String,
    val clave: String,
    val tipo: String,
    val defecto: String,
    val minimo: Int,
    val maximo: Int,
    val visibleSi: String,
    val opciones: List<String>,
)

/** Operación del núcleo (rotar, fusionar, firmar…). */
data class ModuloInfo(
    val id: String,
    val menu: String,
    val orden: Int,
    val modifica: Boolean,
    val requierePosicion: Boolean,
    val parametros: List<ParametroInfo>,
)

/**
 * Acceso al núcleo C++ (biblioteca libibanascapdf.so, compilada desde el submódulo
 * nucleo/). Las funciones se registran en JNI_OnLoad.
 *
 * Todas las llamadas se hacen desde el hilo de trabajo de [MainActivity]
 * (ver [Trabajo]); nunca desde el hilo de la interfaz.
 */
object Nucleo {
    init {
        System.loadLibrary("ibanascapdf")
    }

    external fun iniciar(datos: String, usuario: String, idioma: String)
    external fun cambiarIdioma(codigo: String): Boolean
    external fun idiomaActual(): String
    external fun traducciones(): Array<String>
    external fun idiomas(): Array<String>

    external fun abrir(datos: ByteArray, nombre: String, contrasena: String)
    external fun nuevo(anchoPt: Double, altoPt: Double)
    external fun cerrar()
    external fun abierto(): Boolean
    external fun paginas(): Int
    external fun tamanos(): FloatArray
    external fun dibujar(pagina: Int, bitmap: Bitmap, inicioX: Int, inicioY: Int, anchoPagina: Int, altoPagina: Int): Boolean
    external fun aPagina(pagina: Int, anchoPx: Int, altoPx: Int, x: Int, y: Int): DoubleArray?
    external fun bytes(): ByteArray
    external fun marcarGuardado(nombre: String)
    external fun nombre(): String
    external fun estado(): BooleanArray
    external fun deshacer()
    external fun rehacer()
    external fun modulos(): String
    external fun posicionTrasDialogo(id: String, claves: Array<String>, valores: Array<String>): Boolean
    external fun ejecutar(id: String, claves: Array<String>, valores: Array<String>): String
    external fun reordenar(orden: IntArray)

    /** Módulos con sus parámetros, en el orden de los menús. */
    fun listaModulos(): List<ModuloInfo> {
        val a = JSONArray(modulos())
        return (0 until a.length()).map { i ->
            val m = a.getJSONObject(i)
            val ps = m.getJSONArray("parametros")
            ModuloInfo(
                id = m.getString("id"),
                menu = m.getString("menu"),
                orden = m.getInt("orden"),
                modifica = m.getBoolean("modifica"),
                requierePosicion = m.getBoolean("posicion"),
                parametros = (0 until ps.length()).map { j ->
                    val p = ps.getJSONObject(j)
                    val op = p.getJSONArray("opciones")
                    ParametroInfo(
                        id = p.getString("id"),
                        clave = p.getString("clave"),
                        tipo = p.getString("tipo"),
                        defecto = p.getString("defecto"),
                        minimo = p.getInt("minimo"),
                        maximo = p.getInt("maximo"),
                        visibleSi = p.getString("visibleSi"),
                        opciones = (0 until op.length()).map { op.getString(it) },
                    )
                },
            )
        }
    }

    fun ejecutar(id: String, valores: Map<String, String>): String =
        ejecutar(id, valores.keys.toTypedArray(), valores.values.toTypedArray())

    fun posicionTrasDialogo(id: String, valores: Map<String, String>): Boolean =
        posicionTrasDialogo(id, valores.keys.toTypedArray(), valores.values.toTypedArray())
}

/**
 * Traducciones copiadas del núcleo (archivos .lang del submódulo): la interfaz las
 * consulta sin llamar a C++.
 */
object T {
    @Volatile private var textos: Map<String, String> = emptyMap()

    /** Copia la tabla del núcleo (llamar desde el hilo de trabajo). */
    fun cargarDelNucleo() {
        val pares = Nucleo.traducciones()
        val m = HashMap<String, String>(pares.size)
        var i = 0
        while (i + 1 < pares.size) {
            m[pares[i]] = pares[i + 1]
            i += 2
        }
        textos = m
    }

    /** Texto traducido sin los guiones bajos de los atajos de GTK ("_Abrir" → "Abrir"). */
    fun tr(clave: String): String = (textos[clave] ?: clave).replace("_", "")

    /** Texto con {1}, {2}… sustituidos. */
    fun tr(clave: String, vararg args: String): String {
        var s = tr(clave)
        args.forEachIndexed { i, a -> s = s.replace("{${i + 1}}", a) }
        return s
    }
}

/** Matriz de parámetros: ¿se muestra [p] con los valores actuales? (igual que modulo.h). */
fun parametroVisible(p: ParametroInfo, valorDe: (String) -> String): Boolean {
    if (p.visibleSi.isEmpty()) return true
    val igual = p.visibleSi.indexOf('=')
    if (igual < 0) return true
    val actual = valorDe(p.visibleSi.substring(0, igual))
    return p.visibleSi.substring(igual + 1).split('|').any { it == actual }
}
