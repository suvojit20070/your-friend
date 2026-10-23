# Your Friend release rules (minify is off by default; kept for when it is enabled).
-keep class com.suvo.agent.device.shizuku.ShellUserService { *; }
-keep class com.suvo.agent.nativebridge.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn com.jcraft.jsch.**
-keep class com.jcraft.jsch.** { *; }
