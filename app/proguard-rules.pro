# ONNX Runtime's JNI bridge resolves Java classes and members by name.
# https://onnxruntime.ai/docs/build/android.html
-keep class ai.onnxruntime.** { *; }

# Shizuku loads the helper constructor by its component name.
-keep class com.caleb.prism.SpotifyRelayService { public <init>(...); *; }
