package com.donatellatech.ibanascapdf

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.security.KeyChain
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.util.Locale

/**
 * Ventana principal de Ibanasca PDF Mobile (versión Android de Ibanasca PDF Lab).
 *
 * Toda la lógica de PDF está en el núcleo C++ compartido con escritorio; aquí solo
 * hay interfaz: menús generados a partir de los módulos, visor, selectores del sistema.
 * El archivo original nunca se sobrescribe: se guarda siempre con «Guardar como».
 */
class MainActivity : Activity(), Selectores {

    /** Estado del documento leído del núcleo tras cada operación. */
    private data class Estado(
        val abierto: Boolean = false,
        val tamanos: FloatArray = FloatArray(0),
        val nombre: String = "",
        val modificado: Boolean = false,
        val deshacer: Boolean = false,
        val rehacer: Boolean = false,
        val esNuevo: Boolean = false,
    )

    private lateinit var visor: VisorPdf
    private lateinit var barra: Toolbar
    private lateinit var textoEstado: TextView
    private lateinit var vacio: View
    private lateinit var progreso: ProgressBar

    private var estado = Estado()
    private var modulos: List<ModuloInfo> = emptyList()
    private var idiomas: List<Pair<String, String>> = emptyList()
    @Volatile private var idiomaCodigo = "en"
    private var listo = false
    private var ocupado = false
    private var colocarPendiente: ((Map<String, String>) -> Unit)? = null

    private val acciones = HashMap<Int, () -> Unit>()
    private val resultados = HashMap<Int, (Int, Intent?) -> Unit>()
    private var siguientePeticion = 100

    private val preferencias by lazy { getSharedPreferences("ibanasca", MODE_PRIVATE) }

    /** Nombre de la app (res/values/strings.xml). */
    private val nombreApp by lazy { getString(R.string.app_name) }

    /** «Acerca de …» con el nombre de la app (el texto del núcleo nombra la versión de escritorio). */
    private fun tituloAcercaDe() = T.tr("accion.acerca_de").replace("Ibanasca PDF Lab", nombreApp)

    // ------------------------------------------------------------ ciclo de vida

    override fun onCreate(guardado: Bundle?) {
        super.onCreate(guardado)
        setContentView(R.layout.actividad_principal)
        barra = findViewById(R.id.barra)
        setActionBar(barra)
        visor = findViewById(R.id.visor)
        textoEstado = findViewById(R.id.estado)
        vacio = findViewById(R.id.vacio)
        progreso = findViewById(R.id.ocupado)
        aplicarMargenesDelSistema()

        FirmanteAndroid.contexto = applicationContext
        visor.fuente = VisorPdf.Fuente { pagina, ancho, alto, generacion, entregar ->
            Trabajo.hacer({
                if (generacion != visor.generacion) return@hacer null // documento cambiado: ya no sirve
                val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
                if (Nucleo.dibujar(pagina, bmp, 0, 0, ancho, alto)) bmp else null
            }) { r -> entregar(r.getOrNull()) }
        }
        visor.alCambiarPagina = { actualizarBarraEstado() }
        visor.alColocar = { pagina, x, y, ancho, alto -> colocado(pagina, x, y, ancho, alto) }
        findViewById<Button>(R.id.vacio_abrir).setOnClickListener { accionAbrir() }
        findViewById<Button>(R.id.vacio_nuevo).setOnClickListener { accionNuevo() }
        textoEstado.setOnClickListener { if (visor.modoColocar) cancelarColocar() }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { atras() }
        }

        iniciarNucleo(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        abrirDesdeIntent(intent)
    }

    @Deprecated("Solo para Android < 13; en las versiones nuevas se usa OnBackInvokedCallback")
    override fun onBackPressed() {
        atras()
    }

    private fun atras() {
        when {
            visor.modoColocar -> cancelarColocar()
            estado.modificado -> confirmarDescartar { finish() }
            else -> finish()
        }
    }

    /** Desde Android 15 la app ocupa toda la pantalla: se apartan las barras del sistema. */
    private fun aplicarMargenesDelSistema() {
        val raiz = findViewById<View>(R.id.raiz)
        raiz.setOnApplyWindowInsetsListener { _, insets ->
            val (izq, arr, der, aba) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                listOf(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            barra.setPadding(0, arr, 0, 0)
            raiz.setPadding(izq, 0, der, aba)
            insets
        }
    }

    /** Copia los idiomas del APK a la carpeta de la app e inicia el núcleo. */
    private fun iniciarNucleo(intencion: Intent?) {
        val datos = filesDir
        val idioma = preferencias.getString("idioma", null)
            ?: Locale.getDefault().language.takeIf { it in listOf("es", "en", "it") } ?: "en"
        ocupar(true)
        Trabajo.hacer({
            val carpetaLang = File(datos, "lang").apply { mkdirs() }
            assets.list("")?.filter { it.endsWith(".lang") }?.forEach { nombre ->
                assets.open(nombre).use { ent -> File(carpetaLang, nombre).outputStream().use { ent.copyTo(it) } }
            }
            Nucleo.iniciar(datos.absolutePath, File(datos, "ajustes").absolutePath, idioma)
            idiomaCodigo = Nucleo.idiomaActual()
            T.cargarDelNucleo()
            val pares = Nucleo.idiomas()
            Triple(Nucleo.listaModulos(), (pares.indices step 2).map { pares[it] to pares[it + 1] }, leerEstado())
        }) { r ->
            ocupar(false)
            r.onSuccess { (m, i, e) ->
                modulos = m
                idiomas = i
                listo = true
                aplicarEstado(e, conservarPosicion = true)
                abrirDesdeIntent(intencion)
            }
            r.onFailure { mostrarError(it) }
        }
    }

    private fun abrirDesdeIntent(intencion: Intent?) {
        val uri = intencion?.data ?: return
        if (intencion.action != Intent.ACTION_VIEW || !listo) return
        intencion.data = null
        confirmarDescartar { abrirUri(uri, "") }
    }

    // ------------------------------------------------------------ estado

    /** Se llama en el hilo de trabajo. */
    private fun leerEstado(): Estado {
        if (!Nucleo.abierto()) return Estado()
        val e = Nucleo.estado()
        return Estado(true, Nucleo.tamanos(), Nucleo.nombre(), e[0], e[1], e[2], e[3])
    }

    private fun aplicarEstado(nuevo: Estado, conservarPosicion: Boolean) {
        estado = nuevo
        visor.establecer(nuevo.tamanos, conservarPosicion)
        vacio.visibility = if (nuevo.abierto) View.GONE else View.VISIBLE
        barra.title = if (nuevo.abierto) (if (nuevo.modificado) "• " else "") + nuevo.nombre else nombreApp
        findViewById<TextView>(R.id.vacio_texto).text = T.tr("estado.sin_documento")
        findViewById<Button>(R.id.vacio_abrir).text = T.tr("accion.abrir").trimEnd('…', '.')
        findViewById<Button>(R.id.vacio_nuevo).text = T.tr("accion.nuevo").trimEnd('…', '.')
        actualizarBarraEstado()
        invalidateOptionsMenu()
    }

    private fun actualizarBarraEstado() {
        textoEstado.text = when {
            visor.modoColocar -> T.tr("android.tocar_pagina") + "  ·  " + T.tr("boton.cancelar")
            !estado.abierto -> T.tr("estado.sin_documento")
            else -> T.tr("estado.pagina_antes") + " " + (visor.paginaActual() + 1) + " " +
                T.tr("estado.de_total", visor.paginas().toString())
        }
    }

    private fun ocupar(si: Boolean) {
        ocupado = si
        progreso.visibility = if (si) View.VISIBLE else View.GONE
    }

    /** Ejecuta [tarea] en el núcleo y aplica el estado resultante. */
    private fun operar(conservarPosicion: Boolean = true, tarea: () -> String?, despues: ((String?) -> Unit)? = null) {
        if (ocupado) return
        ocupar(true)
        Trabajo.hacer({ tarea() to leerEstado() }) { r ->
            ocupar(false)
            Archivos.vaciar(cacheDir) // copias temporales (certificados, imágenes, PDF)
            r.onSuccess { (mensaje, e) ->
                aplicarEstado(e, conservarPosicion)
                if (despues != null) despues(mensaje) else if (!mensaje.isNullOrEmpty()) mostrarMensaje(mensaje)
            }
            r.onFailure { mostrarError(it) }
        }
    }

    // ------------------------------------------------------------ menú

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        acciones.clear()
        if (!listo) return true
        var id = 1
        fun item(m: Menu, titulo: String, activo: Boolean = true, accion: () -> Unit): MenuItem {
            val i = m.add(Menu.NONE, id, Menu.NONE, titulo)
            i.isEnabled = activo
            acciones[id++] = accion
            return i
        }
        val abierto = estado.abierto

        item(menu, T.tr("accion.deshacer"), estado.deshacer) { operar(tarea = { Nucleo.deshacer(); null }) }
            .apply { setIcon(R.drawable.ic_deshacer); setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM) }
        item(menu, T.tr("accion.rehacer"), estado.rehacer) { operar(tarea = { Nucleo.rehacer(); null }) }
            .apply { setIcon(R.drawable.ic_rehacer); setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM) }
        item(menu, T.tr("android.compartir"), abierto) { accionCompartir() }
            .apply { setIcon(R.drawable.ic_compartir); setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM) }

        val archivo = menu.addSubMenu(T.tr("menu.archivo"))
        item(archivo, T.tr("accion.nuevo")) { accionNuevo() }
        item(archivo, T.tr("accion.abrir")) { accionAbrir() }
        item(archivo, T.tr("accion.guardar_como"), abierto) { accionGuardarComo() }
        item(archivo, T.tr("android.compartir"), abierto) { accionCompartir() }
        item(archivo, T.tr("accion.cerrar"), abierto) { confirmarDescartar { operar(false, { Nucleo.cerrar(); null }) } }

        val ver = menu.addSubMenu(T.tr("menu.ver"))
        item(ver, T.tr("accion.ajustar_ancho"), abierto) { visor.ajustarAncho() }
        item(ver, T.tr("accion.ajustar_pagina"), abierto) { visor.ajustarPagina() }
        item(ver, T.tr("accion.ir_a_pagina"), abierto) { accionIrAPagina() }

        // Menús de los módulos del núcleo (Páginas, Documento, Firma).
        for (clave in listOf("paginas", "documento", "firma")) {
            val sub = menu.addSubMenu(T.tr("menu.$clave"))
            if (clave == "paginas") item(sub, T.tr("android.organizar"), abierto && estado.tamanos.size > 2) { accionOrganizar() }
            for (m in modulos.filter { it.menu == clave })
                item(sub, T.tr("modulo.${m.id}.nombre"), abierto) { ejecutarModulo(m) }
        }

        val idioma = menu.addSubMenu(T.tr("menu.idioma"))
        for ((codigo, nombre) in idiomas)
            item(idioma, nombre) { cambiarIdioma(codigo) }.apply { isCheckable = true; isChecked = codigo == idiomaCodigo }

        val ayuda = menu.addSubMenu(T.tr("menu.ayuda"))
        item(ayuda, tituloAcercaDe()) { acercaDe() }
        item(ayuda, T.tr("android.licencias")) { licencias() }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val accion = acciones[item.itemId] ?: return super.onOptionsItemSelected(item)
        if (!ocupado) accion()
        return true
    }

    private fun cambiarIdioma(codigo: String) {
        preferencias.edit().putString("idioma", codigo).apply()
        Trabajo.hacer({
            Nucleo.cambiarIdioma(codigo)
            T.cargarDelNucleo()
            Nucleo.idiomaActual()
        }) { r ->
            r.getOrNull()?.let { idiomaCodigo = it }
            aplicarEstado(estado, conservarPosicion = true)
        }
    }

    // ------------------------------------------------------------ archivo

    private fun accionNuevo() {
        val tamanos = mapOf(
            "a4" to (595.276 to 841.89), "carta" to (612.0 to 792.0), "oficio" to (612.0 to 1008.0),
            "a5" to (419.528 to 595.276), "a3" to (841.89 to 1190.551),
        )
        val modulo = ModuloInfo("nuevo", "archivo", 0, true, false, listOf(
            ParametroInfo("tamano", "param.tamano_nuevo", "opcion", "a4", 0, 0, "", listOf("a4", "carta", "oficio", "a5", "a3")),
            ParametroInfo("orientacion", "param.orientacion", "opcion", "vertical", 0, 0, "", listOf("vertical", "horizontal")),
        ))
        confirmarDescartar {
            DialogoParametros(this, modulo, contextoDialogo(), this) { v ->
                var (ancho, alto) = tamanos[v["tamano"]] ?: tamanos.getValue("a4")
                if (v["orientacion"] == "horizontal") ancho = alto.also { alto = ancho }
                operar(false, { Nucleo.nuevo(ancho, alto); null })
            }.mostrar()
        }
    }

    private fun accionAbrir() {
        confirmarDescartar {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
            pedir(i) { _, datos -> datos?.data?.let { abrirUri(it, "") } }
        }
    }

    private fun abrirUri(uri: Uri, contrasena: String) {
        if (ocupado) return
        ocupar(true)
        Trabajo.hacer({
            val nombre = Archivos.nombre(contentResolver, uri)
            Nucleo.abrir(Archivos.leer(contentResolver, uri), nombre, contrasena)
            leerEstado()
        }) { r ->
            ocupar(false)
            r.onSuccess { aplicarEstado(it, conservarPosicion = false) }
            r.onFailure { e ->
                if (e is ErrorNucleo && e.clave == "error.contrasena") pedirContrasena(contrasena.isNotEmpty()) { abrirUri(uri, it) }
                else mostrarError(e)
            }
        }
    }

    private fun pedirContrasena(incorrecta: Boolean, listo: (String) -> Unit) {
        val campo = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle(T.tr("dialogo.contrasena.titulo"))
            .setMessage(T.tr(if (incorrecta) "dialogo.contrasena.incorrecta" else "dialogo.contrasena.texto"))
            .setView(conMargen(campo))
            .setNegativeButton(T.tr("boton.cancelar"), null)
            .setPositiveButton(T.tr("boton.abrir")) { _, _ -> listo(campo.text.toString()) }
            .show()
    }

    private fun accionGuardarComo() {
        if (!estado.abierto) return
        val base = estado.nombre.substringBeforeLast('.', estado.nombre)
        val sugerido = if (estado.esNuevo) "$base.pdf" else base + T.tr("archivo.sufijo") + ".pdf"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf").putExtra(Intent.EXTRA_TITLE, sugerido)
        pedir(i) { _, datos ->
            val uri = datos?.data ?: return@pedir
            operar(tarea = {
                Archivos.escribir(contentResolver, uri, Nucleo.bytes())
                Nucleo.marcarGuardado(Archivos.nombre(contentResolver, uri))
                null
            }) { Toast.makeText(this, T.tr("android.guardado"), Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * Comparte el documento tal como está ahora (con los cambios aún sin guardar) con
     * otra app: correo, mensajería, Drive… Se escribe una copia en la caché y se da a la
     * app elegida permiso para leer solo esa copia (ProveedorCompartir).
     */
    private fun accionCompartir() {
        if (!estado.abierto) return
        val base = estado.nombre.substringBeforeLast('.', estado.nombre)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "documento" }
        var uri: Uri? = null
        operar(tarea = {
            val f = File(ProveedorCompartir.carpetaNueva(this), "$base.pdf")
            f.writeBytes(Nucleo.bytes())
            uri = ProveedorCompartir.uri(this, f)
            null
        }) {
            val u = uri ?: return@operar
            val enviar = Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            enviar.clipData = ClipData.newRawUri(base, u) // el permiso pasa también por el selector
            val titulo = T.tr("android.compartir").trimEnd('…', '.')
            runCatching { startActivity(Intent.createChooser(enviar, titulo)) }
                .onFailure { mostrarError(it) }
        }
    }

    private fun confirmarDescartar(seguir: () -> Unit) {
        if (!estado.modificado) {
            seguir()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(T.tr("dialogo.descartar.titulo"))
            .setMessage(T.tr("dialogo.descartar.texto"))
            .setNegativeButton(T.tr("boton.cancelar"), null)
            .setNeutralButton(T.tr("boton.guardar_como")) { _, _ -> accionGuardarComo() }
            .setPositiveButton(T.tr("boton.descartar")) { _, _ -> seguir() }
            .show()
    }

    // ------------------------------------------------------------ ver

    private fun accionIrAPagina() {
        val campo = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((visor.paginaActual() + 1).toString())
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(T.tr("accion.ir_a_pagina"))
            .setView(conMargen(campo))
            .setNegativeButton(T.tr("boton.cancelar"), null)
            .setPositiveButton(T.tr("boton.aceptar")) { _, _ ->
                campo.text.toString().toIntOrNull()?.let { visor.irAPagina(it - 1) }
            }
            .show()
    }

    private fun accionOrganizar() {
        OrganizarPaginas(this, estado.tamanos, visor.fuente!!, visor.generacion) { orden ->
            operar(tarea = { Nucleo.reordenar(orden); null })
        }.mostrar()
    }

    // ------------------------------------------------------------ módulos

    private fun contextoDialogo() = ContextoDialogo(
        paginaActual = visor.paginaActual(),
        totalPaginas = visor.paginas(),
        carpetaSalida = File(cacheDir, "salida"),
        nombre = estado.nombre.substringBeforeLast('.', estado.nombre),
    )

    /** Mismo flujo que en escritorio: clic antes del diálogo, diálogo, o clic después. */
    private fun ejecutarModulo(m: ModuloInfo) {
        if (!estado.abierto) return
        val base = mutableMapOf("pagina_actual" to (visor.paginaActual() + 1).toString())
        if (m.requierePosicion) {
            pedirPosicion { pos ->
                base.putAll(pos)
                dialogo(m, base)
            }
        } else {
            dialogo(m, base)
        }
    }

    private fun dialogo(m: ModuloInfo, base: Map<String, String>) {
        val seguir: (MutableMap<String, String>) -> Unit = { valores ->
            valores.putAll(base)
            if (m.requierePosicion) {
                correr(m, valores)
            } else {
                Trabajo.hacer({ Nucleo.posicionTrasDialogo(m.id, valores) }) { r ->
                    if (r.getOrDefault(false)) pedirPosicion { pos -> valores.putAll(pos); correr(m, valores) }
                    else correr(m, valores)
                }
            }
        }
        if (m.parametros.isEmpty()) seguir(mutableMapOf()) else DialogoParametros(this, m, contextoDialogo(), this, seguir).mostrar()
    }

    private fun correr(m: ModuloInfo, valores: Map<String, String>) {
        val salida = if (m.parametros.any { it.tipo == "carpeta" }) Archivos.salida(cacheDir) else null
        operar(tarea = { Nucleo.ejecutar(m.id, valores) }) { mensaje ->
            val creados = salida?.listFiles()?.size ?: 0
            when {
                creados > 0 -> guardarSalida(salida!!)
                !mensaje.isNullOrEmpty() -> mostrarMensaje(mensaje)
            }
        }
    }

    /** «Separar»: el usuario elige la carpeta y se copian allí los archivos creados. */
    private fun guardarSalida(salida: File) {
        Toast.makeText(this, T.tr("android.carpeta_destino"), Toast.LENGTH_LONG).show()
        pedir(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) { _, datos ->
            val arbol = datos?.data
            if (arbol == null) {
                salida.deleteRecursively()
                return@pedir
            }
            ocupar(true)
            Trabajo.hacer({ Archivos.copiarACarpeta(contentResolver, arbol, salida) }) { r ->
                ocupar(false)
                r.onSuccess { mostrarMensaje(T.tr("android.archivos_guardados", it.toString())) }
                r.onFailure { mostrarError(it) }
            }
        }
    }

    private fun pedirPosicion(listo: (Map<String, String>) -> Unit) {
        colocarPendiente = listo
        visor.modoColocar = true
        actualizarBarraEstado()
    }

    private fun cancelarColocar() {
        colocarPendiente = null
        visor.modoColocar = false
        actualizarBarraEstado()
    }

    private fun colocado(pagina: Int, x: Int, y: Int, ancho: Int, alto: Int) {
        val listo = colocarPendiente ?: return
        cancelarColocar()
        Trabajo.hacer({ Nucleo.aPagina(pagina, ancho, alto, x, y) }) { r ->
            val p = r.getOrNull() ?: return@hacer
            listo(mapOf(
                "pagina" to pagina.toString(),
                "x" to String.format(Locale.ROOT, "%.3f", p[0]),
                "y" to String.format(Locale.ROOT, "%.3f", p[1]),
            ))
        }
    }

    // ------------------------------------------------------------ selectores

    private fun pedir(intencion: Intent, listo: (Int, Intent?) -> Unit) {
        val codigo = siguientePeticion++
        resultados[codigo] = listo
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intencion, codigo)
        } catch (e: Exception) {
            resultados.remove(codigo)
            mostrarError(e)
        }
    }

    @Deprecated("Activity del sistema sin AndroidX: se usa la API clásica")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(codigo, resultado, datos)
        val listo = resultados.remove(codigo) ?: return
        listo(resultado, if (resultado == RESULT_OK) datos else null)
    }

    override fun elegirArchivos(tipos: Array<String>, multiple: Boolean, listo: (List<File>) -> Unit) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        if (tipos.size == 1) i.type = tipos[0] else {
            i.type = "*/*"
            i.putExtra(Intent.EXTRA_MIME_TYPES, tipos)
        }
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        pedir(i) { _, datos ->
            val uris = ArrayList<Uri>()
            datos?.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
            if (uris.isEmpty()) datos?.data?.let { uris.add(it) }
            try {
                listo(uris.map { Archivos.aCache(cacheDir, contentResolver, it) })
            } catch (e: Exception) {
                mostrarError(e)
            }
        }
    }

    override fun elegirCertificadoInstalado(listo: (String?) -> Unit) {
        KeyChain.choosePrivateKeyAlias(this, { alias -> Trabajo.enInterfaz { listo(alias) } }, null, null, null, null)
    }

    // ------------------------------------------------------------ mensajes

    private fun mostrarMensaje(texto: String) {
        AlertDialog.Builder(this).setMessage(texto).setPositiveButton(T.tr("boton.cerrar"), null).show()
    }

    private fun mostrarError(e: Throwable) {
        val texto = if (e is ErrorNucleo) e.message.orEmpty() else T.tr("error.qpdf") + "\n\n" + e.toString()
        AlertDialog.Builder(this)
            .setTitle(T.tr("error.titulo"))
            .setMessage(texto)
            .setPositiveButton(T.tr("boton.cerrar"), null)
            .show()
    }

    private fun conMargen(v: View): View = LinearLayout(this).apply {
        val m = (20 * resources.displayMetrics.density).toInt()
        setPadding(m, m / 2, m, 0)
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun acercaDe() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        val texto = nombreApp + "\n" + T.tr("acerca.version", version) + "\n\n" +
            T.tr("acerca.descripcion") + "\n\n" + T.tr("acerca.nombre") + "\n\n" +
            "© 2026 Carlos N. Perez-Montenegro (DonatellaTech)\n" + T.tr("acerca.licencia")
        AlertDialog.Builder(this)
            .setTitle(tituloAcercaDe())
            .setIcon(R.drawable.icono_tortuga)
            .setMessage(texto)
            .setPositiveButton(T.tr("boton.cerrar"), null)
            .setNeutralButton("donatellatech.com") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://donatellatech.com/IbanascaPDFApp/"))) }
            }
            .setNegativeButton(T.tr("android.licencias")) { _, _ -> licencias() }
            .show()
    }

    /** Licencias de los componentes (las copia tools/obtener_dependencias.sh en assets/licencias). */
    private fun licencias() {
        val texto = StringBuilder()
        assets.list("licencias")?.sorted()?.forEach { nombre ->
            texto.append("==== ").append(nombre.removeSuffix(".txt")).append(" ====\n\n")
            assets.open("licencias/$nombre").bufferedReader().use { texto.append(it.readText()) }
            texto.append("\n\n")
        }
        val vista = TextView(this).apply {
            text = texto.toString()
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            val m = (16 * resources.displayMetrics.density).toInt()
            setPadding(m, m / 2, m, m / 2)
        }
        AlertDialog.Builder(this)
            .setTitle(T.tr("android.licencias"))
            .setView(ScrollView(this).apply { addView(vista) })
            .setPositiveButton(T.tr("boton.cerrar"), null)
            .show()
    }
}
