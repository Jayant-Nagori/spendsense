# SQLCipher: native code looks up these classes/methods via JNI.
-keep class net.zetetic.** { *; }
-keep interface net.zetetic.** { *; }
-dontwarn net.zetetic.**

# AndroidX SQLite interfaces implemented by SQLCipher's helper.
-keep class androidx.sqlite.** { *; }

# Strip verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

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
