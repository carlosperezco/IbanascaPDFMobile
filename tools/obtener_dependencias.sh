#!/usr/bin/env bash
# Prepara las bibliotecas nativas para Android en third_party/android/<ABI>/:
#   - PDFium precompilado (https://github.com/bblanchon/pdfium-binaries)   BSD-3 / Apache-2.0
#   - libjpeg-turbo compilado con el NDK (lo necesita QPDF)                IJG / BSD-3 / zlib
#   - QPDF compilado con el NDK, criptografía propia (sin OpenSSL/GnuTLS)  Apache-2.0
# y copia sus licencias en app/src/main/assets/licencias/ (pantalla «Licencias»).
#
# Se ejecuta una vez (y al cambiar de versión). Funciona en Linux, macOS y en
# Windows con MSYS2 o Git Bash.
#
# Uso:
#   ./tools/obtener_dependencias.sh
#   ABIS="arm64-v8a" ./tools/obtener_dependencias.sh          # solo una arquitectura
#   ANDROID_NDK_HOME=/ruta/al/ndk ./tools/obtener_dependencias.sh
#
# Requisitos: curl, tar, y el NDK y CMake del SDK de Android (Android Studio ›
# SDK Manager › SDK Tools › «NDK (Side by side)» y «CMake»).
set -euo pipefail

raiz="$(cd "$(dirname "$0")/.." && pwd)"
ABIS="${ABIS:-arm64-v8a x86_64}"
API="${API:-26}"
PDFIUM_VERSION="${PDFIUM_VERSION:-8165}"     # chromium/8165, la misma que usa el escritorio
QPDF_VERSION="${QPDF_VERSION:-12.4.0}"
JPEG_VERSION="${JPEG_VERSION:-3.1.3}"

es_windows=false
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) es_windows=true ;; esac

# Ruta en el formato que entienden cmake/ninja (en Windows, C:/…).
nativa() {
    if $es_windows; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
unix() {
    if $es_windows; then cygpath -u "$1"; else printf '%s' "$1"; fi
}

descargar() { # url destino
    echo "Descargando $1"
    if ! curl -fL --retry 3 -o "$2" "$1"; then
        # En Windows, el curl del sistema usa los certificados de Windows (antivirus, proxy).
        if [[ -x /c/Windows/System32/curl.exe ]]; then
            /c/Windows/System32/curl.exe -fL --retry 3 -o "$(nativa "$2")" "$1"
        else
            return 1
        fi
    fi
}

# ------------------------------------------------------------ SDK, NDK y CMake
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" ]]; then
    if $es_windows && [[ -n "${LOCALAPPDATA:-}" ]]; then sdk="$(unix "$LOCALAPPDATA")/Android/Sdk"
    elif [[ "$(uname -s)" == "Darwin" ]]; then sdk="$HOME/Library/Android/sdk"
    else sdk="$HOME/Android/Sdk"; fi
fi
sdk="$(unix "$sdk")"

ndk="${ANDROID_NDK_HOME:-}"
if [[ -z "$ndk" ]]; then
    ndk="$(ls -d "$sdk"/ndk/*/ 2>/dev/null | sort -V | tail -n 1 || true)"
fi
ndk="$(unix "${ndk%/}")"
toolchain="$ndk/build/cmake/android.toolchain.cmake"
[[ -f "$toolchain" ]] || { echo "No se encontró el NDK (busqué en $sdk/ndk). Instálelo desde el SDK Manager o defina ANDROID_NDK_HOME." >&2; exit 1; }

cmake_bin="${CMAKE:-}"
ninja_bin="${NINJA:-}"
if [[ -z "$cmake_bin" ]]; then
    dir="$(ls -d "$sdk"/cmake/*/ 2>/dev/null | sort -V | tail -n 1 || true)"
    if [[ -n "$dir" ]]; then
        cmake_bin="${dir%/}/bin/cmake"
        ninja_bin="${ninja_bin:-${dir%/}/bin/ninja}"
    else
        cmake_bin="$(command -v cmake || true)"
    fi
fi
ninja_bin="${ninja_bin:-$(command -v ninja || true)}"
# En Windows, cmake necesita la ruta completa con .exe.
if $es_windows; then
    [[ -f "$cmake_bin.exe" ]] && cmake_bin="$cmake_bin.exe"
    [[ -f "$ninja_bin.exe" ]] && ninja_bin="$ninja_bin.exe"
fi
[[ -n "$cmake_bin" && -n "$ninja_bin" ]] || { echo "Faltan cmake y ninja (instale «CMake» desde el SDK Manager)." >&2; exit 1; }
echo "NDK:   $ndk"
echo "CMake: $cmake_bin"

# ------------------------------------------------------------ fuentes
trabajo="$raiz/third_party/android/_trabajo"
mkdir -p "$trabajo"
# pkg-config del sistema anfitrión no debe colar bibliotecas de escritorio.
mkdir -p "$trabajo/vacio"
export PKG_CONFIG_LIBDIR="$trabajo/vacio" PKG_CONFIG_PATH=""

if [[ ! -d "$trabajo/libjpeg-turbo-$JPEG_VERSION" ]]; then
    descargar "https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/$JPEG_VERSION.tar.gz" "$trabajo/jpeg.tar.gz"
    tar -xzf "$trabajo/jpeg.tar.gz" -C "$trabajo"
fi
if [[ ! -d "$trabajo/qpdf-$QPDF_VERSION" ]]; then
    descargar "https://github.com/qpdf/qpdf/archive/refs/tags/v$QPDF_VERSION.tar.gz" "$trabajo/qpdf.tar.gz"
    tar -xzf "$trabajo/qpdf.tar.gz" -C "$trabajo"
fi

# Las bibliotecas estáticas van dentro de libibanascapdf.so: todo su código debe ser
# reubicable (-fPIC). QPDF, cuando solo compila la versión estática, fuerza
# POSITION_INDEPENDENT_CODE=OFF (libqpdf/CMakeLists.txt: OBJECT_LIB_IS_PIC) y el NDK
# compila entonces como PIE, que no se puede enlazar en una biblioteca compartida.
# Se corrige esa línea en la copia descargada y, además, se pasa -fPIC al compilador
# (el toolchain del NDK lo suma a sus propias opciones).
sed -i 's/^  set(OBJECT_LIB_IS_PIC OFF)$/  set(OBJECT_LIB_IS_PIC ON)  # Ibanasca: -fPIC para Android/' \
    "$trabajo/qpdf-$QPDF_VERSION/libqpdf/CMakeLists.txt"

# Si cambia la forma de compilar, se recompila lo que se hizo con la receta anterior.
RECETA=3

comunes=(
    -G Ninja
    "-DCMAKE_MAKE_PROGRAM=$(nativa "$ninja_bin")"
    "-DCMAKE_TOOLCHAIN_FILE=$(nativa "$toolchain")"
    -DANDROID_PLATFORM="android-$API"
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DCMAKE_C_FLAGS=-fPIC
    -DCMAKE_CXX_FLAGS=-fPIC
)

for abi in $ABIS; do
    echo
    echo "=== $abi ==="
    destino="$raiz/third_party/android/$abi"
    mkdir -p "$destino"
    if [[ "$(cat "$destino/.receta" 2>/dev/null || true)" != "$RECETA" ]]; then
        rm -rf "$destino/jpeg" "$destino/qpdf" "$trabajo/jpeg-$abi" "$trabajo/qpdf-$abi"
    fi

    # --- PDFium precompilado
    case "$abi" in
        arm64-v8a) arch=arm64 ;;
        armeabi-v7a) arch=arm ;;
        x86_64) arch=x64 ;;
        x86) arch=x86 ;;
        *) echo "ABI no prevista: $abi" >&2; exit 1 ;;
    esac
    if [[ ! -f "$destino/pdfium/lib/libpdfium.so" ]]; then
        archivo="$trabajo/pdfium-android-$arch.tgz"
        if [[ "$PDFIUM_VERSION" == "latest" ]]; then
            url="https://github.com/bblanchon/pdfium-binaries/releases/latest/download/pdfium-android-$arch.tgz"
        else
            url="https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F$PDFIUM_VERSION/pdfium-android-$arch.tgz"
        fi
        descargar "$url" "$archivo"
        rm -rf "$destino/pdfium" && mkdir -p "$destino/pdfium"
        tar -xzf "$archivo" -C "$destino/pdfium"
    fi

    # --- libjpeg-turbo (estática; sin SIMD: QPDF casi no la usa y así no hace falta NASM)
    if [[ ! -f "$destino/jpeg/lib/libjpeg.a" ]]; then
        "$cmake_bin" -S "$(nativa "$trabajo/libjpeg-turbo-$JPEG_VERSION")" -B "$(nativa "$trabajo/jpeg-$abi")" \
            "${comunes[@]}" -DANDROID_ABI="$abi" \
            -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TURBOJPEG=OFF -DWITH_SIMD=OFF \
            -DCMAKE_INSTALL_PREFIX="$(nativa "$destino/jpeg")" -DCMAKE_INSTALL_LIBDIR=lib
        "$cmake_bin" --build "$(nativa "$trabajo/jpeg-$abi")"
        "$cmake_bin" --install "$(nativa "$trabajo/jpeg-$abi")"
    fi

    # --- QPDF (estática, criptografía nativa)
    if [[ ! -f "$destino/qpdf/lib/libqpdf.a" ]]; then
        "$cmake_bin" -S "$(nativa "$trabajo/qpdf-$QPDF_VERSION")" -B "$(nativa "$trabajo/qpdf-$abi")" \
            "${comunes[@]}" -DANDROID_ABI="$abi" \
            -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC_LIBS=ON -DBUILD_DOC=OFF \
            -DUSE_IMPLICIT_CRYPTO=OFF -DREQUIRE_CRYPTO_NATIVE=ON \
            -DLIBJPEG_H_PATH="$(nativa "$destino/jpeg/include")" \
            -DLIBJPEG_LIB_PATH="$(nativa "$destino/jpeg/lib/libjpeg.a")"
        # Comprobación: el código de QPDF debe compilarse con -fPIC.
        if ! grep -A4 'Pipeline\.cc\.o:' "$trabajo/qpdf-$abi/build.ninja" | grep -q -- '-fPIC'; then
            echo "QPDF no se va a compilar con -fPIC; no se podría enlazar en la app." >&2
            exit 1
        fi
        "$cmake_bin" --build "$(nativa "$trabajo/qpdf-$abi")" --target libqpdf
        mkdir -p "$destino/qpdf/lib" "$destino/qpdf/include"
        cp -r "$trabajo/qpdf-$QPDF_VERSION/include/qpdf" "$destino/qpdf/include/"
        cp "$(find "$trabajo/qpdf-$abi" -name libqpdf.a | head -n 1)" "$destino/qpdf/lib/"
    fi
    echo "$RECETA" > "$destino/.receta"
done

# ------------------------------------------------------------ licencias
lic="$raiz/app/src/main/assets/licencias"
rm -rf "$lic" && mkdir -p "$lic"
primera="$(echo "$ABIS" | awk '{print $1}')"
cp "$raiz/LICENSE" "$lic/1 Ibanasca PDF Mobile (MIT).txt"
cat "$raiz/third_party/android/$primera/pdfium/"LICENSE* > "$lic/2 PDFium.txt"
{
    cat "$trabajo/qpdf-$QPDF_VERSION/"LICENSE*
    [[ -f "$trabajo/qpdf-$QPDF_VERSION/NOTICE.md" ]] && { echo; cat "$trabajo/qpdf-$QPDF_VERSION/NOTICE.md"; }
} > "$lic/3 QPDF (Apache-2.0).txt"
{
    echo "This software is based in part on the work of the Independent JPEG Group."
    echo
    cat "$trabajo/libjpeg-turbo-$JPEG_VERSION/LICENSE.md"
    [[ -f "$trabajo/libjpeg-turbo-$JPEG_VERSION/README.ijg" ]] && { echo; cat "$trabajo/libjpeg-turbo-$JPEG_VERSION/README.ijg"; }
} > "$lic/4 libjpeg-turbo.txt"
cat > "$lic/5 Kotlin.txt" <<'TXT'
Kotlin Standard Library
Copyright JetBrains s.r.o. and Kotlin Programming Language contributors.
Licensed under the Apache License, Version 2.0 (same text as QPDF above).
https://github.com/JetBrains/kotlin
TXT

echo
echo "Listo. Abra el proyecto en Android Studio y compile."
