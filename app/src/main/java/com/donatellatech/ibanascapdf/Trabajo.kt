package com.donatellatech.ibanascapdf

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.Executors

/**
 * Hilo único de trabajo: todas las llamadas al núcleo C++ pasan por aquí, en orden,
 * y el resultado vuelve al hilo de la interfaz.
 */
object Trabajo {
    private val hilo = Executors.newSingleThreadExecutor { r -> Thread(r, "ibanasca-nucleo") }
    private val principal = Handler(Looper.getMainLooper())

    /** Ejecuta [tarea] en el hilo de trabajo y entrega el resultado (o el error) en la interfaz. */
    fun <R> hacer(tarea: () -> R, alTerminar: (Result<R>) -> Unit) {
        hilo.execute {
            val r = runCatching(tarea)
            principal.post { alTerminar(r) }
        }
    }

    /** Ejecuta [accion] en el hilo de la interfaz. */
    fun enInterfaz(accion: () -> Unit) {
        principal.post(accion)
    }
}

/** Utilidades de archivos con el selector del sistema (Storage Access Framework). */
object Archivos {
    /** Nombre visible del documento de [uri] ("informe.pdf"). */
    fun nombre(cr: ContentResolver, uri: Uri): String {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "documento.pdf"
    }

    fun leer(cr: ContentResolver, uri: Uri): ByteArray =
        cr.openInputStream(uri)?.use { it.readBytes() } ?: throw ErrorNucleo("error.leer", T.tr("error.leer"))

    fun escribir(cr: ContentResolver, uri: Uri, datos: ByteArray) {
        cr.openOutputStream(uri, "wt")?.use { it.write(datos) } ?: throw ErrorNucleo("error.escribir", T.tr("error.escribir"))
    }

    /**
     * Copia [uri] a la carpeta de caché (el núcleo trabaja con rutas). Devuelve la ruta;
     * la copia se borra al terminar la operación ([vaciar]).
     */
    fun aCache(cache: File, cr: ContentResolver, uri: Uri): File {
        val carpeta = File(cache, "entrada").apply { mkdirs() }
        val nombre = nombre(cr, uri).replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var destino = File(carpeta, nombre)
        var n = 1
        while (destino.exists()) destino = File(carpeta, "${n++}_$nombre")
        cr.openInputStream(uri)?.use { ent -> destino.outputStream().use { ent.copyTo(it) } }
            ?: throw ErrorNucleo("error.leer", T.tr("error.leer"))
        return destino
    }

    /** Carpeta vacía donde «Separar» deja sus archivos antes de copiarlos al destino. */
    fun salida(cache: File): File = File(cache, "salida").apply {
        deleteRecursively()
        mkdirs()
    }

    /** Borra las copias temporales (certificados, imágenes, PDF añadidos). */
    fun vaciar(cache: File) {
        File(cache, "entrada").deleteRecursively()
    }

    /** Copia los archivos de [origen] a la carpeta elegida [arbol]; devuelve cuántos copió. */
    fun copiarACarpeta(cr: ContentResolver, arbol: Uri, origen: File): Int {
        val padre = DocumentsContract.buildDocumentUriUsingTree(arbol, DocumentsContract.getTreeDocumentId(arbol))
        var n = 0
        origen.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val uri = DocumentsContract.createDocument(cr, padre, "application/pdf", f.name) ?: return@forEach
            cr.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            n++
        }
        origen.deleteRecursively()
        return n
    }
}
