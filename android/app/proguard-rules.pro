# ─── ONNX Runtime ────────────────────────────────────────────────────────────
# Maven artifact: com.microsoft.onnxruntime:onnxruntime-android
# Internal Java package (v1.14+): ai.onnxruntime
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ─── MediaPipe ───────────────────────────────────────────────────────────────
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ─── App services (must survive minification) ────────────────────────────────
-keep class com.example.gaze.GazeForegroundService { *; }
-keep class com.example.gaze.GazeAccessibilityService { *; }
-keep class com.example.gaze.GazeForegroundService$* { *; }
-keep class com.example.gaze.GazeForegroundService$OnnxDirectRunner { *; }
-keep class com.example.gaze.GazeDirection { *; }

# ─── Kotlin coroutines & metadata ────────────────────────────────────────────
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ─── Flutter embedding ───────────────────────────────────────────────────────
-keep class io.flutter.** { *; }
-keep class io.flutter.embedding.** { *; }
-dontwarn io.flutter.**

# ─── AndroidX Accessibility ──────────────────────────────────────────────────
-keep class androidx.core.view.accessibility.** { *; }

# ─── AndroidX Concurrent (needed by camera_android_camerax) ──────────────────
-keep class androidx.concurrent.futures.** { *; }
-dontwarn androidx.concurrent.**

# ─── JSON parsing (used in loadPersonalizedProfile) ──────────────────────────
-keep class org.json.** { *; }

# ─── General Android safety rules ────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Exceptions

# Keep native method signatures for JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
