package com.jayant.spendsense.security

import kotlin.concurrent.Volatile

/**
 * Process-wide "this install is not genuine" latch (Android: APK signing certificate mismatch in a release build).
 * Set once at start-up, before anything touches storage. While set, the database and stored secrets are never
 * opened, so a repackaged copy cannot read or exfiltrate the user's data through the app's own code paths.
 */
object AppIntegrity {
    @Volatile
    var tampered: Boolean = false
        private set

    fun markTampered() { tampered = true }

    internal fun resetForTests() { tampered = false }

    /** Throws instead of opening storage when the install is not genuine. */
    fun requireGenuine() {
        if (tampered) throw IntegrityException()
    }
}

class IntegrityException : IllegalStateException("SpendSense install failed its integrity check; storage stays closed")
