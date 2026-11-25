# Biblioteca nativa de Ibanasca PDF Mobile (Android): el núcleo compartido con
# escritorio (submódulo nucleo/) + el puente JNI.
cmake_minimum_required(VERSION 3.22.1)
project(ibanascapdf_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20) # QPDF 12 exige C++20
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

get_filename_component(IBZ_RAIZ "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(IBZ_NUCLEO "${IBZ_RAIZ}/nucleo")
set(IBZ_TERCEROS "${IBZ_RAIZ}/third_party/android/${ANDROID_ABI}")

if(NOT EXISTS "${IBZ_NUCLEO}/core/CMakeLists.txt")
    message(FATAL_ERROR "Falta el núcleo en ${IBZ_NUCLEO}.\nEjecute: git submodule update --init")
endif()
if(NOT EXISTS "${IBZ_TERCEROS}/qpdf/lib/libqpdf.a" OR NOT EXISTS "${IBZ_TERCEROS}/pdfium/lib/libpdfium.so")
    message(FATAL_ERROR "Faltan PDFium/QPDF para ${ANDROID_ABI} en ${IBZ_TERCEROS}.\n"
                        "Ejecute: ./tools/obtener_dependencias.sh")
endif()

# ------------------------------------------------------------ dependencias
# Los mismos nombres neutros que usa el escritorio (ver nucleo/core/CMakeLists.txt).

add_library(ibz_pdfium_so SHARED IMPORTED)
set_target_properties(ibz_pdfium_so PROPERTIES
    IMPORTED_LOCATION "${IBZ_TERCEROS}/pdfium/lib/libpdfium.so"
    INTERFACE_INCLUDE_DIRECTORIES "${IBZ_TERCEROS}/pdfium/include")
add_library(ibz_pdfium INTERFACE)
target_link_libraries(ibz_pdfium INTERFACE ibz_pdfium_so)

add_library(ibz_jpeg STATIC IMPORTED)
set_target_properties(ibz_jpeg PROPERTIES IMPORTED_LOCATION "${IBZ_TERCEROS}/jpeg/lib/libjpeg.a")

add_library(ibz_qpdf_a STATIC IMPORTED)
set_target_properties(ibz_qpdf_a PROPERTIES
    IMPORTED_LOCATION "${IBZ_TERCEROS}/qpdf/lib/libqpdf.a"
    INTERFACE_INCLUDE_DIRECTORIES "${IBZ_TERCEROS}/qpdf/include")
add_library(ibz_qpdf INTERFACE)
target_link_libraries(ibz_qpdf INTERFACE ibz_qpdf_a ibz_jpeg z)

add_library(ibz_zlib INTERFACE) # zlib del sistema (NDK)
target_link_libraries(ibz_zlib INTERFACE z)

# ------------------------------------------------------------ núcleo

# En Android la clave privada la maneja Java (KeyStore/KeyChain): sin GnuTLS.
set(IBZ_FIRMA_GNUTLS OFF CACHE BOOL "" FORCE)
add_subdirectory("${IBZ_NUCLEO}/core" nucleo_core)

add_library(ibanascapdf SHARED ibz_jni.cpp)
target_link_libraries(ibanascapdf PRIVATE ibanascapdfcore jnigraphics)
target_compile_options(ibanascapdf PRIVATE -Wall -Wextra)
# Páginas de 16 KB (obligatorio en Google Play para Android 15+).
target_link_options(ibanascapdf PRIVATE "-Wl,-z,max-page-size=16384")
