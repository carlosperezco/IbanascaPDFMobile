# Ibanasca PDF Mobile

Editor de PDF ligero, libre y de código abierto para Android, parte de
[DonatellaTech](https://donatellatech.com). Web: <https://donatellatech.com/IbanascaPDFApp/>.
Es la versión Android de [Ibanasca PDF Lab](https://github.com/carlosperezco/IbanascaPDFLab),
el editor de escritorio, y comparte con él el núcleo C++: los dos hacen lo mismo con el mismo código.

[Read in English](README.md)

## Funciones

- **Ver:** desplazamiento continuo, zoom con dos dedos, doble toque, ajustar al ancho o
  a la página, ir a una página, PDF con contraseña, «Abrir con» desde otras apps.
- **Páginas:** girar, mover, eliminar, duplicar, insertar páginas en blanco y
  organizarlas arrastrando las miniaturas.
- **Archivo:** nuevo PDF con una página en blanco (A4, Carta, Oficio, A5, A3); compartir
  el documento con otras apps (correo, mensajería, almacenamiento en la nube).
- **Documento:** fusionar PDF; separar por página o por rangos, o extraer páginas a otro PDF.
- **Escribir:** añadir texto e imágenes PNG donde toque.
- **Firmar:** dibuje su firma con el dedo o use una imagen PNG; firma digital PAdES-B-B
  con un archivo `.p12`/`.pfx` o con un certificado instalado en Android.
- Deshacer y rehacer. El archivo original nunca se sobrescribe («Guardar como»).
- Sin permisos, sin cuenta, sin internet. Español, inglés e italiano.

## Instalar

Descargue el APK desde [Releases](https://github.com/carlosperezco/IbanascaPDFMobile/releases)
y ábralo en el teléfono. Android le pedirá permitir la instalación de apps desde ese
origen (el navegador o el gestor de archivos). Requiere Android 8.0 o posterior.

## Compilar

Requisitos: Android Studio con el SDK de Android, el **NDK** y **CMake** (SDK Manager ›
SDK Tools), `git`, `curl` y `bash` (en Windows: MSYS2 o Git Bash).

```bash
git clone --recurse-submodules https://github.com/carlosperezco/IbanascaPDFMobile.git
cd IbanascaPDFMobile
./tools/obtener_dependencias.sh     # PDFium, QPDF y libjpeg-turbo para Android (una vez)
```

Después abra la carpeta en Android Studio y ejecute la configuración `app`. Si clonó sin
`--recurse-submodules`, ejecute antes `git submodule update --init`.

`tools/obtener_dependencias.sh` descarga PDFium precompilado y compila QPDF y
libjpeg-turbo con el NDK para `arm64-v8a` y `x86_64` (emulador). Variables:
`ABIS`, `API`, `PDFIUM_VERSION`, `QPDF_VERSION`, `JPEG_VERSION`, `ANDROID_NDK_HOME`.

Para actualizar el núcleo: `git submodule update --remote nucleo` y haga commit de la
nueva referencia.

## Versiones y releases

La app Android tiene su propia versión (`versionName` en `app/build.gradle.kts`),
independiente de la de escritorio. El CHANGELOG indica qué versión del núcleo usa cada
release. En cada release:

1. Sume uno a `versionCode` y ajuste `versionName`; actualice el CHANGELOG.
2. Android Studio › Build › Generate Signed App Bundle or APK › APK › `release`,
   con su propia clave (`.jks`). Guarde la clave y su contraseña fuera del repositorio y
   haga una copia de seguridad: las actualizaciones deben firmarse con la misma clave.
3. Instale el APK firmado en un teléfono y pruébelo antes de publicarlo.
4. Haga commit, etiqueta (`vX.Y.Z`) y adjunte el APK al release de GitHub con el nombre
   `IbanascaPDFMobile-X.Y.Z.apk` (el botón de descarga de la web espera ese nombre).

## Arquitectura

```
nucleo/                  submódulo git: Ibanasca PDF Lab (escritorio); solo se usan core/ y lang/
app/src/main/cpp/        CMakeLists.txt e ibz_jni.cpp (puente Kotlin ↔ C++)
app/src/main/java/…      interfaz en Kotlin (solo el framework de Android, sin AndroidX)
  MainActivity.kt        menús creados a partir de los módulos, selectores de archivos, flujo de cada operación
  VisorPdf.kt            visor de páginas (gestos, dibujo en un hilo de trabajo)
  DialogoParametros.kt   diálogo genérico de parámetros (la misma idea que el de GTK)
  LienzoFirma.kt         firma dibujada con el dedo
  FirmanteAndroid.kt     clave privada con KeyStore (.p12) o KeyChain (certificado instalado)
tools/                   obtener_dependencias.sh
```

Todas las llamadas al núcleo pasan por un único hilo de trabajo. Un módulo nuevo del
núcleo (`nucleo/core/src/modules/`) aparece solo en los menús y el diálogo de Android.

La firma digital la arma el núcleo (CMS, SHA-256, atributos PAdES); Android solo firma
los bytes con la clave privada mediante las clases del sistema. Algunas versiones
antiguas de Android no leen archivos `.p12` creados con las opciones recientes de
OpenSSL (AES); en ese caso, instale el certificado en Android (Ajustes › Seguridad ›
Instalar certificado) y elija «Certificado instalado».

## Licencia

MIT — ver [LICENSE](LICENSE). Componentes de terceros: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
