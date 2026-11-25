package com.donatellatech.ibanascapdf

import android.content.Context
import android.security.KeyChain
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate

/**
 * Clave privada del firmante con las clases del sistema (sin bibliotecas añadidas).
 *
 * El núcleo C++ arma el CMS PAdES y llama a estos métodos (desde JNI) para obtener
 * los certificados y firmar unos bytes. La referencia es:
 *  - la ruta de una copia temporal de un archivo .p12/.pfx (KeyStore "PKCS12"), o
 *  - "keychain:<alias>" para un certificado instalado en Ajustes de Android (KeyChain).
 *
 * Ni la contraseña ni la clave se guardan.
 */
object FirmanteAndroid {
    private const val PREFIJO_KEYCHAIN = "keychain:"

    /** Contexto de la aplicación (para KeyChain). */
    @Volatile var contexto: Context? = null

    private class Clave(val privada: PrivateKey, val cadena: List<X509Certificate>)

    private fun abrir(referencia: String, contrasena: String): Clave {
        if (referencia.startsWith(PREFIJO_KEYCHAIN)) {
            val ctx = contexto ?: throw ErrorNucleo("error.certificado", "")
            val alias = referencia.removePrefix(PREFIJO_KEYCHAIN)
            // KeyChain no se puede usar desde el hilo de la interfaz (aquí, hilo de trabajo).
            val privada = KeyChain.getPrivateKey(ctx, alias) ?: throw ErrorNucleo("error.certificado", alias)
            val cadena = KeyChain.getCertificateChain(ctx, alias)?.toList().orEmpty()
            if (cadena.isEmpty()) throw ErrorNucleo("error.certificado", alias)
            return Clave(privada, cadena)
        }

        val archivo = File(referencia)
        if (!archivo.isFile) throw ErrorNucleo("error.leer", archivo.name)
        val almacen = KeyStore.getInstance("PKCS12")
        try {
            FileInputStream(archivo).use { almacen.load(it, contrasena.toCharArray()) }
        } catch (e: IOException) {
            // Contraseña incorrecta: IOException causada por UnrecoverableKeyException
            // (o mensaje sobre la MAC, según la versión de Android).
            val causa = e.cause
            val mac = e.message?.contains("mac", ignoreCase = true) == true ||
                e.message?.contains("password", ignoreCase = true) == true
            throw ErrorNucleo(
                if (causa is UnrecoverableKeyException || mac) "error.contrasena_certificado" else "error.certificado",
                "",
            )
        } catch (e: Exception) {
            throw ErrorNucleo("error.certificado", "")
        }
        val alias = almacen.aliases().toList().firstOrNull { almacen.isKeyEntry(it) }
            ?: throw ErrorNucleo("error.certificado", "")
        val privada = try {
            almacen.getKey(alias, contrasena.toCharArray()) as? PrivateKey
        } catch (e: UnrecoverableKeyException) {
            throw ErrorNucleo("error.contrasena_certificado", "")
        } ?: throw ErrorNucleo("error.certificado", "")
        val cadena = almacen.getCertificateChain(alias)?.filterIsInstance<X509Certificate>().orEmpty()
        if (cadena.isEmpty()) throw ErrorNucleo("error.certificado", "")
        return Clave(privada, cadena)
    }

    /** Certificados en DER: el del firmante primero. Llamado desde C++. */
    @JvmStatic
    fun certificados(referencia: String, contrasena: String): Array<ByteArray> =
        abrir(referencia, contrasena).cadena.map { it.encoded }.toTypedArray()

    /**
     * Firma [datos] con SHA-256: RSA PKCS#1 v1.5 o ECDSA (DER), como espera el CMS.
     * Llamado desde C++.
     */
    @JvmStatic
    fun firmar(referencia: String, contrasena: String, datos: ByteArray): ByteArray {
        val clave = abrir(referencia, contrasena)
        val algoritmo = when (clave.privada.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> throw ErrorNucleo("error.certificado_algoritmo", clave.privada.algorithm)
        }
        return Signature.getInstance(algoritmo).run {
            initSign(clave.privada)
            update(datos)
            sign()
        }
    }
}
