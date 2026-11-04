package com.jayant.spendsense.data

/**
 * Display names for payees. Banks often only give the UPI ID ("chai@okaxis"); show "Chai" and keep the ID for the
 * meta line. Numeric or generic handles (9876543210@ybl, paytmqr…@paytm) stay as they are.
 */
object Payee {
    private val VPA = Regex("^([A-Za-z0-9._\\-]{2,})@([A-Za-z][A-Za-z0-9.]*)$")
    private val JUNK = setOf("upi", "pay", "paytm", "paytmqr", "gpay", "phonepe", "bharatpe", "qr", "merchant", "mab", "ibz", "vyapar", "ok", "ybl", "axl", "bhim", "q")

    fun isVpa(s: String?): Boolean = s != null && VPA.matches(s.trim())

    /** "chai@okaxis" → "Chai", "rahul.sharma_1@okicici" → "Rahul Sharma"; null when nothing readable is left. */
    fun nameFromVpa(vpa: String): String? {
        val local = VPA.matchEntire(vpa.trim())?.groupValues?.get(1) ?: return null
        val words = local.split('.', '_', '-')
            .map { it.trimEnd { c -> c.isDigit() } }
            .filter { w -> w.length >= 2 && w.any(Char::isLetter) && w.lowercase() !in JUNK && JUNK.none { j -> j.length > 3 && w.lowercase().startsWith(j) } }
        if (words.isEmpty()) return null
        return words.take(3).joinToString(" ") { it.lowercase().replaceFirstChar(Char::titlecase) }
    }

    /** What to call the counterparty in headlines; [raw] itself when it is already a name. */
    fun display(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (isVpa(s)) nameFromVpa(s) ?: s else s
    }

    /** The UPI ID to show in a meta line, only when [display] replaced it with a name. */
    fun vpaForMeta(raw: String?): String? = raw?.trim()?.takeIf { isVpa(it) && nameFromVpa(it) != null }?.lowercase()
}
