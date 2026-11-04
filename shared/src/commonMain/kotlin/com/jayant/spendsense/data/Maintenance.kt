package com.jayant.spendsense.data

import com.jayant.spendsense.PlatformContext

/** One-time data upgrades run off the main thread at app open (both platforms). Never deletes anything. */
object Maintenance {
    private const val ACCOUNT_BACKFILL_VERSION = 1

    fun onAppOpen(context: PlatformContext) {
        val settings = Settings(context)
        if (settings.accountBackfillVersion < ACCOUNT_BACKFILL_VERSION) {
            runCatching { TransactionRepository.get(context).backfillAccounts() }
                .onSuccess { settings.accountBackfillVersion = ACCOUNT_BACKFILL_VERSION }
        }
    }
}
