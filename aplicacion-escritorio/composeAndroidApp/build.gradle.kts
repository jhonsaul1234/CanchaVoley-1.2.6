// APK de CanchaVoley. Todo el código vive en :composeApp (librería KMP);
// este módulo solo empaqueta el ícono, el manifiesto y la configuración de red.
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
}

// Firma del release: keystore.properties (fuera de git). Sin él, el release sale sin firmar.
val firma = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Copia de prueba (-PcanchaPruebas=true): se instala JUNTO a la versión normal (otro identificador),
// con su propio nombre, sus propios datos y su propia sesión. Sirve para probar cambios sin tocar la instalada.
val esCopiaDePrueba = providers.gradleProperty("canchaPruebas").orNull.toBoolean()

android {
    namespace  = "pe.canchavoley.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Identificador propio de CanchaVoley: distinto del de ProLech ("pe.prolech.acopio")
        // para no reemplazarla (ni a sus datos) si ambas se instalan en el mismo celular.
        applicationId = if (esCopiaDePrueba) "pe.canchavoley.app.prueba" else "pe.canchavoley.app"
        minSdk        = libs.versions.android.minSdk.get().toInt()
        targetSdk     = libs.versions.android.targetSdk.get().toInt()
        versionCode   = 11
        versionName   = if (esCopiaDePrueba) "1.2.6-prueba" else "1.2.6"
        manifestPlaceholders["nombreApp"] = if (esCopiaDePrueba) "CanchaVoley prueba" else "CanchaVoley"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    signingConfigs {
        if (firma.getProperty("storeFile") != null) {
            create("release") {
                storeFile     = rootProject.file(firma.getProperty("storeFile"))
                storePassword = firma.getProperty("storePassword")
                keyAlias      = firma.getProperty("keyAlias")
                keyPassword   = firma.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: reduce y ofusca el código (APK más liviano y más difícil de copiar)
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // DEF-15: Android 7 (API 24 y 25) no trae java.time, que usan las fechas (kotlinx-datetime).
        // La «desugarización» de la biblioteca de Java lo incluye en el APK; sin esto la app se cerraba.
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(project(":composeApp"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
