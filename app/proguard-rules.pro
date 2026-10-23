<<<<<<< HEAD
# Your Friend release rules (minify is off by default; kept for when it is enabled).
-keep class com.suvo.agent.device.shizuku.ShellUserService { *; }
-keep class com.suvo.agent.nativebridge.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn com.jcraft.jsch.**
-keep class com.jcraft.jsch.** { *; }
=======
# Keep OkHttp / okio
-dontwarn okhttp3.**
-dontwarn okio.**
# Shizuku
-keep class rikka.shizuku.** { *; }
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
