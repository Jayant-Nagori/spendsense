package com.jayant.spendsense.platform

/**
 * The single switch for debug-only behaviour (QA deep links, the fake order server, its developer settings).
 *
 * Android release builds pin [enabled] to `false` with an R8 `-assumevalues` rule (app/proguard-rules.pro), so every
 * branch behind it — and the strings it uses — is removed from the release APK. iOS release binaries report
 * `Platform.isDebugBinary == false`. Never gate anything security-relevant on the *absence* of this flag.
 */
object DebugGate {
    fun enabled(): Boolean = runCatching { Platform.services.isDebugBuild }.getOrDefault(false)
}
