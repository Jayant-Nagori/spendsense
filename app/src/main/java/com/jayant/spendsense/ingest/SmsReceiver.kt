package com.jayant.spendsense.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.jayant.spendsense.data.TxnSource
import kotlin.concurrent.thread

/** Live bank/UPI/card SMS. Multipart messages are joined per sender before parsing. Never logs bodies. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bySender = messages.filterNotNull().groupBy { it.originatingAddress.orEmpty() }
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "sms-ingest") {
            try {
                for ((sender, parts) in bySender) {
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    val ts = parts.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
                    val parsed = TransactionParser.parseSms(sender, body, ts) ?: continue
                    ingestWithRetry { TransactionIngestor.ingest(app, parsed, TxnSource.SMS, sender, body, live = true) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
