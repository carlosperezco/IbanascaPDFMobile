plugins {
    id("com.android.application")
}

android {
    namespace = "com.donatellatech.ibanascapdf"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.donatellatech.ibanascapdf"
        minSdk = 26
        targetSdk = 36
        // Versión de la app Android, independiente de la de escritorio.
        // En cada release: versionCode + 1 (Android solo instala una actualización si
        // este número es mayor) y versionName según el CHANGELOG (x.y.z).
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // Teléfonos actuales y emulador. Para teléfonos antiguos de 32 bits añada
            // "armeabi-v7a" aquí y en ABIS de tools/obtener_dependencias.sh.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

// Los idiomas son los del núcleo (submódulo nucleo/): se empaquetan tal cual.
androidComponents {
    onVariants { variante ->
        variante.sources.assets?.addStaticSourceDirectory(rootProject.file("nucleo/lang").absolutePath)
    }
}
