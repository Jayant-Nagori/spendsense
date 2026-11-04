package com.jayant.spendsense.ingest

/** Pure Kotlin keyword-based categoriser. First matching rule wins. */
object CategoryGuesser {

    val CATEGORIES: List<String> = listOf(
        "Food & Dining", "Groceries", "Shopping", "Transport", "Bills & Utilities", "Rent", "Entertainment",
        "Health", "Travel", "Investment", "Transfers", "Cash", "Education", "Fees & Charges", "Other",
    )

    private val RULES: List<Pair<String, List<String>>> = listOf(
        "Transfers" to listOf("credit card bill", "towards your credit card", "credit card payment", "cc payment", "self transfer"),
        "Cash" to listOf(" atm", "cash withdrawal", "withdrawn"),
        "Investment" to listOf(
            "zerodha", "groww", "upstox", "kuvera", "cams", "kfintech", "kfin", "mutual fund", " sip", "nps trust",
            " ppf", "indian clearing", "iccl", "nsdl", "cdsl", "smallcase", "angel one", "angelone", "dhan",
            "paytm money", "etmoney", "et money", "wint", "tapinvest", "bondscanner", "golden pi", "indmoney", "coin by",
        ),
        "Food & Dining" to listOf(
            "swiggy", "zomato", "restaurant", "cafe", "café", "domino", "pizza", "mcdonald", "kfc", "burger",
            "starbucks", "chaayos", "haldiram", "bakery", "dhaba", "eatclub", "eatsure", "barbeque", "biryani", "kitchen", "veg",
        ),
        "Groceries" to listOf(
            "blinkit", "zepto", "bigbasket", "instamart", "dmart", "d mart", "avenue supermart", "jiomart",
            "reliance fresh", "reliance smart", "more retail", "spencer", "nature's basket", "kirana", "grocer",
            "supermarket", "dunzo", "milkbasket", "country delight",
        ),
        "Shopping" to listOf(
            "amazon", "amzn", "flipkart", "myntra", "ajio", "meesho", "nykaa", "tata cliq", "croma", "reliance digital",
            "decathlon", "ikea", "lifestyle", "westside", "zara", "h&m", "uniqlo", "shoppers stop", "pantaloons", "mall",
        ),
        "Transport" to listOf(
            "uber", "ola ", "olacabs", "rapido", "metro", "irctc", "redbus", "fastag", "petrol", "fuel", "hpcl", "bpcl",
            "indian oil", "iocl", "shell", "parking", "toll", "namma yatri", "blusmart",
        ),
        "Bills & Utilities" to listOf(
            "electricity", "bescom", "tata power", "adani elec", "msedcl", "airtel", "jio", "vodafone", "vi postpaid",
            "broadband", "act fibernet", "hathway", "indane", "bharat gas", "mahanagar gas", "water bill", "recharge",
            "dth", "tata play", "bill payment", "billdesk", "postpaid", "insurance", "lic ",
        ),
        "Rent" to listOf(" rent", "nobroker", "housing.com", "landlord", "society maintenance"),
        "Entertainment" to listOf(
            "netflix", "spotify", "bookmyshow", "hotstar", "prime video", "youtube", "pvr", "inox", "sonyliv",
            "zee5", "jiocinema", "apple.com", "google play", "steam", "playstation", "district",
        ),
        "Health" to listOf(
            "pharmacy", "apollo", "1mg", "pharmeasy", "netmeds", "hospital", "clinic", "medical", "medplus",
            "practo", "diagnostic", "cult.fit", "cultfit", "gym", "healthcare",
        ),
        "Travel" to listOf(
            "makemytrip", "goibibo", "cleartrip", "ixigo", "easemytrip", "indigo", "air india", "vistara",
            "akasa", "spicejet", "airbnb", "oyo", "hotel", "booking.com", "agoda", "yatra",
        ),
        "Education" to listOf("school", "college", "university", "udemy", "coursera", "byju", "unacademy", "tuition", "upgrad"),
        "Fees & Charges" to listOf("charges", "annual fee", "late fee", "penalty", "gst on", "interest charged", "finance charge"),
    )

    /** Short or common-word keywords that must be a whole word, not just the start of one ("mall" vs "mallika"). */
    private val WHOLE_WORDS = setOf("dhan", "wint", "cams", "kfin", "sip", "ppf", "ola", "lic", "dth", "mall", "toll", "gym", "atm", "rent", "veg")

    /** Each keyword must start a word, so "dhan" (the broker) no longer matches "Aradhana Veg". */
    private class Keyword(val word: String, val whole: Boolean) {
        private fun isWordChar(c: Char) = c in 'a'..'z' || c in '0'..'9'

        /** Plain substring scan (no regex): linear, and fast on Kotlin/Native where ~200 regexes per payment were slow. */
        fun containsIn(hay: String): Boolean {
            var from = 0
            while (true) {
                val i = hay.indexOf(word, from)
                if (i < 0) return false
                val startOk = i == 0 || !isWordChar(hay[i - 1])
                val end = i + word.length
                val endOk = !whole || end >= hay.length || !isWordChar(hay[end])
                if (startOk && endOk) return true
                from = i + 1
            }
        }
    }

    private val MATCHERS: List<Pair<String, List<Keyword>>> = RULES.map { (cat, words) ->
        cat to words.map { raw -> raw.trim().let { w -> Keyword(w, w in WHOLE_WORDS) } }
    }

    private val PERSON_VPA = Regex(
        "^[a-z][a-z._]*[0-9]{0,12}@(ok\\w+|ybl|ibl|axl|paytm|ptyes|ptaxis|pthdfc|ptsbi|apl|upi|kotak|icici|hdfcbank|sbi|yesbank|axisbank|idfcbank|fbl|jupiteraxis|naviaxis|superyes)$",
        RegexOption.IGNORE_CASE,
    )
    private val PERSON_NAME = Regex("^[A-Za-z]+( [A-Za-z]+){1,3}$")

    fun guess(counterparty: String?, rawText: String): String {
        val hay = listOfNotNull(counterparty?.let { TextSafety.bound(it, 200) }, TextSafety.bound(rawText, 1_000)).joinToString(" ").lowercase()
        for ((cat, words) in MATCHERS) if (words.any { it.containsIn(hay) }) return cat
        val cp = counterparty?.trim().orEmpty()
        if (cp.isNotEmpty() && (PERSON_VPA.matches(cp) || PERSON_NAME.matches(cp))) return "Transfers"
        return "Other"
    }
}
