package com.jayant.spendsense.notify

import android.graphics.Color as AColor
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.security.AppLockGate
import com.jayant.spendsense.security.SecureWindow
import com.jayant.spendsense.ui.ThemePrefs
import com.jayant.spendsense.ui.isDark
import com.jayant.spendsense.ui.components.SplitContent
import com.jayant.spendsense.ui.theme.SpendSenseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dialog-style activity opened from the split notification. Extra: EXTRA_TXN_ID (Long). */
class SplitActivity : FragmentActivity() {
    companion object {
        const val EXTRA_TXN_ID = "txn_id"
        const val EXTRA_START_SPLIT = "start_split"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.jayant.spendsense.security.AppIntegrity.tampered) {
            startActivity(android.content.Intent(this, com.jayant.spendsense.ui.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        SecureWindow.apply(this)
        ThemePrefs.load(this)

        // Rounded surface in the app's own colours instead of the stock dialog chrome.
        val dark = ThemePrefs.isDark(this)
        val bg = GradientDrawable().apply {
            setColor(if (dark) AColor.parseColor("#121212") else AColor.WHITE)
            cornerRadius = 4f * resources.displayMetrics.density
            setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), if (dark) 0x1AFFFFFF else 0x14000000)
        }
        window.setBackgroundDrawable(bg)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val txnId = intent.getLongExtra(EXTRA_TXN_ID, -1L)
        val startAsSplit = intent.getBooleanExtra(EXTRA_START_SPLIT, false)
        setContent {
            SpendSenseTheme {
                AppLockGate(this) {
                    val context = LocalContext.current
                    var txn by remember { mutableStateOf<Transaction?>(null) }
                    LaunchedEffect(txnId) {
                        val t = withContext(Dispatchers.IO) { runCatching { TransactionRepository.get(context).get(txnId) }.getOrNull() }
                        if (t == null || t.type != TxnType.DEBIT) finish() else txn = t
                    }
                    txn?.let { t ->
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            var note by remember { mutableStateOf<String?>(null) }
                            SplitContent(t, saveLabel = "Save", startAsSplit = startAsSplit, onNote = { note = it }, onSave = { s ->
                                val app = applicationContext
                                val text = note
                                Thread {
                                    val repo = TransactionRepository.get(app)
                                    if (text != null) repo.setNote(t.id, text)
                                    repo.setAnswer(t.id, s.isSplit, s.myShare, s.forOthers, s.paidForName)
                                    NotificationManagerCompat.from(app).cancel(t.id.toInt())
                                }.start()
                                finish()
                            })
                        }
                    }
                }
            }
        }
    }
}
