package com.jayant.spendsense.orders

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsIntent
import com.jayant.spendsense.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sign-in in a Chrome Custom Tab (falls back to any browser). The provider redirects to
 * `com.jayant.spendsense:/oauth/callback`, which [OAuthRedirectActivity] hands back here. If the user closes the tab
 * instead, MainActivity resumes without a redirect and the attempt is cancelled.
 */
class AndroidAuthBrowser(private val app: Context) : AuthBrowser {

    override suspend fun authorize(url: String, redirectUri: String): String? {
        val pending = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            current?.complete(null)
            current = pending
            launchedAt = android.os.SystemClock.elapsedRealtime()
            val uri = Uri.parse(url)
            val intent = CustomTabsIntent.Builder().setShowTitle(true).build().intent.setData(uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { pending.complete(null) }
            }
        }
        val result = withTimeoutOrNull(10 * 60_000L) { pending.await() }
        withContext(Dispatchers.Main) { if (current === pending) current = null }
        return result?.takeIf { OAuthClient.isOurRedirect(it, redirectUri) }
    }

    companion object {
        private var current: CompletableDeferred<String?>? = null
        private var launchedAt = 0L
        private val main = Handler(Looper.getMainLooper())

        /** Called by [OAuthRedirectActivity] on the main thread. */
        fun deliver(url: String) {
            // Only while a sign-in we started is waiting, and only for our exact redirect.
            if (!OAuthClient.isOurRedirect(url, OrderConnector.REDIRECT_URI)) return
            current?.complete(url)
        }

        /**
         * MainActivity came back to the front. If a sign-in is pending and no redirect arrives shortly, the user closed
         * the browser: cancel.
         */
        fun onHostResumed() {
            val p = current ?: return
            if (android.os.SystemClock.elapsedRealtime() - launchedAt < 1500) return
            main.postDelayed({ if (current === p && !p.isCompleted) p.complete(null) }, 1200)
        }
    }
}

/** Receives the OAuth redirect (custom scheme), passes it on and returns to the app. Shows nothing itself. */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.jayant.spendsense.security.AppIntegrity.tampered && intent?.action == Intent.ACTION_VIEW) {
            intent?.data?.toString()?.let { AndroidAuthBrowser.deliver(it) }
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
