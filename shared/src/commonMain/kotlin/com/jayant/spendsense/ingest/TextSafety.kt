package com.jayant.spendsense.ingest

/**
 * Bounds the work regex-based parsers do on untrusted text (SMS, notifications, emails, statements, MCP results).
 * Caps the length and drops runs of combining marks: Java's `\b` re-scans a run of combining marks at every position,
 * so a message made of thousands of them turns every word-boundary regex quadratic (found by ParserFuzzTest).
 */
object TextSafety {
    fun bound(text: String, maxChars: Int): String {
        val s = if (text.length > maxChars) text.substring(0, maxChars) else text
        var run = 0
        var dirty = false
        for (c in s) {
            if (c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.ENCLOSING_MARK) {
                if (++run > 3) { dirty = true; break }
            } else run = 0
        }
        if (!dirty) return s
        val sb = StringBuilder(s.length)
        run = 0
        for (c in s) {
            if (c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.ENCLOSING_MARK) {
                if (++run <= 3) sb.append(c)
            } else { run = 0; sb.append(c) }
        }
        return sb.toString()
    }
}
