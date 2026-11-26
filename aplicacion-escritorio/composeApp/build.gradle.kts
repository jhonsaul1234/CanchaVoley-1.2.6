import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// CanchaVoley: librería KMP con toda la app (UI Compose, SQLDelight, Ktor, Koin) para
// Windows (jvm) y Android. AGP 9 no permite 'kotlin.multiplatform' + 'com.android.application'
// en el mismo módulo: el APK lo genera :composeAndroidApp y el instalador :composeDesktopApp.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    android {
        namespace  = "pe.canchavoley.app.compartido"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk     = libs.versions.android.minSdk.get().toInt()

        compilerOptions { jvmTarget = JvmTarget.JVM_11 }

        // Tests de commonTest en la JVM (src/androidHostTest)
        withHostTest { }
    }

    // Escritorio (Windows) con la misma lógica compartida
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    sourceSets {

        // ── commonMain ────────────────────────────────────────────────────────
        commonMain.dependencies {
            // Compose Multiplatform
            implementation(libs.kmp.compose.runtime)
            implementation(libs.kmp.compose.foundation)
            implementation(libs.kmp.compose.material3)
            implementation(libs.kmp.compose.ui)
            // Botón «atrás» de Android (y Esc en la PC)
            implementation("org.jetbrains.compose.ui:ui-backhandler:${libs.versions.composeMultiplatform.get()}")

            // Lifecycle KMP (fija la versión que usan Compose y Koin)
            implementation(libs.kmp.lifecycle.viewmodel.compose)
            implementation(libs.kmp.lifecycle.runtime.compose)

            // Ktor Client KMP
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)

            // Koin KMP
            implementation(libs.koin.core)
            implementation(libs.koin.compose)

            // Coroutines
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.kotlinx.coroutines.get()}")

            // kotlinx-datetime
            implementation(libs.kotlinx.datetime)

            // SQLDelight: Flow reactivo (asFlow().mapToList())
            implementation(libs.sqldelight.coroutines.extensions)
        }

        // ── androidMain ───────────────────────────────────────────────────────
        androidMain.dependencies {
            // Ktor Android Engine
            implementation(libs.ktor.client.android)

            // Koin Android (androidContext)
            api(libs.koin.android)

            // DataStore Preferences (token cifrado con el Android Keystore)
            implementation(libs.datastore.preferences)

            // Activity + Compose
            api(libs.androidx.activity.compose)

            // Coroutines Android
            implementation(libs.kotlinx.coroutinesAndroid)

            // SQLDelight Android driver
            implementation(libs.sqldelight.android.driver)

            // Lectura de códigos QR con la cámara (ZXing, de código abierto, funciona sin internet)
            implementation("com.journeyapps:zxing-android-embedded:4.3.0")
        }

        // ── jvmMain (escritorio Windows) ─────────────────────────────────────
        jvmMain.dependencies {
            // Ktor CIO Engine (Kotlin puro)
            implementation(libs.ktor.client.cio)
            // SQLDelight JDBC: SQLite en %APPDATA%\CanchaVoley
            implementation(libs.sqldelight.sqlite.driver)
            // Dispatchers.Main en escritorio (Swing)
            implementation(libs.kotlinx.coroutinesSwing)
            // DPAPI de Windows para guardar el token cifrado
            implementation("net.java.dev.jna:jna-platform:5.6.0")
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }

        // Driver JDBC en memoria para correr los tests de commonTest en la JVM
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.kotlin.testJunit)
        }

        // Los mismos tests de commonTest, también en la JVM de escritorio
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
        }
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("pe.edu.upeu.prolech.db")
        }
    }
}

// DEF-19: las pruebas de la JVM usan el mismo --add-opens que el lanzador de la app de escritorio.
tasks.withType<Test>().configureEach {
    if (name == "jvmTest") jvmArgs("--add-opens", "java.datatransfer/java.awt.datatransfer=ALL-UNNAMED")
}
