package com.jayant.spendsense.imports.statement

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper

/** The PDF is encrypted. [wrongPassword] is true when a password was supplied but did not open it. */
class PasswordRequiredException(val wrongPassword: Boolean) : Exception(if (wrongPassword) "Wrong password" else "Password required")

/** The file could not be read as a statement (corrupt, unsupported, not a PDF). The message is safe to show. */
class StatementReadException(message: String) : Exception(message)

/**
 * Extracts text lines from a (possibly password-protected) PDF. Blocking: call from Dispatchers.IO.
 * Everything stays in memory: no temp files, no logging, and the password is never retained.
 */
object PdfStatementReader {
    @Volatile private var initialised = false

    private fun ensureInit(context: Context) {
        if (!initialised) synchronized(this) {
            if (!initialised) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialised = true
            }
        }
    }

    fun readLines(context: Context, uri: Uri, password: String? = null): List<String> {
        ensureInit(context)
        val input = context.contentResolver.openInputStream(uri) ?: throw StatementReadException("Could not open the file")
        val doc: PDDocument = try {
            input.use { PDDocument.load(CappedInputStream(it, MAX_PDF_BYTES), password.orEmpty()) }
        } catch (e: InvalidPasswordException) {
            throw PasswordRequiredException(wrongPassword = !password.isNullOrEmpty())
        } catch (e: OutOfMemoryError) {
            throw StatementReadException("This PDF is too large to read on this phone")
        } catch (e: Exception) {
            throw StatementReadException("This file does not look like a readable PDF")
        }
        return doc.use { d ->
            try {
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                splitLines(stripper.getText(d))
            } catch (e: OutOfMemoryError) {
                throw StatementReadException("This PDF is too large to read on this phone")
            } catch (e: Exception) {
                throw StatementReadException("Could not read text from this PDF")
            }
        }
    }

    internal fun splitLines(text: String): List<String> = text.split(Regex("\r\n|\r|\n")).map { it.trim() }.filter { it.isNotEmpty() }
}

/** Longest PDF statement we will read (real ones are a few MB). */
internal const val MAX_PDF_BYTES = 40L * 1024 * 1024

/** Fails with [StatementReadException] once more than [max] bytes were read, so a hostile stream can't exhaust memory. */
internal class CappedInputStream(private val inner: java.io.InputStream, private val max: Long) : java.io.FilterInputStream(inner) {
    private var count = 0L
    private fun add(n: Int): Int {
        if (n > 0) { count += n; if (count > max) throw StatementReadException("That file is too large to be a statement") }
        return n
    }
    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = add(super.read(b, off, len))
}
