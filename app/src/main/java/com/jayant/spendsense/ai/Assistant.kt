package com.jayant.spendsense.ai

import com.jayant.spendsense.data.Transaction

enum class ReplyMode { AI, OFFLINE, ERROR }

data class Reply(val text: String, val txns: List<Transaction> = emptyList(), val mode: ReplyMode = ReplyMode.AI)

/** Ties the online engine to the offline fallback. Blocking: call from Dispatchers.IO. */
class Assistant(
    private val engine: AssistantEngine,
    private val local: LocalAnswerer,
    private val hasKey: () -> Boolean,
) {
    fun reset() = engine.reset()

    fun ask(question: String): Reply {
        if (!hasKey()) return offline(question, prefix = null)
        return try {
            val r = engine.ask(question)
            Reply(r.text, r.txns, ReplyMode.AI)
        } catch (e: AssistantException) {
            when (e.kind) {
                ErrorKind.NO_KEY -> offline(question, null)
                ErrorKind.NETWORK -> offline(question, "Couldn't reach the AI, so here's an offline answer.")
                ErrorKind.AUTH -> offline(question, "Your AI API key was rejected. Check it in Settings. Offline answer:")
                ErrorKind.RATE_LIMIT -> Reply("The AI is rate limited right now. Give it a minute and try again.", mode = ReplyMode.ERROR)
                ErrorKind.OVERLOADED -> Reply("The AI service is busy. Please try again in a moment.", mode = ReplyMode.ERROR)
                ErrorKind.BAD_REQUEST -> Reply(
                    "The AI provider rejected the request" + (e.message?.takeIf { it.isNotBlank() && it != "Bad request" }?.let { ": $it" } ?: "") +
                        ". If it mentions the model, change it in Settings.",
                    mode = ReplyMode.ERROR,
                )
                else -> Reply("Something went wrong talking to the AI. Please try again.", mode = ReplyMode.ERROR)
            }
        } catch (e: Exception) {
            Reply("Something went wrong. Please try again.", mode = ReplyMode.ERROR)
        }
    }

    private fun offline(question: String, prefix: String?): Reply {
        val a = local.answer(question)
        val text = if (prefix == null) a.text else if (a.understood) "$prefix\n${a.text}" else prefix + "\n" + a.text
        return Reply(text, a.txns, ReplyMode.OFFLINE)
    }
}
