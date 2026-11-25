package com.donatellatech.ibanascapdf

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Entrega a otras apps, solo para leer, el PDF que se comparte.
 *
 * Hace lo mismo que FileProvider de AndroidX, sin añadir esa biblioteca: sirve únicamente
 * los archivos de la carpeta de caché «compartir» y no está exportado; las demás apps solo
 * pueden leer el archivo concreto al que se les da permiso al compartir.
 */
class ProveedorCompartir : ContentProvider() {

    companion object {
        private const val CARPETA = "compartir"

        private fun autoridad(contexto: Context) = contexto.packageName + ".compartir"

        /** Carpeta de caché donde se deja el PDF que se va a compartir (se vacía cada vez). */
        fun carpetaNueva(contexto: Context): File = File(contexto.cacheDir, CARPETA).apply {
            deleteRecursively()
            mkdirs()
        }

        /** Dirección content:// del archivo [f] de esa carpeta. */
        fun uri(contexto: Context, f: File): Uri =
            Uri.Builder().scheme("content").authority(autoridad(contexto)).appendPath(f.name).build()
    }

    /** Archivo pedido, solo si está directamente dentro de la carpeta «compartir». */
    private fun archivo(uri: Uri): File {
        val contexto = context ?: throw FileNotFoundException()
        val nombre = uri.lastPathSegment ?: throw FileNotFoundException()
        val carpeta = File(contexto.cacheDir, CARPETA).canonicalFile
        val f = File(carpeta, nombre).canonicalFile
        if (f.parentFile != carpeta || !f.isFile) throw FileNotFoundException(nombre)
        return f
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/pdf"

    override fun openFile(uri: Uri, modo: String): ParcelFileDescriptor {
        if (modo != "r") throw SecurityException("Solo lectura")
        return ParcelFileDescriptor.open(archivo(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Nombre y tamaño, que muestran las apps que reciben el archivo. */
    override fun query(uri: Uri, proyeccion: Array<out String>?, seleccion: String?,
                       args: Array<out String>?, orden: String?): Cursor {
        val f = archivo(uri)
        val columnas = proyeccion?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val valores = columnas.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() }
        return MatrixCursor(columnas, 1).apply { addRow(valores) }
    }

    override fun insert(uri: Uri, valores: ContentValues?): Uri? = null
    override fun delete(uri: Uri, seleccion: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, valores: ContentValues?, seleccion: String?, args: Array<out String>?) = 0
}
