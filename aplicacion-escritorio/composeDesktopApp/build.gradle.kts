// CanchaVoley: aplicación de ESCRITORIO para Windows (instalador .msi / .exe).
// Igual que :composeAndroidApp para el APK, este módulo solo empaqueta: todo el código
// (pantallas, SQLDelight, Ktor, Koin, sincronización) vive en :composeApp.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Carpeta de compilación opcional fuera del proyecto (-PcanchaCarpetaCompilacion=<carpeta>).
// En la PC de desarrollo la protección de archivos (antivirus) no deja crear runtime\bin\ucrtbase.dll
// en Documentos ni en AppData\Local (createRuntimeImage falla con AccessDeniedException), pero sí en %TEMP%.
// scripts/compilar.ps1 compila la app de escritorio en %TEMP%\CanchaVoley-compilacion y copia el .msi a dist\.
providers.gradleProperty("canchaCarpetaCompilacion").orNull?.takeIf { it.isNotBlank() }?.let { carpeta ->
    layout.buildDirectory.set(file(carpeta).resolve(project.name))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
}

// Windows 10/11 ya trae el UCRT (ucrtbase.dll en System32). La copia local que agrega jlink no hace falta y
// el antivirus de la PC de desarrollo bloquea instalarla (msiexec: error 1310 al escribir
// runtime\bin\ucrtbase.dll), así que jlink la deja fuera de la imagen de Java.
// La autoprueba (CanchaVoley.exe --autoprueba) comprueba que el Java recortado funciona sin ella.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask>().configureEach {
    freeArgs.add("--exclude-files=**ucrtbase.dll")
}

compose.desktop {
    application {
        mainClass = "pe.canchavoley.escritorio.MainKt"
        // DEF-19: tras un copiado fallido (portapapeles ocupado) la app olvida el texto que Java creía tener;
        // para eso necesita abrir java.awt.datatransfer (lo comprueba «CanchaVoley.exe --autoprueba»).
        jvmArgs += listOf("--add-opens", "java.datatransfer/java.awt.datatransfer=ALL-UNNAMED")

        nativeDistributions {
            // Instaladores de Windows: .msi (Windows Installer) y .exe
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName    = "CanchaVoley"
            packageVersion = "1.2.6"
            description    = "CanchaVoley - Gestion de reservas de la cancha"
            vendor         = "CanchaVoley"
            // Solo los módulos de Java que la app usa (antes iban todos y el instalador pesaba más).
            // Se suman a los que Compose incluye siempre: java.base, java.desktop, java.logging y
            // jdk.crypto.ec. Lista de «gradlew :composeDesktopApp:suggestRuntimeModules» (java.sql:
            // SQLite; java.prefs: token; java.management/java.instrument/jdk.unsupported: librerías).
            // scripts/compilar.ps1 lo comprueba con «CanchaVoley.exe --autoprueba» al compilar.
            // Si algún día faltara un módulo: gradlew packageMsi -PcanchaTodosLosModulos=true
            val todosLosModulos = providers.gradleProperty("canchaTodosLosModulos").orNull.toBoolean()
            if (todosLosModulos) {
                includeAllModules = true
            } else {
                modules("java.instrument", "java.management", "java.prefs", "java.sql", "jdk.unsupported")
            }

            windows {
                // Ícono del programa, del acceso directo y del menú Inicio (pelota del logo; ver icono/)
                iconFile.set(project.file("icono/canchavoley.ico"))
                menu           = true
                menuGroup      = "CanchaVoley"
                shortcut       = true
                dirChooser     = true
                perUserInstall = true
                // Fijo: permite que una versión nueva del .msi reemplace a la anterior
                upgradeUuid    = "6aadc07b-0a46-4191-a13b-714225772ea6"
            }
        }
    }
}
