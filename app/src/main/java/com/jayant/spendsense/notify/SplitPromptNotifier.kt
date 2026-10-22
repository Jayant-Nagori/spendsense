package com.jayant.spendsense.notify

import android.content.Context
import com.jayant.spendsense.data.Transaction

// OWNER: UI agent.
object SplitPromptNotifier {
    fun createChannel(context: Context) = Unit
    fun notifyNewDebit(context: Context, txn: Transaction) = Unit
}
