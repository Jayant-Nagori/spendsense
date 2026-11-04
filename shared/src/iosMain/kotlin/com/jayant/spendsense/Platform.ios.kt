package com.jayant.spendsense

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSDate
import platform.Foundation.NSDecimalNumber
import platform.Foundation.NSDecimalNumberHandler
import platform.Foundation.NSRoundingMode
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSString
import platform.Foundation.NSUUID
import platform.Foundation.stringWithFormat
import platform.Foundation.timeIntervalSince1970

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun elapsedRealtimeMillis(): Long = (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()

@OptIn(ExperimentalForeignApi::class)
actual fun formatFixed(value: Double, decimals: Int): String =
    NSString.stringWithFormat("%.${decimals}f", value)

actual fun decimalPlain(value: Double, scale: Int, stripTrailingZeros: Boolean): String {
    val handler = NSDecimalNumberHandler(
        roundingMode = NSRoundingMode.NSRoundPlain, scale = scale.toShort(),
        raiseOnExactness = false, raiseOnOverflow = false, raiseOnUnderflow = false, raiseOnDivideByZero = false,
    )
    val rounded = NSDecimalNumber(double = value).decimalNumberByRoundingAccordingToBehavior(handler)
    // stringValue is plain and already drops trailing zeros ("12.5", "100").
    var s = rounded.stringValue
    if (s == "-0") s = "0"
    if (stripTrailingZeros || scale <= 0) return s
    val frac = s.substringAfter('.', "")
    return (if (frac.isEmpty()) "$s." else s) + "0".repeat(scale - frac.length)
}

actual fun randomUuid(): String = NSUUID().UUIDString().lowercase()

actual abstract class PlatformContext

/** The one iOS "context": there is a single app process and sandbox. */
object IosContext : PlatformContext()

@Composable
actual fun platformContext(): PlatformContext = IosContext

actual class ReentrantLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO

@Composable
actual fun rememberSystemMotionEnabled(): Boolean =
    androidx.compose.runtime.remember { !platform.UIKit.UIAccessibilityIsReduceMotionEnabled() }

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual fun secureRandomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    if (n == 0) return out
    val status = out.usePinned { pinned ->
        platform.Security.SecRandomCopyBytes(platform.Security.kSecRandomDefault, n.toULong(), pinned.addressOf(0))
    }
    check(status == 0) { "SecRandomCopyBytes failed" }
    return out
}
