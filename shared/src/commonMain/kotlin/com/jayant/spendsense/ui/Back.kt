package com.jayant.spendsense.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Most recently registered enabled back handlers, top last. Drives the iOS edge swipe. */
internal object BackStack {
    private val handlers = mutableListOf<Pair<Any, () -> Unit>>()
    fun add(token: Any, onBack: () -> Unit) { remove(token); handlers += token to onBack }
    fun remove(token: Any) { handlers.removeAll { it.first === token } }

    /** Runs the top handler; false when nothing handles Back. */
    fun dispatch(): Boolean {
        val top = handlers.lastOrNull() ?: return false
        top.second()
        return true
    }
}

/**
 * Back handling for shared screens: Android system Back / predictive back via [BackHandler], and the iOS
 * left-edge swipe via [edgeSwipeBack] on the iOS root.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled) { onBack() }
    val current by rememberUpdatedState(onBack)
    val token = remember { Any() }
    DisposableEffect(enabled) {
        if (enabled) BackStack.add(token) { current() }
        onDispose { BackStack.remove(token) }
    }
}

/**
 * iOS swipe-back: a horizontal drag that starts within 24dp of the left edge and travels 72dp right fires the
 * top [AppBackHandler]. Observes the Initial pass without consuming, so content below still scrolls normally.
 */
fun Modifier.edgeSwipeBack(): Modifier = pointerInput(Unit) {
    val edge = 24.dp.toPx()
    val trigger = 72.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x > edge) return@awaitEachGesture
        var fired = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (!fired && dx > trigger && abs(dy) < dx * 0.6f) {
                fired = BackStack.dispatch()
                if (fired) event.changes.forEach { it.consume() }
            }
            if (!change.pressed) break
        }
    }
}
