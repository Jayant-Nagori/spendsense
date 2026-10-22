package com.jayant.spendsense.ingest

import android.content.Context

// OWNER: ingestion agent.
object SmsBackfill {
    /** Blocking. Imports historical bank SMS from the inbox (no split prompts). Returns rows inserted. */
    fun run(context: Context, sinceMillis: Long): Int = 0
}
