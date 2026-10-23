package com.jayant.spendsense.ai

import android.content.Context
import com.jayant.spendsense.data.Investment
import com.jayant.spendsense.data.PortfolioRepository
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType

/** What the assistant needs from storage. Keeps tools / offline answerer free of android.* for tests. Blocking. */
interface AssistantData {
    fun search(
        fromMillis: Long? = null,
        toMillis: Long? = null,
        amount: Double? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null,
        text: String? = null,
        type: TxnType? = null,
        category: String? = null,
        limit: Int = 200,
    ): List<Transaction>

    fun pendingReview(): List<Transaction>
    fun investments(): List<Investment>

    /** Capture permissions that are off, in plain words ("bank SMS access"). Empty when everything is on. */
    fun captureIssues(): List<String> = emptyList()
}

class RepoAssistantData(context: Context) : AssistantData {
    private val txns = TransactionRepository.get(context)
    private val portfolio = PortfolioRepository.get(context)

    override fun search(
        fromMillis: Long?, toMillis: Long?, amount: Double?, minAmount: Double?, maxAmount: Double?,
        text: String?, type: TxnType?, category: String?, limit: Int,
    ): List<Transaction> = txns.search(fromMillis, toMillis, amount, minAmount, maxAmount, text, type, category, limit)

    override fun pendingReview(): List<Transaction> = txns.pendingReview()
    override fun investments(): List<Investment> = portfolio.all()

    private val app = context.applicationContext

    override fun captureIssues(): List<String> = buildList {
        val sms = listOf(android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.READ_SMS).all {
            androidx.core.content.ContextCompat.checkSelfPermission(app, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!sms) add("bank SMS access")
        if (!androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)) {
            add("GPay/PhonePe notification access")
        }
    }
}
