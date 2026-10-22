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
