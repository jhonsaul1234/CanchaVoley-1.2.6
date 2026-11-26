# Reglas R8 para el APK release de CanchaVoley.

# Mensajes de error legibles en reportes de fallos
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# kotlinx.serialization: los DTO de la API se (de)serializan por los serializer() generados
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }
-keep,includedescriptorclasses class pe.canchavoley.**$$serializer { *; }
-keepclassmembers class pe.canchavoley.** {
    *** Companion;
}
-dontnote kotlinx.serialization.**

# Ktor (motor y plugins se cargan por ServiceLoader)
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# SQLDelight / SQLite
-keep class app.cash.sqldelight.** { *; }
-keep class androidx.sqlite.** { *; }

# Clases de la app referenciadas desde el manifiesto o por reflexión (Koin, Application, servicios)
-keep class pe.canchavoley.** { *; }
-keep class pe.edu.upeu.prolech.** { *; }

-dontwarn com.google.android.gms.**

# Lector QR (ZXing)
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
