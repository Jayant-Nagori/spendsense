# SQLCipher: native code looks up these classes/methods via JNI.
-keep class net.zetetic.** { *; }
-keep interface net.zetetic.** { *; }
-dontwarn net.zetetic.**

# AndroidX SQLite interfaces implemented by SQLCipher's helper.
-keep class androidx.sqlite.** { *; }

# ---- Release hardening (Phase 6) -----------------------------------------------------------------------------
# Debug-only code (spendsense://debug links, fake order server, its developer settings) is dead in release:
# DebugGate.enabled() is pinned to false so R8 removes those branches and their strings.
-assumevalues class com.jayant.spendsense.platform.DebugGate {
    boolean enabled() return false;
}

# No logging of any kind survives in release (the app never logs message/order contents, and this guarantees
# that no library or forgotten call does either).
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
}
-assumenosideeffects class kotlin.io.ConsoleKt {
    public static void println(...);
    public static void print(...);
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# Make reverse engineering harder: repackage everything obfuscated into one flat package, drop source file names.
-repackageclasses ''
-allowaccessmodification
-renamesourcefileattribute ''

# Ktor (OkHttp engine is passed explicitly, no ServiceLoader lookup). Coroutine/atomicfu fields are updated
# through field updaters by name.
-keepclassmembers class io.ktor.** { volatile <fields>; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn org.slf4j.**
-dontwarn io.ktor.util.debug.**
-dontwarn java.lang.management.**

# OkHttp / Okio ship their own consumer rules; silence optional TLS providers.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# Email import (JavaMail for Android) loads providers reflectively
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn javax.naming.**

# Statement import (PdfBox-Android) uses reflection for fonts/filters/encryption
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
