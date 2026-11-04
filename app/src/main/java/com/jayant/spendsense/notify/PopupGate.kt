package com.jayant.spendsense.notify

/**
 * Pure state machine behind the payment pop-up (no android.*, unit-tested). Decides, per payment, whether to draw the
 * overlay now, hand off to a full-screen-intent notification, or queue; and tracks the overlay that is on screen.
 *
 * The v1.2 bug it fixes: `current` stayed set after One UI dropped the overlay window (or `addView` failed silently),
 * so every later payment was queued until the user opened the app. Now an overlay only blocks others while its
 * window is actually attached, or for [attachTimeoutMs] while it is being attached.
 */
class PopupGate(private val attachTimeoutMs: Long = 2_000) {

    enum class Path { OVERLAY, FULL_SCREEN, QUEUE, NONE }

    /** Device snapshot taken on the main thread when a payment arrives. */
    data class Device(
        val popupEnabled: Boolean,
        val overlayAllowed: Boolean,
        val interactive: Boolean,
        val keyguardLocked: Boolean,
        val fullScreenAllowed: Boolean,
    )

    /** [reason] is a fixed token for timing logs (never message content). [alsoQueue]: keep it for the next unlock. */
    data class Decision(val path: Path, val reason: String, val alsoQueue: Boolean = false)

    private class Current(val id: Long, val since: Long, var attached: Boolean)

    private var current: Current? = null

    val currentId: Long? get() = current?.id

    /** True when an overlay is on screen (or still being attached) and should hold back new pop-ups. */
    fun busy(now: Long): Boolean {
        val c = current ?: return false
        if (c.attached) return true
        if (now - c.since <= attachTimeoutMs) return true
        current = null // never attached: stale, forget it
        return false
    }

    fun decide(id: Long, d: Device, now: Long): Decision {
        if (!d.popupEnabled) return Decision(Path.NONE, "disabled")
        if (current?.id == id && busy(now)) return Decision(Path.NONE, "already-showing")
        if (busy(now)) return Decision(Path.QUEUE, "another-open")
        // Screen off / Always-On Display / keyguard: an overlay would sit invisible behind the lock screen. Behave like
        // a call screen instead; if full-screen intents are refused, the queue shows it right after unlock.
        if (!d.interactive || d.keyguardLocked) {
            val r = if (!d.interactive) "screen-off" else "locked"
            return if (d.fullScreenAllowed) Decision(Path.FULL_SCREEN, r) else Decision(Path.QUEUE, "$r-no-fsi")
        }
        if (!d.overlayAllowed) return Decision(Path.FULL_SCREEN, "no-overlay-permission")
        return Decision(Path.OVERLAY, "unlocked")
    }

    /** Main thread, right before WindowManager.addView. */
    fun onAttaching(id: Long, now: Long) { current = Current(id, now, attached = false) }

    /** The overlay's root view reported onViewAttachedToWindow. */
    fun onAttached(id: Long) { current?.takeIf { it.id == id }?.attached = true }

    /** addView threw, or the window never attached in time. */
    fun onAttachFailed(id: Long) { if (current?.id == id) current = null }

    /** The window went away without our dismiss (system removed it, process-wide overlay hide, ...). */
    fun onDetached(id: Long) { if (current?.id == id) current = null }

    /** The user answered or closed it. */
    fun onDismissed(id: Long? = null) { if (id == null || current?.id == id) current = null }

    /** May the queue be drained now (unlock, app resume, previous pop-up closed)? */
    fun canDrain(d: Device, now: Long): Boolean =
        d.popupEnabled && d.overlayAllowed && d.interactive && !d.keyguardLocked && !busy(now)
}
