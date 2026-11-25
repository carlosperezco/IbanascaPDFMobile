/**
 * @file ibz_jni.cpp
 * @brief Puente entre Kotlin y el núcleo C++ de Ibanasca PDF Lab (submódulo nucleo/), para Ibanasca PDF Mobile.
 *
 * Todas las funciones se registran en JNI_OnLoad con RegisterNatives (los nombres no
 * dependen del paquete). La app llama siempre desde un único hilo de trabajo; aun
 * así, un mutex protege el documento.
 *
 * Las cadenas cruzan como UTF-16 de Java ↔ UTF-8 del núcleo (no se usa el «UTF-8
 * modificado» de JNI, que estropea los caracteres fuera del plano básico).
 */
#include <jni.h>

#include <android/bitmap.h>

#include <ibanascapdfcore/documento.h>
#include <ibanascapdfcore/error.h>
#include <ibanascapdfcore/firma.h>
#include <ibanascapdfcore/idioma.h>
#include <ibanascapdfcore/modulo.h>
#include <ibanascapdfcore/operaciones.h>
#include <ibanascapdfcore/rutas.h>

#include <cstdint>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr const char* kClaseNucleo = "com/donatellatech/ibanascapdf/Nucleo";
constexpr const char* kClaseError = "com/donatellatech/ibanascapdf/ErrorNucleo";
constexpr const char* kClaseFirmante = "com/donatellatech/ibanascapdf/FirmanteAndroid";

JavaVM* gVm = nullptr;
jclass gError = nullptr;    // ErrorNucleo(String clave, String mensaje)
jmethodID gErrorCtor = nullptr;
jmethodID gErrorClave = nullptr;
jclass gFirmante = nullptr; // FirmanteAndroid (métodos estáticos)
jmethodID gFirmanteCertificados = nullptr;
jmethodID gFirmanteFirmar = nullptr;

std::mutex gMutex;

ibz::Documento& documento() {
    static ibz::Documento d;
    return d;
}

// ------------------------------------------------------------ cadenas

std::string aUtf8(const jchar* u, jsize n) {
    std::string r;
    r.reserve(static_cast<std::size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        std::uint32_t c = u[i];
        if (c >= 0xD800 && c < 0xDC00 && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] < 0xE000) {
            c = 0x10000 + ((c - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            ++i;
        }
        if (c < 0x80) {
            r += static_cast<char>(c);
        } else if (c < 0x800) {
            r += static_cast<char>(0xC0 | (c >> 6));
            r += static_cast<char>(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            r += static_cast<char>(0xE0 | (c >> 12));
            r += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            r += static_cast<char>(0x80 | (c & 0x3F));
        } else {
            r += static_cast<char>(0xF0 | (c >> 18));
            r += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
            r += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            r += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    return r;
}

std::string cadena(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jsize n = env->GetStringLength(s);
    const jchar* u = env->GetStringChars(s, nullptr);
    std::string r = aUtf8(u, n);
    env->ReleaseStringChars(s, u);
    return r;
}

jstring jcadena(JNIEnv* env, const std::string& s) {
    std::vector<jchar> u;
    u.reserve(s.size());
    for (std::size_t i = 0; i < s.size();) {
        const auto c0 = static_cast<unsigned char>(s[i]);
        std::uint32_t cp = 0xFFFD;
        std::size_t n = 1;
        if (c0 < 0x80) {
            cp = c0;
        } else if ((c0 >> 5) == 0x6 && i + 1 < s.size()) {
            cp = ((c0 & 0x1F) << 6) | (s[i + 1] & 0x3F);
            n = 2;
        } else if ((c0 >> 4) == 0xE && i + 2 < s.size()) {
            cp = ((c0 & 0x0F) << 12) | ((s[i + 1] & 0x3F) << 6) | (s[i + 2] & 0x3F);
            n = 3;
        } else if ((c0 >> 3) == 0x1E && i + 3 < s.size()) {
            cp = ((c0 & 0x07) << 18) | ((s[i + 1] & 0x3F) << 12) | ((s[i + 2] & 0x3F) << 6) | (s[i + 3] & 0x3F);
            n = 4;
        }
        i += n;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            u.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            u.push_back(static_cast<jchar>(cp));
        }
    }
    return env->NewString(u.data(), static_cast<jsize>(u.size()));
}

std::vector<unsigned char> bytesJava(JNIEnv* env, jbyteArray a) {
    std::vector<unsigned char> r;
    if (!a) return r;
    r.resize(static_cast<std::size_t>(env->GetArrayLength(a)));
    if (!r.empty()) env->GetByteArrayRegion(a, 0, static_cast<jsize>(r.size()), reinterpret_cast<jbyte*>(r.data()));
    return r;
}

jbyteArray bytesNuevos(JNIEnv* env, const unsigned char* d, std::size_t n) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(n));
    if (a && n) env->SetByteArrayRegion(a, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(d));
    return a;
}

ibz::Valores valores(JNIEnv* env, jobjectArray claves, jobjectArray vals) {
    ibz::Valores v;
    const jsize n = claves ? env->GetArrayLength(claves) : 0;
    for (jsize i = 0; i < n; ++i) {
        auto k = static_cast<jstring>(env->GetObjectArrayElement(claves, i));
        auto x = static_cast<jstring>(env->GetObjectArrayElement(vals, i));
        v[cadena(env, k)] = cadena(env, x);
        env->DeleteLocalRef(k);
        env->DeleteLocalRef(x);
    }
    return v;
}

// ------------------------------------------------------------ errores

/// Lanza ErrorNucleo con la clave y el mensaje ya traducido.
void lanzar(JNIEnv* env, const std::string& clave, const std::string& detalle) {
    if (env->ExceptionCheck()) return;
    std::string mensaje = ibz::tr(clave.c_str());
    if (!detalle.empty()) mensaje += "\n\n" + detalle;
    jstring k = jcadena(env, clave);
    jstring m = jcadena(env, mensaje);
    auto e = static_cast<jthrowable>(env->NewObject(gError, gErrorCtor, k, m));
    if (e) env->Throw(e);
}

/// Ejecuta @p f traduciendo las excepciones de C++ a ErrorNucleo.
template <typename F, typename R>
R protegido(JNIEnv* env, R siFalla, F&& f) {
    try {
        std::lock_guard<std::mutex> bloqueo(gMutex);
        return f();
    } catch (const ibz::Error& e) {
        lanzar(env, e.clave(), e.detalle());
    } catch (const std::exception& e) {
        lanzar(env, "error.qpdf", e.what());
    }
    return siFalla;
}

// ------------------------------------------------------------ firmante

JNIEnv* entorno() {
    JNIEnv* env = nullptr;
    if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED)
#ifdef __ANDROID__
        gVm->AttachCurrentThread(&env, nullptr);
#else
        gVm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr); // jni.h del JDK (solo para comprobar)
#endif
    return env;
}

/// Si Java lanzó una excepción, la convierte en ibz::Error.
void comprobarJava(JNIEnv* env, const char* claveDefecto) {
    jthrowable ex = env->ExceptionOccurred();
    if (!ex) return;
    env->ExceptionClear();
    std::string clave = claveDefecto;
    std::string detalle;
    if (env->IsInstanceOf(ex, gError)) {
        auto k = static_cast<jstring>(env->CallObjectMethod(ex, gErrorClave));
        if (!env->ExceptionCheck() && k) clave = cadena(env, k);
        env->ExceptionClear();
    } else {
        jclass c = env->FindClass("java/lang/Throwable");
        jmethodID ts = env->GetMethodID(c, "toString", "()Ljava/lang/String;");
        auto t = static_cast<jstring>(env->CallObjectMethod(ex, ts));
        if (!env->ExceptionCheck() && t) detalle = cadena(env, t);
        env->ExceptionClear();
    }
    env->DeleteLocalRef(ex);
    throw ibz::Error(clave, detalle);
}

/**
 * @brief Firmante de Android: la clave la maneja Java (KeyStore PKCS#12 o KeyChain),
 *        el núcleo arma el CMS.
 */
class FirmanteJava final : public ibz::Firmante {
public:
    FirmanteJava(std::string referencia, std::string contrasena)
        : referencia_(std::move(referencia)), contrasena_(std::move(contrasena)) {
        JNIEnv* env = entorno();
        jstring r = jcadena(env, referencia_);
        jstring c = jcadena(env, contrasena_);
        auto lista = static_cast<jobjectArray>(env->CallStaticObjectMethod(gFirmante, gFirmanteCertificados, r, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
        comprobarJava(env, "error.certificado");
        const jsize n = lista ? env->GetArrayLength(lista) : 0;
        for (jsize i = 0; i < n; ++i) {
            auto der = static_cast<jbyteArray>(env->GetObjectArrayElement(lista, i));
            certs_.push_back(bytesJava(env, der));
            env->DeleteLocalRef(der);
        }
        if (lista) env->DeleteLocalRef(lista);
        if (certs_.empty()) throw ibz::Error("error.certificado");
    }

    const std::vector<std::vector<unsigned char>>& certificados() const override { return certs_; }

    std::vector<unsigned char> firmar(const unsigned char* datos, std::size_t n) const override {
        JNIEnv* env = entorno();
        jstring r = jcadena(env, referencia_);
        jstring c = jcadena(env, contrasena_);
        jbyteArray d = bytesNuevos(env, datos, n);
        auto firma = static_cast<jbyteArray>(env->CallStaticObjectMethod(gFirmante, gFirmanteFirmar, r, c, d));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
        env->DeleteLocalRef(d);
        comprobarJava(env, "error.firma");
        std::vector<unsigned char> salida = bytesJava(env, firma);
        if (firma) env->DeleteLocalRef(firma);
        return salida;
    }

private:
    std::string referencia_;
    std::string contrasena_;
    std::vector<std::vector<unsigned char>> certs_;
};

// ------------------------------------------------------------ JSON de los módulos

void json(std::string& o, const std::string& s) {
    o += '"';
    for (const char ch : s) {
        const auto c = static_cast<unsigned char>(ch);
        switch (c) {
        case '"': o += "\\\""; break;
        case '\\': o += "\\\\"; break;
        case '\n': o += "\\n"; break;
        case '\r': o += "\\r"; break;
        case '\t': o += "\\t"; break;
        default:
            if (c < 0x20) {
                static const char kHex[] = "0123456789abcdef";
                o += "\\u00";
                o += kHex[c >> 4];
                o += kHex[c & 0xF];
            } else {
                o += ch;
            }
        }
    }
    o += '"';
}

const char* nombreTipo(ibz::TipoParametro t) {
    using T = ibz::TipoParametro;
    switch (t) {
    case T::Entero: return "entero";
    case T::Texto: return "texto";
    case T::Opcion: return "opcion";
    case T::Paginas: return "paginas";
    case T::Contrasena: return "contrasena";
    case T::ArchivosPdf: return "archivos_pdf";
    case T::Carpeta: return "carpeta";
    case T::TextoLargo: return "texto_largo";
    case T::Color: return "color";
    case T::ArchivoImagen: return "archivo_imagen";
    case T::ArchivoCertificado: return "archivo_certificado";
    case T::Dibujo: return "dibujo";
    }
    return "texto";
}

std::string modulosJson() {
    std::string o = "[";
    bool primero = true;
    for (const auto& m : ibz::Registro::instancia().modulos()) {
        if (!primero) o += ',';
        primero = false;
        o += "{\"id\":";
        json(o, m->id());
        o += ",\"menu\":";
        json(o, m->menu());
        o += ",\"orden\":" + std::to_string(m->orden());
        o += ",\"modifica\":";
        o += m->modificaDocumento() ? "true" : "false";
        o += ",\"posicion\":";
        o += m->requierePosicion() ? "true" : "false";
        o += ",\"parametros\":[";
        bool p1 = true;
        for (const ibz::Parametro& p : m->parametros()) {
            if (!p1) o += ',';
            p1 = false;
            o += "{\"id\":";
            json(o, p.id);
            o += ",\"clave\":";
            json(o, p.clave);
            o += ",\"tipo\":";
            json(o, nombreTipo(p.tipo));
            o += ",\"defecto\":";
            json(o, p.defecto);
            o += ",\"minimo\":" + std::to_string(p.minimo) + ",\"maximo\":" + std::to_string(p.maximo);
            o += ",\"visibleSi\":";
            json(o, p.visibleSi);
            o += ",\"opciones\":[";
            for (std::size_t i = 0; i < p.opciones.size(); ++i) {
                if (i) o += ',';
                json(o, p.opciones[i]);
            }
            o += "]}";
        }
        o += "]}";
    }
    o += "]";
    return o;
}

// ------------------------------------------------------------ funciones nativas

void iniciar(JNIEnv* env, jobject, jstring datos, jstring usuario, jstring idioma) {
    protegido(env, 0, [&] {
        static bool hecho = false;
        if (!hecho) {
            ibz::establecerCarpetas(ibz::desdeUtf8(cadena(env, datos)), ibz::desdeUtf8(cadena(env, usuario)));
            ibz::iniciarPdf();
            ibz::establecerFabricaFirmante([](const std::string& ref, const std::string& clave) {
                return std::make_unique<FirmanteJava>(ref, clave);
            });
            hecho = true;
        }
        if (!ibz::Idioma::global().cargar(cadena(env, idioma))) ibz::Idioma::global().cargar("en");
        return 0;
    });
}

jboolean cambiarIdioma(JNIEnv* env, jobject, jstring codigo) {
    return protegido(env, static_cast<jboolean>(JNI_FALSE),
                     [&] { return static_cast<jboolean>(ibz::Idioma::global().cargar(cadena(env, codigo))); });
}

jstring idiomaActual(JNIEnv* env, jobject) {
    return jcadena(env, ibz::Idioma::global().codigo());
}

/// Pares (clave, texto) seguidos: [k0, t0, k1, t1, …].
jobjectArray traducciones(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> bloqueo(gMutex);
    const auto& t = ibz::Idioma::global().textos();
    jobjectArray a = env->NewObjectArray(static_cast<jsize>(t.size() * 2), env->FindClass("java/lang/String"), nullptr);
    jsize i = 0;
    for (const auto& [k, v] : t) {
        jstring jk = jcadena(env, k);
        jstring jv = jcadena(env, v);
        env->SetObjectArrayElement(a, i++, jk);
        env->SetObjectArrayElement(a, i++, jv);
        env->DeleteLocalRef(jk);
        env->DeleteLocalRef(jv);
    }
    return a;
}

/// Pares (código, nombre propio) de los idiomas disponibles.
jobjectArray idiomas(JNIEnv* env, jobject) {
    const auto lista = ibz::Idioma::disponibles();
    jobjectArray a = env->NewObjectArray(static_cast<jsize>(lista.size() * 2), env->FindClass("java/lang/String"), nullptr);
    jsize i = 0;
    for (const auto& [codigo, nombre] : lista) {
        jstring c = jcadena(env, codigo);
        jstring n = jcadena(env, nombre);
        env->SetObjectArrayElement(a, i++, c);
        env->SetObjectArrayElement(a, i++, n);
        env->DeleteLocalRef(c);
        env->DeleteLocalRef(n);
    }
    return a;
}

void abrir(JNIEnv* env, jobject, jbyteArray datos, jstring nombre, jstring contrasena) {
    protegido(env, 0, [&] {
        documento().abrirMemoria(bytesJava(env, datos), cadena(env, nombre), cadena(env, contrasena));
        return 0;
    });
}

void nuevo(JNIEnv* env, jobject, jdouble ancho, jdouble alto) {
    protegido(env, 0, [&] {
        documento().nuevo(ibz::crearPdfVacio(ancho, alto), ibz::tr("archivo.sin_titulo") + ".pdf");
        return 0;
    });
}

void cerrar(JNIEnv* env, jobject) {
    protegido(env, 0, [&] {
        documento().cerrar();
        return 0;
    });
}

jboolean abierto(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> bloqueo(gMutex);
    return documento().abierto() ? JNI_TRUE : JNI_FALSE;
}

jint paginas(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> bloqueo(gMutex);
    return documento().paginas();
}

/// Tamaños de todas las páginas tal como se ven: [ancho0, alto0, ancho1, alto1, …] en puntos.
jfloatArray tamanos(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> bloqueo(gMutex);
    const int n = documento().paginas();
    std::vector<jfloat> v(static_cast<std::size_t>(n) * 2);
    for (int i = 0; i < n; ++i) {
        v[static_cast<std::size_t>(2 * i)] = static_cast<jfloat>(documento().tamano(i).ancho);
        v[static_cast<std::size_t>(2 * i + 1)] = static_cast<jfloat>(documento().tamano(i).alto);
    }
    jfloatArray a = env->NewFloatArray(static_cast<jsize>(v.size()));
    if (a && !v.empty()) env->SetFloatArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return a;
}

/**
 * @brief Dibuja en @p bitmap (ARGB_8888) la zona de la página que empieza en
 *        (inicioX, inicioY) de una página dibujada a anchoPagina × altoPagina píxeles.
 */
jboolean dibujar(JNIEnv* env, jobject, jint pagina, jobject bitmap, jint inicioX, jint inicioY, jint anchoPagina,
                 jint altoPagina) {
    std::lock_guard<std::mutex> bloqueo(gMutex);
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return JNI_FALSE;
    void* pixeles = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixeles) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    auto* p = static_cast<unsigned char*>(pixeles);
    const bool ok = documento().renderizar(pagina, inicioX, inicioY, anchoPagina, altoPagina,
                                           static_cast<int>(info.width), static_cast<int>(info.height), p,
                                           static_cast<int>(info.stride));
    if (ok) {
        // PDFium escribe BGRx; Android espera RGBA (opaco).
        for (std::uint32_t y = 0; y < info.height; ++y) {
            unsigned char* f = p + static_cast<std::size_t>(y) * info.stride;
            for (std::uint32_t x = 0; x < info.width; ++x, f += 4) {
                const unsigned char b = f[0];
                f[0] = f[2];
                f[2] = b;
                f[3] = 0xFF;
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/// Punto de la pantalla (píxeles dentro de la página dibujada) → puntos PDF; null si falla.
jdoubleArray aPagina(JNIEnv* env, jobject, jint pagina, jint anchoPx, jint altoPx, jint x, jint y) {
    std::lock_guard<std::mutex> bloqueo(gMutex);
    double px = 0;
    double py = 0;
    if (!documento().dispositivoAPagina(pagina, anchoPx, altoPx, x, y, px, py)) return nullptr;
    jdoubleArray a = env->NewDoubleArray(2);
    const jdouble v[2] = {px, py};
    env->SetDoubleArrayRegion(a, 0, 2, v);
    return a;
}

jbyteArray bytes(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> bloqueo(gMutex);
    const auto& b = documento().bytes();
    return bytesNuevos(env, b.data(), b.size());
}

void marcarGuardado(JNIEnv* env, jobject, jstring nombre) {
    std::lock_guard<std::mutex> bloqueo(gMutex);
    documento().marcarGuardado(cadena(env, nombre));
}

jstring nombre(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> bloqueo(gMutex);
    return jcadena(env, ibz::utf8(documento().ruta().filename()));
}

/// Estado: [modificado, puedeDeshacer, puedeRehacer, esNuevo].
jbooleanArray estado(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> bloqueo(gMutex);
    const ibz::Documento& d = documento();
    const jboolean v[4] = {d.modificado(), d.puedeDeshacer(), d.puedeRehacer(), d.esNuevo()};
    jbooleanArray a = env->NewBooleanArray(4);
    env->SetBooleanArrayRegion(a, 0, 4, v);
    return a;
}

void deshacer(JNIEnv* env, jobject) {
    protegido(env, 0, [&] {
        documento().deshacer();
        return 0;
    });
}

void rehacer(JNIEnv* env, jobject) {
    protegido(env, 0, [&] {
        documento().rehacer();
        return 0;
    });
}

jstring modulos(JNIEnv* env, jobject) {
    return jcadena(env, modulosJson());
}

jboolean posicionTrasDialogo(JNIEnv* env, jobject, jstring id, jobjectArray claves, jobjectArray vals) {
    const ibz::Modulo* m = ibz::Registro::instancia().buscar(cadena(env, id));
    if (!m) return JNI_FALSE;
    return m->posicionTrasDialogo(valores(env, claves, vals)) ? JNI_TRUE : JNI_FALSE;
}

jstring ejecutar(JNIEnv* env, jobject, jstring id, jobjectArray claves, jobjectArray vals) {
    const std::string nombreModulo = cadena(env, id);
    const ibz::Valores v = valores(env, claves, vals);
    const std::string mensaje = protegido(env, std::string(), [&] {
        const ibz::Modulo* m = ibz::Registro::instancia().buscar(nombreModulo);
        if (!m) throw ibz::Error("error.qpdf", nombreModulo);
        if (!documento().abierto()) throw ibz::Error("error.sin_documento");
        return m->ejecutar(documento(), v);
    });
    return env->ExceptionCheck() ? nullptr : jcadena(env, mensaje);
}

void reordenar(JNIEnv* env, jobject, jintArray orden) {
    std::vector<int> o(static_cast<std::size_t>(env->GetArrayLength(orden)));
    if (!o.empty()) env->GetIntArrayRegion(orden, 0, static_cast<jsize>(o.size()), reinterpret_cast<jint*>(o.data()));
    protegido(env, 0, [&] {
        ibz::reordenarPaginas(documento(), o);
        return 0;
    });
}

} // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gVm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    auto global = [env](const char* nombre) {
        jclass local = env->FindClass(nombre);
        if (!local) return static_cast<jclass>(nullptr);
        auto g = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        return g;
    };
    gError = global(kClaseError);
    gFirmante = global(kClaseFirmante);
    jclass nucleo = env->FindClass(kClaseNucleo);
    if (!gError || !gFirmante || !nucleo) return JNI_ERR;
    gErrorCtor = env->GetMethodID(gError, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
    gErrorClave = env->GetMethodID(gError, "getClave", "()Ljava/lang/String;");
    gFirmanteCertificados =
        env->GetStaticMethodID(gFirmante, "certificados", "(Ljava/lang/String;Ljava/lang/String;)[[B");
    gFirmanteFirmar = env->GetStaticMethodID(gFirmante, "firmar", "(Ljava/lang/String;Ljava/lang/String;[B)[B");
    if (!gErrorCtor || !gErrorClave || !gFirmanteCertificados || !gFirmanteFirmar) return JNI_ERR;

    const JNINativeMethod metodos[] = {
        {"iniciar", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", reinterpret_cast<void*>(iniciar)},
        {"cambiarIdioma", "(Ljava/lang/String;)Z", reinterpret_cast<void*>(cambiarIdioma)},
        {"idiomaActual", "()Ljava/lang/String;", reinterpret_cast<void*>(idiomaActual)},
        {"traducciones", "()[Ljava/lang/String;", reinterpret_cast<void*>(traducciones)},
        {"idiomas", "()[Ljava/lang/String;", reinterpret_cast<void*>(idiomas)},
        {"abrir", "([BLjava/lang/String;Ljava/lang/String;)V", reinterpret_cast<void*>(abrir)},
        {"nuevo", "(DD)V", reinterpret_cast<void*>(nuevo)},
        {"cerrar", "()V", reinterpret_cast<void*>(cerrar)},
        {"abierto", "()Z", reinterpret_cast<void*>(abierto)},
        {"paginas", "()I", reinterpret_cast<void*>(paginas)},
        {"tamanos", "()[F", reinterpret_cast<void*>(tamanos)},
        {"dibujar", "(ILandroid/graphics/Bitmap;IIII)Z", reinterpret_cast<void*>(dibujar)},
        {"aPagina", "(IIIII)[D", reinterpret_cast<void*>(aPagina)},
        {"bytes", "()[B", reinterpret_cast<void*>(bytes)},
        {"marcarGuardado", "(Ljava/lang/String;)V", reinterpret_cast<void*>(marcarGuardado)},
        {"nombre", "()Ljava/lang/String;", reinterpret_cast<void*>(nombre)},
        {"estado", "()[Z", reinterpret_cast<void*>(estado)},
        {"deshacer", "()V", reinterpret_cast<void*>(deshacer)},
        {"rehacer", "()V", reinterpret_cast<void*>(rehacer)},
        {"modulos", "()Ljava/lang/String;", reinterpret_cast<void*>(modulos)},
        {"posicionTrasDialogo", "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)Z",
         reinterpret_cast<void*>(posicionTrasDialogo)},
        {"ejecutar", "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;",
         reinterpret_cast<void*>(ejecutar)},
        {"reordenar", "([I)V", reinterpret_cast<void*>(reordenar)},
    };
    if (env->RegisterNatives(nucleo, metodos, static_cast<jint>(sizeof(metodos) / sizeof(metodos[0]))) != JNI_OK)
        return JNI_ERR;
    env->DeleteLocalRef(nucleo);
    return JNI_VERSION_1_6;
}
