package com.jayant.spendsense.imports.email

import android.content.Context
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.ingest.TransactionIngestor
import java.util.Date
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

data class ImportCounts(val scanned: Int = 0, val parsed: Int = 0, val added: Int = 0, val duplicates: Int = 0) {
    fun summary(): String = "Scanned $scanned · added $added · $duplicates already recorded"
}

class EmailImportException(val failure: EmailFailure, cause: Throwable? = null) : Exception(failure.message, cause)

/**
 * Reads bank/card/UPI alert emails over IMAP (imap.gmail.com:993, TLS) with a Google App Password and feeds them to
 * [EmailAlertParser]. Blocking: call off the main thread. Never logs addresses, passwords or message content.
 */
object GmailImapImporter {
    private const val HOST = "imap.gmail.com"
    private const val TIMEOUT_MS = "20000"
    private const val MAX_MESSAGES = 2000
    private const val MAX_MESSAGE_BYTES = 1_500_000
    private const val MAX_TEXT_CHARS = 60_000
    private const val BATCH = 40

    fun interface Progress { fun onProgress(done: Int, total: Int) }

    private fun session(): Session {
        val p = Properties()
        p["mail.store.protocol"] = "imaps"
        p["mail.imaps.host"] = HOST
        p["mail.imaps.port"] = "993"
        p["mail.imaps.ssl.enable"] = "true"
        p["mail.imaps.ssl.checkserveridentity"] = "true"
        p["mail.imaps.ssl.protocols"] = "TLSv1.2 TLSv1.3"
        p["mail.imaps.connectiontimeout"] = TIMEOUT_MS
        p["mail.imaps.timeout"] = TIMEOUT_MS
        p["mail.imaps.writetimeout"] = TIMEOUT_MS
        p["mail.imaps.peek"] = "true"
        p["mail.imaps.auth.xoauth2.disable"] = "true"
        p["mail.imaps.auth.ntlm.disable"] = "true"
        return Session.getInstance(p)
    }

    private fun <T> withFolder(address: String, password: CharArray, block: (Folder) -> T): T {
        var store: Store? = null
        var folder: Folder? = null
        try {
            store = session().getStore("imaps")
            store.connect(HOST, 993, address.trim(), String(password))
            folder = openAllMail(store)
            return block(folder)
        } catch (e: EmailImportException) {
            throw e
        } catch (e: Exception) {
            throw EmailImportException(EmailFailure.classify(e), e)
        } finally {
            runCatching { folder?.takeIf { it.isOpen }?.close(false) }
            runCatching { store?.close() }
        }
    }

    private fun openAllMail(store: Store): Folder {
        runCatching { store.getFolder("[Gmail]/All Mail").also { it.open(Folder.READ_ONLY) } }.getOrNull()?.let { return it }
        // Localised Gmail: find the folder flagged \All.
        runCatching {
            store.defaultFolder.list("*").firstOrNull { f ->
                (f as? com.sun.mail.imap.IMAPFolder)?.attributes?.any { it.equals("\\All", ignoreCase = true) } == true
            }?.also { it.open(Folder.READ_ONLY) }
        }.getOrNull()?.let { return it }
        return store.getFolder("INBOX").also { it.open(Folder.READ_ONLY) }
    }

    private fun searchTerm(since: Date): SearchTerm {
        val matchers = EmailSenders.KNOWN.map { FromStringTerm(it.match) as SearchTerm } +
            EmailSenders.SUBJECT_WORDS.map { SubjectTerm(it) as SearchTerm }
        return AndTerm(ReceivedDateTerm(ComparisonTerm.GE, since), OrTerm(matchers.toTypedArray()))
    }

    /** Login + count of matching alert emails in the last 30 days. Throws [EmailImportException]. */
    fun testConnection(address: String, password: CharArray, nowMillis: Long = System.currentTimeMillis()): Int =
        withFolder(address, password) { f -> f.search(searchTerm(Date(nowMillis - 30L * 24 * 3600 * 1000))).size }

    fun import(
        context: Context,
        address: String,
        password: CharArray,
        sinceMillis: Long,
        progress: Progress? = null,
    ): ImportCounts = withFolder(address, password) { folder ->
        val all = folder.search(searchTerm(Date(sinceMillis)))
        // Newest messages are last; keep the most recent MAX_MESSAGES.
        val msgs = if (all.size > MAX_MESSAGES) all.copyOfRange(all.size - MAX_MESSAGES, all.size) else all
        var scanned = 0; var parsed = 0; var added = 0; var dup = 0
        val profile = FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) }
        for (chunk in msgs.toList().chunked(BATCH)) {
            val arr = chunk.toTypedArray()
            runCatching { folder.fetch(arr, profile) }
            for (m in arr) {
                scanned++
                val outcome = runCatching { processOne(context, m, sinceMillis) }.getOrNull()
                when (outcome) {
                    Outcome.ADDED -> { parsed++; added++ }
                    Outcome.DUPLICATE -> { parsed++; dup++ }
                    else -> Unit
                }
            }
            progress?.onProgress(scanned, msgs.size)
        }
        ImportCounts(scanned, parsed, added, dup)
    }

    private enum class Outcome { ADDED, DUPLICATE, SKIPPED }

    private fun processOne(context: Context, m: Message, sinceMillis: Long): Outcome {
        val ts = (m.sentDate ?: m.receivedDate)?.time ?: return Outcome.SKIPPED
        if (ts < sinceMillis - 3L * 24 * 3600 * 1000) return Outcome.SKIPPED
        if (m.size > MAX_MESSAGE_BYTES) return Outcome.SKIPPED
        val from = m.from?.firstOrNull()?.toString().orEmpty()
        val plain = StringBuilder(); val html = StringBuilder()
        collect(m, plain, html)
        val parsed = EmailAlertParser.parse(from, m.subject, plain.toString(), html.toString(), ts) ?: return Outcome.SKIPPED
        val raw = EmailAlertParser.clean(m.subject, plain.toString(), html.toString()).take(600)
        val stored = TransactionIngestor.ingest(
            context, parsed, TxnSource.MANUAL, "email:" + EmailSenders.domain(from).ifBlank { "unknown" }, raw, live = false,
        )
        return if (stored != null) Outcome.ADDED else Outcome.DUPLICATE
    }

    private fun collect(p: Part, plain: StringBuilder, html: StringBuilder) {
        if (plain.length + html.length > MAX_TEXT_CHARS) return
        if (Part.ATTACHMENT.equals(p.disposition, ignoreCase = true)) return
        when {
            p.isMimeType("text/plain") -> (runCatching { p.content }.getOrNull() as? String)?.let { plain.append(it).append('\n') }
            p.isMimeType("text/html") -> (runCatching { p.content }.getOrNull() as? String)?.let { html.append(it).append('\n') }
            p.isMimeType("multipart/*") -> {
                val mp = runCatching { p.content }.getOrNull() as? Multipart ?: return
                for (i in 0 until mp.count) collect(mp.getBodyPart(i), plain, html)
            }
        }
    }
}
