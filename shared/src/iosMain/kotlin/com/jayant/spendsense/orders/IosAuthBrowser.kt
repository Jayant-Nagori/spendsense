package com.jayant.spendsense.orders

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * Sign-in with ASWebAuthenticationSession and a custom URL scheme callback: works with a free Apple ID (no associated
 * domains / entitlements). Ephemeral session: no cookies shared with Safari.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosAuthBrowser : AuthBrowser {
    private var session: ASWebAuthenticationSession? = null

    private val anchorProvider = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor = keyWindow()
    }

    private fun keyWindow(): UIWindow? {
        val scenes = UIApplication.sharedApplication.connectedScenes
        for (s in scenes) {
            val scene = s as? UIWindowScene ?: continue
            (scene.windows.firstOrNull { (it as UIWindow).isKeyWindow() } as? UIWindow)?.let { return it }
            (scene.windows.firstOrNull() as? UIWindow)?.let { return it }
        }
        return null
    }

    override suspend fun authorize(url: String, redirectUri: String): String? = withContext(Dispatchers.Main) {
        val scheme = redirectUri.substringBefore(':')
        val nsUrl = NSURL.URLWithString(url) ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            val s = ASWebAuthenticationSession(nsUrl, scheme) { callback, error ->
                session = null
                if (cont.isActive) cont.resume(if (error == null) callback?.absoluteString else null)
            }
            s.presentationContextProvider = anchorProvider
            s.prefersEphemeralWebBrowserSession = true
            session = s
            if (!s.start()) { session = null; cont.resume(null) }
            cont.invokeOnCancellation { s.cancel() }
        }
    }
}
