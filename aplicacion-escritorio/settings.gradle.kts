rootProject.name = "CanchaVoley"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// CanchaVoley: sistema de reservas de la cancha (Windows + Android). Nació de una copia de ProLech;
// el código de ProLech ya se retiró (solo quedan sus tablas locales, sin uso, para no borrar datos).
//   :composeApp        → librería KMP con toda la app (UI Compose, SQLDelight, Ktor, Koin)
//   :composeAndroidApp → APK "CanchaVoley" (pe.canchavoley.app)
//   :composeDesktopApp → app de Windows e instaladores .msi / .exe
include(":composeApp")
include(":composeAndroidApp")
include(":composeDesktopApp")
