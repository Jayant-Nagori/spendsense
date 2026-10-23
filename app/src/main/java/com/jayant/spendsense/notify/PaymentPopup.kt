package com.jayant.spendsense.notify

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
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
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.MoneyText
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.SpendCategories
import com.jayant.spendsense.ui.components.SpendChip
import com.jayant.spendsense.ui.components.SpendTextField
import com.jayant.spendsense.ui.components.SplitEditor
import com.jayant.spendsense.ui.components.formatTime
import com.jayant.spendsense.ui.components.label
import com.jayant.spendsense.ui.components.pressable
import com.jayant.spendsense.ui.components.rememberSplitState
import com.jayant.spendsense.ui.theme.SpendSenseTheme
import kotlinx.coroutines.launch

/**
 * Full-screen pop-up drawn over whatever app is open, right after a payment is detected. Needs "Display over other
 * apps". Payments that arrive while the phone is locked or another pop-up is open are queued and shown on the
 * next unlock (or when the current pop-up closes).
 */
object PaymentPopup {
    private val main = Handler(Looper.getMainLooper())
    private var current: Pair<FrameLayout, OverlayOwner>? = null

    private const val PREFS = "payment_popup"
    private const val KEY_QUEUE = "queue"
    private const val MAX_AGE_MS = 12L * 3600 * 1000

    fun canShow(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)

    private fun enabled(app: Context) = canShow(app) && Settings(app).paymentPopup

    private fun usable(app: Context): Boolean {
        val locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val awake = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        return awake && !locked
    }

    fun show(context: Context, txn: Transaction) {
        val app = context.applicationContext
        if (!enabled(app)) return
        main.post {
            if (current != null || !usable(app)) enqueue(app, txn.id)
            else runCatching { attach(app, txn) }
        }
    }

    /** Called when the user unlocks the phone: shows the oldest payment that is still waiting for an answer. */
    fun onUnlock(context: Context) {
        val app = context.applicationContext
        if (enabled(app)) main.post { showNext(app) }
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
    private fun pollQueue(app: Context): Long? {
        val q = queue(app)
        if (q.isEmpty()) return null
        val id = q.removeAt(0)
        saveQueue(app, q)
        return id
    }

    /** Main thread. Pops queued ids until one is still unanswered and recent, then shows it. One drain at a time. */
    private fun showNext(app: Context) {
        if (current != null || !usable(app) || queue(app).isEmpty()) return
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
                next?.let { t -> main.post { if (current == null && usable(app)) runCatching { attach(app, t) } else enqueue(app, t.id) } }
            } finally {
                draining.set(false)
            }
        }, "popup-next").start()
    }

    private fun attach(app: Context, txn: Transaction) {
        removeCurrent(app)
        val wm = app.getSystemService(WindowManager::class.java) ?: return
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
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.addView(ComposeView(app).apply {
            setContent { SpendSenseTheme(darkTheme = true) { PopupContent(txn, onClose = { dismissNow(app) }) } }
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
        wm.addView(root, lp)
        current = root to owner
    }

    private fun dismissNow(app: Context) {
        if (current == null) return
        removeCurrent(app)
        main.post { showNext(app) }
    }

    private fun removeCurrent(app: Context) {
        val (root, owner) = current ?: return
        current = null
        runCatching { app.getSystemService(WindowManager::class.java)?.removeView(root) }
        owner.stop()
    }

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
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}

@Composable
private fun PopupContent(txn: Transaction, onClose: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val gold = MaterialTheme.colorScheme.primary
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val enter = remember { Animatable(0f) }
    var note by remember { mutableStateOf(txn.note.orEmpty()) }
    var noteEdited by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(txn.category) }
    var splitting by remember { mutableStateOf(false) }
    val split = rememberSplitState(txn.amount, key = txn.id)

    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        enter.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }

    fun close() {
        scope.launch { enter.animateTo(0f, tween(200)); onClose() }
    }

    fun save(isSplit: Boolean, myShare: Double?) {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        val app = context.applicationContext
        val text = note.trim().take(200).ifBlank { null }
        val edited = noteEdited
        val cat = category
        Thread({
            val repo = TransactionRepository.get(app)
            repo.get(txn.id)?.let {
                // Keep a note typed in the notification reply meanwhile unless the user edited the field here.
                repo.update(it.copy(note = if (edited) text else it.note, category = cat, isSplit = isSplit, myShare = if (isSplit) myShare else null, reviewed = true))
            }
            NotificationManagerCompat.from(app).cancel(txn.id.toInt())
        }, "popup-save").start()
        close()
    }

    // Slow gold glow breathing behind the amount.
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        0.08f, 0.24f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "glowAlpha",
    )
    val lift = with(LocalDensity.current) { 140.dp.toPx() }
    val p = enter.value

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = p)).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(gold.copy(alpha = glow * p), Color.Transparent),
                    center = Offset(size.width * 0.32f, size.height * 0.26f), radius = size.width * 0.8f,
                ),
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { translationY = (1f - p) * lift; alpha = p }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 32.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(gold))
                Spacer(Modifier.size(10.dp))
                Text(
                    "PAYMENT DETECTED  ·  ${formatTime(txn.timestamp).uppercase()}",
                    style = MaterialTheme.typography.labelSmall, color = gold, modifier = Modifier.weight(1f),
                )
                Text(
                    "LATER", style = MaterialTheme.typography.labelMedium, color = grey,
                    modifier = Modifier.pressable(role = null) { close() }.padding(12.dp),
                )
            }

            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.CenterStart) {
                MoneyText(
                    txn.amount, animate = true,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 88.sp, fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface, symbolScale = 0.42f,
                )
            }

            Text(
                txn.counterparty?.takeIf { it.isNotBlank() }?.let { "to $it" } ?: "paid",
                style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(txn.bank, txn.account?.let { "ending " + it.takeLast(4) }, txn.paymentMode?.label()).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = grey, modifier = Modifier.padding(top = 6.dp))
            }

            Spacer(Modifier.height(36.dp))
            SpendTextField(note, { note = it.take(200); noteEdited = true }, "What was it for?")

            Spacer(Modifier.height(24.dp))
            Text("CATEGORY", style = MaterialTheme.typography.labelSmall, color = grey)
            Spacer(Modifier.height(10.dp))
            val cats = remember { listOf(txn.category) + SpendCategories.filter { it != txn.category } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c -> SpendChip(c, category == c, { category = c }) }
            }

            Spacer(Modifier.height(32.dp))
            if (splitting) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)) {
                    SplitEditor(split)
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Save my share", { save(true, split.myShare) }, Modifier.fillMaxWidth(), enabled = split.isValid)
                Spacer(Modifier.height(12.dp))
                GhostButton("It was all mine", { save(false, null) }, Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton("All mine", { save(false, null) }, Modifier.weight(1f))
                    GhostButton("I split it", { split.fullyMine = false; split.touched = true; splitting = true }, Modifier.weight(1f))
                }
            }
        }
    }
}
