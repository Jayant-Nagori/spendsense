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
}
