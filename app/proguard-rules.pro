# Правила R8 для release-збірки RoadScan AI.

# TFLite: нативные методы и рефлексия по делегатам.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Room генерирует реализации через KSP — их имена нельзя переименовывать.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# kotlinx.serialization: сериализаторы ищутся по сгенерированным именам.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ai.roadscan.**$$serializer { *; }
-keepclassmembers class ai.roadscan.** { *** Companion; }
-keepclasseswithmembers class ai.roadscan.** { kotlinx.serialization.KSerializer serializer(...); }

# Лицензии и калибровка читаются через рефлексию (kotlinx.serialization).
-keep class ai.roadscan.core.license.** { *; }
-keep class ai.roadscan.core.calibration.** { *; }

# Compose поставляет собственные правила; здесь только отключаем шум.
-dontwarn androidx.compose.**
