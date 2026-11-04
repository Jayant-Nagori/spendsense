package com.jayant.spendsense.notify

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jayant.spendsense.BuildConfig
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.ui.theme.SpendSenseTheme

/**
 * Full-screen pop-up right after a payment is detected. Unlocked phone with "Display over other apps": an overlay
 * window over any app. Locked / screen off / no overlay permission / overlay failed to attach: a full-screen-intent
 * notification that opens [PaymentPopupActivity] (like an incoming call). Decisions live in [PopupGate].
 * Timings and reasons are logged (debug builds; release strips Log), never message content.
 */
object PaymentPopup {
    private const val TAG = "SS-Popup"
    private val main = Handler(Looper.getMainLooper())
    private val gate = PopupGate()
    private var current: Pair<FrameLayout, OverlayOwner>? = null

    private const val PREFS = "payment_popup"
    private const val KEY_QUEUE = "queue"
    private const val MAX_AGE_MS = 12L * 3600 * 1000
    private const val ATTACH_CHECK_MS = 1_500L

    fun canShow(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)

    /** Android 14+ needs the user's OK for full-screen intents; earlier versions grant it at install. */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    private fun device(app: Context): PopupGate.Device {
        val km = app.getSystemService(KeyguardManager::class.java)
        val pm = app.getSystemService(PowerManager::class.java)
        return PopupGate.Device(
            popupEnabled = runCatching { Settings(app).paymentPopup }.getOrDefault(true),
            overlayAllowed = canShow(app),
            interactive = pm?.isInteractive == true,
            keyguardLocked = km?.isKeyguardLocked == true,
            fullScreenAllowed = canUseFullScreen(app),
        )
    }

    /**
     * A live debit was stored: posts its notification and shows the pop-up the fastest way the phone allows.
     * Any thread. [receivedAt] is SystemClock.elapsedRealtime() when the SMS / notification reached the app.
     */
    fun present(context: Context, txn: Transaction, receivedAt: Long?) {
        val app = context.applicationContext
        val stored = SystemClock.elapsedRealtime()
        main.post {
            val now = SystemClock.elapsedRealtime()
            val d = device(app)
            val decision = gate.decide(txn.id, d, now)
            val ingestMs = receivedAt?.let { stored - it }
            when (decision.path) {
                PopupGate.Path.OVERLAY -> {
                    SplitPromptNotifier.notifyNewDebit(app, txn, fullScreen = false)
                    if (!attach(app, txn, receivedAt)) fallback(app, txn, "overlay-add-failed")
                }
                PopupGate.Path.FULL_SCREEN -> if (fsiBudget(now)) SplitPromptNotifier.notifyNewDebit(app, txn, fullScreen = true) else {
                    // A flood (e.g. spoofed SMS) gets ordinary notifications, not a screen takeover each (R4-10).
                    SplitPromptNotifier.notifyNewDebit(app, txn, fullScreen = false); enqueue(app, txn.id)
                }
                PopupGate.Path.QUEUE -> {
                    SplitPromptNotifier.notifyNewDebit(app, txn, fullScreen = false)
                    enqueue(app, txn.id)
                }
                PopupGate.Path.NONE -> SplitPromptNotifier.notifyNewDebit(app, txn, fullScreen = false)
            }
            log("decide path=${decision.path} reason=${decision.reason} ingestMs=${ingestMs ?: -1} queueMs=${now - stored}")
        }
    }

    private val fsiTimes = ArrayDeque<Long>()

    /** At most 3 full-screen launches a minute. Main thread. */
    private fun fsiBudget(now: Long): Boolean {
        while (fsiTimes.isNotEmpty() && now - fsiTimes.first() > 60_000) fsiTimes.removeFirst()
        if (fsiTimes.size >= 3) return false
        fsiTimes.addLast(now); return true
    }

    /** Overlay could not be shown: re-post the same notification with a full-screen intent (heads-up when unlocked). */
    private fun fallback(app: Context, txn: Transaction, reason: String) {
        log("fallback reason=$reason")
        if (canUseFullScreen(app) && fsiBudget(SystemClock.elapsedRealtime())) SplitPromptNotifier.notifyNewDebit(app, txn, fullScreen = true) else enqueue(app, txn.id)
    }

    /** Called on unlock, app resume and process start: shows the oldest payment still waiting for an answer. */
    fun onUnlock(context: Context) {
        val app = context.applicationContext
        main.post { showNext(app) }
    }

    /** Debit [id] turned out to be money in (the bank said "credited"): take down its pop-up and notification. */
    fun withdraw(context: Context, id: Long) {
        val app = context.applicationContext
        runCatching { androidx.core.app.NotificationManagerCompat.from(app).cancel(id.toInt()) }
        main.post {
            removeFromQueue(app, id)
            if (gate.currentId == id) dismissNow(app)
            log("withdrawn")
        }
    }

    /** Main thread. False when the overlay is already showing [id] (the activity should just close). */
    fun claimForActivity(context: Context, id: Long): Boolean {
        removeFromQueue(context.applicationContext, id)
        return !(gate.currentId == id && gate.busy(SystemClock.elapsedRealtime()))
    }

    private val draining = java.util.concurrent.atomic.AtomicBoolean(false)

    // All queue reads/writes go through these synchronized helpers (main thread and the popup-next thread).
    @Synchronized
    private fun queue(app: Context): MutableList<Long> =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_QUEUE, "").orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }.toMutableList()

    @Synchronized
    private fun saveQueue(app: Context, ids: List<Long>) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_QUEUE, ids.takeLast(20).joinToString(",")).apply()
    }

    @Synchronized
    private fun enqueue(app: Context, id: Long) {
        val q = queue(app)
        if (id !in q) { q += id; saveQueue(app, q) }
    }

    @Synchronized
    private fun removeFromQueue(app: Context, id: Long) {
        val q = queue(app)
        if (q.remove(id)) saveQueue(app, q)
    }

    @Synchronized
    private fun pollQueue(app: Context): Long? {
        val q = queue(app)
        if (q.isEmpty()) return null
        val id = q.removeAt(0)
        saveQueue(app, q)
        return id
    }

    /** Main thread. Pops queued ids until one is still unanswered and recent, then shows it. One drain at a time. */
    private fun showNext(app: Context) {
        if (!gate.canDrain(device(app), SystemClock.elapsedRealtime()) || queue(app).isEmpty()) return
        if (!draining.compareAndSet(false, true)) return
        Thread({
            try {
                val repo = TransactionRepository.get(app)
                var next: Transaction? = null
                while (next == null) {
                    val id = pollQueue(app) ?: break
                    val t = runCatching { repo.get(id) }.getOrNull()
                    if (t != null && !t.reviewed && System.currentTimeMillis() - t.timestamp < MAX_AGE_MS) next = t
                }
                next?.let { t ->
                    main.post {
                        if (gate.canDrain(device(app), SystemClock.elapsedRealtime())) {
                            if (!attach(app, t, null)) fallback(app, t, "drain-add-failed")
                        } else enqueue(app, t.id)
                    }
                }
            } finally {
                draining.set(false)
            }
        }, "popup-next").start()
    }

    /** Called from a tap on our visible overlay, which is what lets a background app start an activity. */
    private fun openLockedActivity(app: Context, txn: Transaction): Boolean = runCatching {
        app.startActivity(
            Intent(app, PaymentPopupActivity::class.java).putExtra(SplitActivity.EXTRA_TXN_ID, txn.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        )
        log("activity-for-app-lock")
    }.isSuccess

    /** Main thread. True when addView succeeded; whether the window really attached is checked shortly after. */
    private fun attach(app: Context, txn: Transaction, receivedAt: Long?): Boolean {
        // With app lock on, payment details must sit behind the lock (R3 M3). The overlay still appears instantly, but
        // locked; its Unlock opens the activity, whose AppLockGate asks for the biometric. Starting the activity
        // straight from the background is blocked on Android 15+ unless an overlay is already showing.
        val appLocked = runCatching { Settings(app).appLockEnabled }.getOrDefault(true)
        removeCurrent(app)
        val wm = app.getSystemService(WindowManager::class.java) ?: return false
        val owner = OverlayOwner().also { it.start() }
        val root = object : FrameLayout(app) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        // First Back only hides the keyboard, so a half-typed note isn't thrown away.
                        val imeOpen = androidx.core.view.ViewCompat.getRootWindowInsets(this)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                        if (imeOpen) {
                            app.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                ?.hideSoftInputFromWindow(windowToken, 0)
                        } else {
                            dismissNow(app)
                        }
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val started = SystemClock.elapsedRealtime()
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                gate.onAttached(txn.id)
                val t = SystemClock.elapsedRealtime()
                log("attached attachMs=${t - started} totalMs=${receivedAt?.let { t - it } ?: -1}")
            }

            override fun onViewDetachedFromWindow(v: View) {
                // Our own dismiss already cleared [current]; anything else means the system took the window away.
                if (current?.first === v) {
                    log("detached-by-system")
                    current = null
                    gate.onDetached(txn.id)
                    owner.stop()
                }
            }
        })
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.addView(ComposeView(app).apply {
            setContent {
                SpendSenseTheme(darkTheme = true) {
                    if (appLocked) {
                        LockedPrompt(onUnlock = { openLockedActivity(app, txn); dismissNow(app) }, onLater = { dismissNow(app) })
                    } else {
                        PaymentPopupContent(txn, onClose = { dismissNow(app) })
                    }
                }
            }
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // Same rule as the app's own screens: no screenshots of money data in release builds.
                (if (BuildConfig.DEBUG) 0 else WindowManager.LayoutParams.FLAG_SECURE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            // Draw edge to edge, under the status and navigation bars; content pads itself with insets.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        gate.onAttaching(txn.id, started)
        val added = runCatching { wm.addView(root, lp) }
        if (added.isFailure) {
            // e.g. BadTokenException / permission revoked: One UI fails quietly for background processes.
            log("add-failed ${added.exceptionOrNull()?.javaClass?.simpleName}")
            gate.onAttachFailed(txn.id)
            owner.stop()
            return false
        }
        current = root to owner
        main.postDelayed({
            if (current?.first === root && !root.isAttachedToWindow) {
                gate.onAttachFailed(txn.id)
                removeCurrent(app)
                fallback(app, txn, "never-attached")
            }
        }, ATTACH_CHECK_MS)
        return true
    }

    private fun dismissNow(app: Context) {
        val id = gate.currentId
        if (current == null) return
        removeCurrent(app)
        gate.onDismissed(id)
        main.post { showNext(app) }
    }

    private fun removeCurrent(app: Context) {
        val (root, owner) = current ?: return
        current = null
        runCatching { app.getSystemService(WindowManager::class.java)?.removeView(root) }
        owner.stop()
    }

    private fun log(msg: String) { Log.i(TAG, msg) }

    private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun start() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            if (registry.currentState == Lifecycle.State.DESTROYED) return
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}

/**
 * When the SMS / notification behind the current ingest reached the app (elapsedRealtime), for pop-up timing logs.
 * Ingest runs synchronously on the receiving thread, so a thread-local carries it to [PaymentPopup.present].
 */
object PopupTiming {
    private val received = ThreadLocal<Long?>()
    fun begin(at: Long = SystemClock.elapsedRealtime()) = received.set(at)
    fun current(): Long? = received.get()
    fun end() = received.remove()
}
