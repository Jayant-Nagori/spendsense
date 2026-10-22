package com.jayant.spendsense.ingest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.jayant.spendsense.data.TxnSource

object SmsBackfill {
    /** Blocking. Imports historical bank SMS from the inbox (no split prompts). Returns rows inserted. */
    fun run(context: Context, sinceMillis: Long): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return 0
        var inserted = 0
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(sinceMillis.toString()),
                "${Telephony.Sms.DATE} ASC",
            )?.use { c ->
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (c.moveToNext()) {
                    val sender = c.getString(iAddr) ?: continue
                    val body = c.getString(iBody) ?: continue
                    val parsed = TransactionParser.parseSms(sender, body, c.getLong(iDate)) ?: continue
                    if (TransactionIngestor.ingest(context, parsed, TxnSource.SMS, sender, body, live = false) != null) inserted++
                }
            }
        }
        return inserted
    }
}
