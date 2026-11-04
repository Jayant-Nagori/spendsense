package com.jayant.spendsense.imports

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.jayant.spendsense.security.AppLockGate
import com.jayant.spendsense.security.SecureWindow
import com.jayant.spendsense.ui.ThemePrefs
import com.jayant.spendsense.ui.theme.SpendSenseTheme

/** Receives a statement shared (ACTION_SEND) or opened (ACTION_VIEW) from a bank app / Files and shows [ImportScreen] with it. */
class ShareImportActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.jayant.spendsense.security.AppIntegrity.tampered) {
            startActivity(android.content.Intent(this, com.jayant.spendsense.ui.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        SecureWindow.apply(this)
        ThemePrefs.load(this)
        enableEdgeToEdge()
        val uri = sharedUri(intent)
        setContent {
            SpendSenseTheme {
                AppLockGate(this) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
                        ImportScreen(onBack = { finish() }, initialUri = uri)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUri(i: Intent?): Uri? = when (i?.action) {
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        Intent.ACTION_VIEW -> i.data
        else -> null
    }?.takeIf { isAcceptableShareUri(it.scheme, it.authority, packageName) }

    companion object {
        /**
         * Only another app's content:// document. Rejects file:// (a hostile app could point us at our own private
         * files, e.g. databases/ or shared_prefs/) and anything served under our own package's authority.
         */
        fun isAcceptableShareUri(scheme: String?, authority: String?, ownPackage: String): Boolean =
            scheme.equals("content", ignoreCase = true) && !authority.isNullOrBlank() &&
                !authority.equals(ownPackage, ignoreCase = true) && !authority.startsWith("$ownPackage.", ignoreCase = true)
    }
}
