# CourierStack HID Remote ProGuard Rules

# Keep CourierStack classes
-keep class com.courierstack.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep data classes
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
