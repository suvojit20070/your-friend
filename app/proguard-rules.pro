# Your Friend — R8 rules for the release build: only the app's own code (com.suvo.agent) is shrunk.
#
# Class and method NAMES are kept on purpose (-dontobfuscate): crash reports that users share
# stay readable. Unused code is still removed and the rest optimized, which is where the size goes.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*,Exceptions

# Some libraries refer to desktop-Java classes that Android does not have (only used off Android).
-ignorewarnings
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn java.beans.**
-dontwarn javax.naming.**
-dontwarn javax.security.sasl.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn sun.**
-dontwarn com.sun.jna.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.jcraft.jzlib.**
-dontwarn org.apache.**

# ---- The app ----------------------------------------------------------------------------------
# Native audio (C++ through JNI), Shizuku's user service (started by class name in another process).
-keep class com.suvo.agent.nativebridge.** { *; }
-keep class com.suvo.agent.device.shizuku.ShellUserService { *; }
-keepclasseswithmembernames class * { native <methods>; }
# Methods JavaScript in a WebView calls (agent browser, code sandbox).
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# androidx.webkit features used through reflection (desktop mode client hints, document-start JS).
-keep class androidx.webkit.** { *; }

# ---- Libraries: kept whole, never shrunk or changed ----------------------------------------
# Everything that is not the app's own code stays exactly as the library shipped it (their
# reflection, JNI, JNA and ServiceLoader keep working as they do in the debug build).
-keep class !com.suvo.agent.**, ** { *; }
