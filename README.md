# Ibanasca PDF Mobile

Lightweight, free and open-source PDF editor for Android, part of
[DonatellaTech](https://donatellatech.com). Website: <https://donatellatech.com/IbanascaPDFApp/>.
It is the Android version of [Ibanasca PDF Lab](https://github.com/carlosperezco/IbanascaPDFLab),
the desktop editor, and shares its C++ core: both do the same things with the same code.

[Leer en español](README.es.md)

## Features

- **View:** continuous scrolling, pinch zoom, double tap, fit width or page, go to page,
  password-protected PDFs, "Open with" from other apps.
- **Pages:** rotate, move, delete, duplicate, insert blank pages, organize them by
  dragging thumbnails.
- **File:** new PDF with a blank page (A4, Letter, Legal, A5, A3); share the document
  with other apps (mail, messaging, cloud storage).
- **Document:** merge PDFs; split by page or by ranges, or extract pages to another PDF.
- **Write:** add text and PNG images where you tap.
- **Sign:** draw your signature with your finger, or use a PNG image; PAdES-B-B digital
  signature with a `.p12`/`.pfx` file or with a certificate installed in Android.
- Undo and redo. The original file is never overwritten ("Save As").
- No permissions, no account, no internet. English, Spanish and Italian.

## Install

Download the APK from [Releases](https://github.com/carlosperezco/IbanascaPDFMobile/releases)
and open it on the phone. Android will ask you to allow installing apps from that source
(the browser or the file manager). Requires Android 8.0 or later.

## Build

Requirements: Android Studio with the Android SDK, **NDK** and **CMake** (SDK Manager ›
SDK Tools), `git`, `curl` and `bash` (on Windows: MSYS2 or Git Bash).

```bash
git clone --recurse-submodules https://github.com/carlosperezco/IbanascaPDFMobile.git
cd IbanascaPDFMobile
./tools/obtener_dependencias.sh     # PDFium, QPDF and libjpeg-turbo for Android (once)
```

Then open the folder in Android Studio and run the `app` configuration. If you cloned
without `--recurse-submodules`, run `git submodule update --init` first.

`tools/obtener_dependencias.sh` downloads prebuilt PDFium and compiles QPDF and
libjpeg-turbo with the NDK for `arm64-v8a` and `x86_64` (emulator). Variables:
`ABIS`, `API`, `PDFIUM_VERSION`, `QPDF_VERSION`, `JPEG_VERSION`, `ANDROID_NDK_HOME`.

To update the core: `git submodule update --remote nucleo` and commit the new reference.

## Versions and releases

The Android app has its own version (`versionName` in `app/build.gradle.kts`),
independent of the desktop version. The CHANGELOG states which core version each
release uses. For each release:

1. Increase `versionCode` by one and set `versionName`; update the CHANGELOG.
2. Android Studio › Build › Generate Signed App Bundle or APK › APK › `release`,
   with your own key (`.jks`). Keep the key and its password outside the repository and
   make a backup: updates must be signed with the same key.
3. Install the signed APK on a phone and test it before publishing it.
4. Commit, tag (`vX.Y.Z`) and attach the APK to the GitHub release, named
   `IbanascaPDFMobile-X.Y.Z.apk` (the download button of the website expects that name).

## Architecture

```
nucleo/                  git submodule: Ibanasca PDF Lab (desktop); only core/ and lang/ are used
app/src/main/cpp/        CMakeLists.txt and ibz_jni.cpp (bridge Kotlin ↔ C++)
app/src/main/java/…      Kotlin interface (Android framework only, no AndroidX)
  MainActivity.kt        menus built from the core modules, file pickers, flow of each operation
  VisorPdf.kt            page viewer (gestures, rendering on a worker thread)
  DialogoParametros.kt   generic parameter dialog (same idea as the GTK one)
  LienzoFirma.kt         signature drawn with the finger
  FirmanteAndroid.kt     private key with KeyStore (.p12) or KeyChain (installed certificate)
tools/                   obtener_dependencias.sh
```

All calls to the core go through a single worker thread. A new module added to the
core (`nucleo/core/src/modules/`) appears automatically in the Android menus and dialog.

The digital signature is built by the core (CMS, SHA-256, PAdES attributes); Android
only signs the bytes with the private key through the system classes. Some old Android
versions cannot read `.p12` files created with recent OpenSSL defaults (AES); in that
case, install the certificate in Android (Settings › Security › Install certificate) and
choose "Installed certificate".

## License

MIT — see [LICENSE](LICENSE). Third-party components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
