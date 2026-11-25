# Third-party notices

Ibanasca PDF Mobile is released under the MIT license (see `LICENSE`).
It includes or links the following components. All of them use permissive licenses
compatible with MIT; none of them is GPL or LGPL.

| Component | Use | License | How it is included |
|---|---|---|---|
| Ibanasca PDF Lab core (`nucleo/`) | PDF logic shared with the desktop | MIT | git submodule, compiled into `libibanascapdf.so` |
| [PDFium](https://pdfium.googlesource.com/pdfium/) (prebuilt by [pdfium-binaries](https://github.com/bblanchon/pdfium-binaries)) | rendering pages | BSD-3-Clause and Apache-2.0 | `libpdfium.so` in the APK |
| [QPDF](https://qpdf.sourceforge.io/) | editing the PDF structure | Apache-2.0 | static library in `libibanascapdf.so` |
| [libjpeg-turbo](https://libjpeg-turbo.org/) | JPEG support required by QPDF | IJG, BSD-3-Clause, zlib | static library in `libibanascapdf.so` |
| zlib | compression | zlib | Android system library (not in the APK) |
| Kotlin Standard Library | Kotlin runtime | Apache-2.0 | in the APK |

Not used on Android: GTK, GnuTLS, AndroidX. The digital signature uses the Java
classes of the Android system (`KeyStore`, `KeyChain`, `Signature`).

`tools/obtener_dependencias.sh` copies the full license texts of these components to
`app/src/main/assets/licencias/`; the app shows them in Help › Open-source licenses.

libjpeg-turbo: this software is based in part on the work of the Independent JPEG Group.
