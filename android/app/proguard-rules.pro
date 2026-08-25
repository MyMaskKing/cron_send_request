# Glance / RemoteViews
-keep class androidx.glance.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class xyz.a10023456.todowidget.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
