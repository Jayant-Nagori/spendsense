package com.jayant.spendsense.ingest

// OWNER: ingestion agent. Pure Kotlin.
object CategoryGuesser {
    fun guess(counterparty: String?, rawText: String): String = "Other"
}
