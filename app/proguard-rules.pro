# MediaPipe Tasks loads native/task implementation classes dynamically.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Nearby Connections may reference Google Play services classes dynamically.
-dontwarn com.google.android.gms.**

# CameraX annotations/types can be referenced transitively by generated code.
-dontwarn androidx.camera.**

# Keep DropShare callbacks and Quick Settings service reachable after R8 shrinking.
-keep class com.dropshare.app.** { *; }
