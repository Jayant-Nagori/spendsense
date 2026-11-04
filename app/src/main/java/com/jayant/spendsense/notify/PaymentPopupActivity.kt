package com.jayant.spendsense.notify

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.security.SecureWindow
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.theme.SpendSenseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The payment pop-up as an activity: opened by the notification tap and by its full-screen intent (screen off,
 * locked, no overlay permission, or the overlay failed). Over the lock screen it shows nothing about the payment,
 * only "unlock to answer", and asks the system to dismiss the keyguard; the details appear once the phone is unlocked.
 */
class PaymentPopupActivity : FragmentActivity() {
    private var txnId by mutableLongStateOf(-1L)
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.jayant.spendsense.security.AppIntegrity.tampered) { finish(); return }
        SecureWindow.apply(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        load(intent)
        setContent {
            SpendSenseTheme(darkTheme = true) {
                if (!unlocked) LockedPrompt(onUnlock = ::requestUnlock, onLater = ::finish)
                // Same app-lock rule as the split dialog (R4-08).
                else com.jayant.spendsense.security.AppLockGate(this) { PopupFor(txnId) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshLock()
    }

    private fun load(intent: Intent?) {
        txnId = intent?.getLongExtra(SplitActivity.EXTRA_TXN_ID, -1L) ?: -1L
        if (txnId <= 0) { finish(); return }
        // The overlay already shows this payment (e.g. the user tapped the heads-up): nothing more to do.
        if (!PaymentPopup.claimForActivity(this, txnId)) { finish(); return }
        refreshLock()
        if (!unlocked) requestUnlock()
    }

    private fun refreshLock() {
        unlocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
    }

    private fun requestUnlock() {
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { unlocked = true }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Next queued payment, if any.
        if (isFinishing && !com.jayant.spendsense.security.AppIntegrity.tampered) PaymentPopup.onUnlock(this)
    }

    @Composable
    private fun PopupFor(id: Long) {
        var txn by androidx.compose.runtime.remember(id) { mutableStateOf<Transaction?>(null) }
        LaunchedEffect(id) {
            val t = withContext(Dispatchers.IO) { runCatching { TransactionRepository.get(applicationContext).get(id) }.getOrNull() }
            if (t == null || t.type != TxnType.DEBIT || t.reviewed) finish() else txn = t
        }
        txn?.let { PaymentPopupContent(it, onClose = { finish() }) }
    }
}

/** Shown over the lock screen, and as the overlay while app lock is on: no amount, payee or bank, just a way to unlock. */
@Composable
internal fun LockedPrompt(onUnlock: () -> Unit, onLater: () -> Unit) {
    val gold = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(gold))
                Spacer(Modifier.size(10.dp))
                Text("PAYMENT DETECTED", style = MaterialTheme.typography.labelSmall, color = gold)
            }
            Text("Unlock to say what it was for", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Unlock", onUnlock, Modifier.weight(1f))
                GhostButton("Later", onLater, Modifier.weight(1f))
            }
        }
    }
}
