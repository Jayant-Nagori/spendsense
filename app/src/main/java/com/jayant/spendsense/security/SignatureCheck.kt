package com.jayant.spendsense.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.jayant.spendsense.BuildConfig
import java.security.MessageDigest

/**
 * Runtime anti-tamper check: is this APK signed with SpendSense's release certificate?
 *
 * A repackaged copy (e.g. one with a data-stealing payload injected and re-signed) cannot carry our certificate, so
 * its signer digest differs from [BuildConfig.EXPECTED_CERT_SHA256] (set for release builds only, from
 * `apksigner verify --print-certs` on the signed release APK). Debug builds skip the check.
 *
 * This raises the bar against casual repackaging; it is not proof against an attacker who also patches this check
 * out or hooks PackageManager on a rooted device (see SECURITY_REVIEW.md).
 */
object SignatureCheck {

    enum class Verdict { GENUINE, NOT_GENUINE, SKIPPED }

    /** Lower-case hex without separators ("AD:F4:…" and "adf4…" compare equal). */
    fun normalize(hex: String): String = hex.filter { it.isLetterOrDigit() }.lowercase()

    private fun isSha256Hex(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Pure decision, unit-tested. Genuine only when there is at least one signer and *every* current signer is the
     * expected certificate. A missing or malformed expected value fails closed (release builds must embed one).
     */
    fun verdict(actualSignerDigests: Collection<String>, expected: String?, debugBuild: Boolean): Verdict {
        if (debugBuild) return Verdict.SKIPPED
        val want = normalize(expected.orEmpty())
        if (!isSha256Hex(want)) return Verdict.NOT_GENUINE
        val got = actualSignerDigests.map(::normalize)
        if (got.isEmpty()) return Verdict.NOT_GENUINE
        return if (got.all { it == want }) Verdict.GENUINE else Verdict.NOT_GENUINE
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }

    /** SHA-256 of each certificate currently signing this APK (past rotated-out certificates are not included). */
    @SuppressLint("PackageManagerGetSignatures")
    fun signerDigests(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return@runCatching emptyList()
            info.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
        signatures.map { sha256Hex(it.toByteArray()) }
    }.getOrDefault(emptyList())

    /** Runs the check for this process (cheap; done once in Application.onCreate). */
    fun check(context: Context): Verdict =
        verdict(
            actualSignerDigests = if (BuildConfig.DEBUG) emptyList() else signerDigests(context),
            expected = BuildConfig.EXPECTED_CERT_SHA256,
            debugBuild = BuildConfig.DEBUG,
        )
}
