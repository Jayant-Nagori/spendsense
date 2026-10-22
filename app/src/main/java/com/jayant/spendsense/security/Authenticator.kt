package com.jayant.spendsense.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** BiometricPrompt wrapper: BIOMETRIC_STRONG or device credential (PIN/pattern/password). */
object Authenticator {
    fun isDeviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** True when strong biometrics are enrolled (informational; credential fallback is always allowed). */
    fun hasStrongBiometric(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    @Suppress("DEPRECATION")
    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onError: (code: Int, message: String) -> Unit = { _, _ -> },
    ) {
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).apply {
            subtitle?.let { setSubtitle(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            } else {
                // API 26-29: the BIOMETRIC_STRONG|DEVICE_CREDENTIAL combo is unsupported on 28-29.
                setDeviceCredentialAllowed(true)
            }
        }.build()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                    onError(errorCode, errString.toString())
            },
        )
        prompt.authenticate(info)
    }
}
