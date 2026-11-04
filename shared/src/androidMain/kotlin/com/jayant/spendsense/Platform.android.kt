package com.jayant.spendsense

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.Locale

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()

actual fun formatFixed(value: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", value)

actual fun decimalPlain(value: Double, scale: Int, stripTrailingZeros: Boolean): String {
    val bd = java.math.BigDecimal(value).setScale(scale, java.math.RoundingMode.HALF_UP)
    return (if (stripTrailingZeros) bd.stripTrailingZeros() else bd).toPlainString()
}

actual fun randomUuid(): String = java.util.UUID.randomUUID().toString()

actual typealias PlatformContext = android.content.Context

@Composable
actual fun platformContext(): PlatformContext = LocalContext.current

actual typealias ReentrantLock = java.util.concurrent.locks.ReentrantLock

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO

@Composable
actual fun rememberSystemMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return androidx.compose.runtime.remember {
        try {
            android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } catch (_: Exception) {
            true
        }
    }
}

private val secureRandom by lazy { java.security.SecureRandom() }

actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }
