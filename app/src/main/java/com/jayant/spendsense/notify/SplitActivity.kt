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
import com.jayant.spendsense.ui.components.SplitContent
import com.jayant.spendsense.ui.theme.SpendSenseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dialog-style activity opened from the split notification. Extra: EXTRA_TXN_ID (Long). */
class SplitActivity : FragmentActivity() {
    companion object { const val EXTRA_TXN_ID = "txn_id" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureWindow.apply(this)
        ThemePrefs.load(this)

        // Rounded surface in the app's own colours instead of the stock dialog chrome.
        val dark = ThemePrefs.isDark(this)
        val bg = GradientDrawable().apply {
            setColor(if (dark) AColor.parseColor("#14141C") else AColor.WHITE)
            cornerRadius = 28f * resources.displayMetrics.density
        }
        window.setBackgroundDrawable(bg)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val txnId = intent.getLongExtra(EXTRA_TXN_ID, -1L)
        setContent {
            SpendSenseTheme {
                AppLockGate(this) {
                    val context = LocalContext.current
                    var txn by remember { mutableStateOf<Transaction?>(null) }
                    LaunchedEffect(txnId) {
                        val t = withContext(Dispatchers.IO) { TransactionRepository.get(context).get(txnId) }
                        if (t == null || t.type != TxnType.DEBIT) finish() else txn = t
                    }
                    txn?.let { t ->
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            SplitContent(t, saveLabel = "Save", onSave = { isSplit, share ->
                                val app = applicationContext
                                Thread {
                                    TransactionRepository.get(app).setSplit(t.id, isSplit, share)
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
