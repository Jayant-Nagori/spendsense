package com.jayant.spendsense

import androidx.compose.runtime.Composable

/** Wall-clock time in epoch milliseconds. */
expect fun currentTimeMillis(): Long

/** Monotonic clock in milliseconds (for "how long was the app in the background"). */
expect fun elapsedRealtimeMillis(): Long

/**
 * Fixed-point formatting with exactly [decimals] digits, '.' separator, no grouping ("%.2f" in Locale.US).
 * Android keeps java.util.Formatter's exact rounding; iOS uses the C formatter.
 */
expect fun formatFixed(value: Double, decimals: Int): String

/**
 * [value] rounded HALF_UP to [scale] decimals as a plain string (java.math.BigDecimal semantics on Android,
 * NSDecimalNumber on iOS). With [stripTrailingZeros], "12.5000" becomes "12.5" and "100.0000" becomes "100".
 */
expect fun decimalPlain(value: Double, scale: Int, stripTrailingZeros: Boolean): String

/** Random RFC 4122 v4 UUID string. */
expect fun randomUuid(): String

/**
 * Whatever the platform needs to reach storage and system services: android.content.Context on Android,
 * a singleton on iOS. Lets shared code keep the `Repository.get(context)` shape.
 */
expect abstract class PlatformContext

/** The current [PlatformContext] inside composition (LocalContext on Android). */
@Composable
expect fun platformContext(): PlatformContext

/** Re-entrant mutual exclusion for blocking repository code. */
expect class ReentrantLock() {
    fun lock()
    fun unlock()
}

inline fun <T> ReentrantLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** Background dispatcher for blocking storage work (Dispatchers.IO on both platforms). */
expect val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher

/** False when the user turned animations off (Android animator scale 0, iOS Reduce Motion). */
@Composable
expect fun rememberSystemMotionEnabled(): Boolean

/** Process-lifetime scope for fire-and-forget saves that must outlive the screen that started them. */
val AppScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())

/** [n] bytes from the platform CSPRNG (SecureRandom on Android, SecRandomCopyBytes on iOS). */
expect fun secureRandomBytes(n: Int): ByteArray
