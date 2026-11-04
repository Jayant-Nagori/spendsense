package com.jayant.spendsense.data

import com.jayant.spendsense.PlatformContext

/**
 * Non-secret key/value preferences (SharedPreferences on Android, NSUserDefaults suite on iOS).
 * Writes are applied asynchronously unless stated; [clear] with commit = true is durable on return.
 */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun contains(key: String): Boolean
    fun putString(key: String, value: String?)
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun remove(key: String)
    fun clear()
}

/** Opens (or creates) the preferences file [name]. */
expect fun keyValueStore(context: PlatformContext, name: String): KeyValueStore

/**
 * Secrets such as AI API keys. Android: AES-256-GCM blob wrapped by a non-exportable Keystore key, kept in the
 * given preferences file. iOS: Keychain generic password, kSecAttrAccessibleWhenUnlockedThisDeviceOnly.
 * Values are never logged.
 */
interface SecretStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

/** Secret storage scoped to the preferences file [prefsName] (Android) / keychain service (iOS). */
expect fun secretStore(context: PlatformContext, prefsName: String): SecretStore

/** A fully read result row. Column access by name; numbers are widened as SQLite stores them. */
class SqlRow(private val values: Map<String, Any?>) {
    fun isNull(col: String): Boolean = values[col] == null
    fun string(col: String): String? = values[col]?.let { if (it is ByteArray) it.decodeToString() else it.toString() }
    fun long(col: String): Long? = when (val v = values[col]) {
        null -> null
        is Long -> v
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong()
        else -> null
    }
    fun int(col: String): Int? = long(col)?.toInt()
    fun double(col: String): Double? = when (val v = values[col]) {
        null -> null
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
    fun has(col: String): Boolean = values.containsKey(col)
    fun columns(): Set<String> = values.keys
    fun raw(col: String): Any? = values[col]
}

/**
 * The thin SQL surface the repositories need. Android: the SQLCipher database (unchanged file and schema).
 * iOS: system SQLite with the file under NSFileProtectionComplete. All calls are blocking.
 */
interface SqlDb {
    fun query(sql: String, args: List<Any?> = emptyList()): List<SqlRow>
    fun execute(sql: String, args: List<Any?> = emptyList())
    fun insert(table: String, values: Map<String, Any?>): Long
    fun update(table: String, values: Map<String, Any?>, where: String, args: List<Any?>): Int
    fun delete(table: String, where: String, args: List<Any?>): Int
    fun <T> transaction(block: () -> T): T
}

/** The app's single database (opened once per process). */
expect fun openDatabase(context: PlatformContext): SqlDb

/**
 * Schema v6. Shared by Android's SQLiteOpenHelper and the iOS opener so both platforms keep identical tables.
 * Never rename or drop tables or columns: existing users' data lives in them. Every change is an additive migration
 * (ADD COLUMN with a default / CREATE ... IF NOT EXISTS), and a fresh install runs the same statements.
 */
object DbSchema {
    const val VERSION = 6

    val CREATE: List<String> get() = listOf(
        """
        CREATE TABLE txn (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            amount REAL NOT NULL,
            type TEXT NOT NULL,
            counterparty TEXT,
            account TEXT,
            bank TEXT,
            upi_ref TEXT,
            source TEXT NOT NULL,
            source_app TEXT,
            raw_text TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            category TEXT NOT NULL DEFAULT 'Other',
            is_split INTEGER NOT NULL DEFAULT 0,
            my_share REAL,
            reviewed INTEGER NOT NULL DEFAULT 0,
            note TEXT,
            payment_mode TEXT,
            refund_status TEXT NOT NULL DEFAULT 'NONE',
            refund_amount REAL,
            refund_of_id INTEGER,
            seen_sources TEXT,
            paid_for_others INTEGER NOT NULL DEFAULT 0,
            paid_for TEXT
        )
        """.trimIndent(),
        "CREATE INDEX idx_txn_ts ON txn(timestamp)",
        "CREATE INDEX idx_txn_ref ON txn(upi_ref)",
        """
        CREATE TABLE investment (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            asset_type TEXT NOT NULL,
            platform TEXT,
            invested_amount REAL NOT NULL,
            current_value REAL,
            units REAL,
            start_date INTEGER,
            maturity_date INTEGER,
            interest_rate REAL,
            is_sip INTEGER NOT NULL DEFAULT 0,
            sip_amount REAL,
            notes TEXT,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent(),
    ) + ORDER_DETAILS + V6

    /** Itemised orders fetched from connected food / grocery apps (v5). Encrypted with the rest of the database. */
    val ORDER_DETAILS: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS order_details (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            txn_id INTEGER UNIQUE,
            source TEXT NOT NULL,
            order_id TEXT NOT NULL,
            restaurant TEXT,
            items_json TEXT NOT NULL,
            total REAL NOT NULL,
            placed_at INTEGER NOT NULL,
            cancelled INTEGER NOT NULL DEFAULT 0,
            fetched_at INTEGER NOT NULL,
            UNIQUE(source, order_id)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_order_placed ON order_details(placed_at)",
    )

    /**
     * v6 (1.3): card/account split, transfers, "not a payment" rules, account nicknames, Splitwise.
     * Old rows get issuer/instrument/last4 from [TransactionRepository.backfillAccounts] (re-parsed, no new raw text).
     */
    val V6: List<String> = listOf(
        "ALTER TABLE txn ADD COLUMN issuer TEXT",
        "ALTER TABLE txn ADD COLUMN instrument TEXT",
        "ALTER TABLE txn ADD COLUMN last4 TEXT",
        "ALTER TABLE txn ADD COLUMN is_transfer INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE txn ADD COLUMN transfer_kind TEXT",
        "ALTER TABLE txn ADD COLUMN splitwise_id TEXT",
        "CREATE INDEX IF NOT EXISTS idx_txn_sw ON txn(splitwise_id)",
        // Sender + fingerprint of a digit-free message template; never the message itself.
        """
        CREATE TABLE IF NOT EXISTS ignore_rule (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sender TEXT NOT NULL,
            template_hash TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            UNIQUE(sender, template_hash)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS account_pref (
            account_key TEXT PRIMARY KEY,
            nickname TEXT,
            hidden INTEGER NOT NULL DEFAULT 0,
            merged_into TEXT
        )
        """.trimIndent(),
        // One row per Splitwise expense the user is part of; [role]: OWE (imported share), PAID (linked), SETTLE.
        """
        CREATE TABLE IF NOT EXISTS splitwise_expense (
            sw_id TEXT PRIMARY KEY,
            txn_id INTEGER,
            role TEXT NOT NULL,
            description TEXT,
            cost REAL NOT NULL,
            currency TEXT,
            my_paid REAL NOT NULL,
            my_owed REAL NOT NULL,
            payer TEXT,
            shares_json TEXT,
            category TEXT,
            group_name TEXT,
            date INTEGER NOT NULL,
            updated_at TEXT
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_sw_txn ON splitwise_expense(txn_id)",
        // Net balance per friend from the last sync: > 0 they owe the user, < 0 the user owes them.
        """
        CREATE TABLE IF NOT EXISTS splitwise_friend (
            friend_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            balance REAL NOT NULL,
            currency TEXT
        )
        """.trimIndent(),
    )

    /** Statements that bring a database at [oldVersion] up to [VERSION]. */
    fun upgrade(oldVersion: Int): List<String> = buildList {
        if (oldVersion < 2) {
            add("ALTER TABLE txn ADD COLUMN refund_status TEXT NOT NULL DEFAULT 'NONE'")
            add("ALTER TABLE txn ADD COLUMN refund_amount REAL")
            add("ALTER TABLE txn ADD COLUMN refund_of_id INTEGER")
        }
        if (oldVersion < 3) {
            // Which capture channels already reported this payment (SMS, app notification, email, statement).
            add("ALTER TABLE txn ADD COLUMN seen_sources TEXT")
        }
        if (oldVersion < 4) {
            // "Paid for someone else": whole amount owed back by [paid_for] (name, may be empty).
            add("ALTER TABLE txn ADD COLUMN paid_for_others INTEGER NOT NULL DEFAULT 0")
            add("ALTER TABLE txn ADD COLUMN paid_for TEXT")
        }
        if (oldVersion < 5) addAll(ORDER_DETAILS)
        if (oldVersion < 6) addAll(V6)
    }
}

internal fun Transaction.toValues(): Map<String, Any?> = linkedMapOf(
    "amount" to amount,
    "type" to type.name,
    "counterparty" to counterparty,
    "account" to account,
    "bank" to bank,
    "upi_ref" to upiRef,
    "source" to source.name,
    "source_app" to sourceApp,
    "raw_text" to rawText,
    "timestamp" to timestamp,
    "category" to category,
    "is_split" to if (isSplit) 1 else 0,
    "my_share" to myShare,
    "reviewed" to if (reviewed) 1 else 0,
    "note" to note,
    "payment_mode" to paymentMode?.name,
    "refund_status" to refundStatus.name,
    "refund_amount" to refundAmount,
    "refund_of_id" to refundOfId,
    "paid_for_others" to if (paidForOthers) 1 else 0,
    "paid_for" to paidFor,
    "issuer" to issuer,
    "instrument" to instrument?.name,
    "last4" to last4,
    "is_transfer" to if (isTransfer) 1 else 0,
    "transfer_kind" to transferKind?.name,
    "splitwise_id" to splitwiseId,
)

internal fun SqlRow.toTransaction(): Transaction = Transaction(
    id = long("id")!!,
    amount = double("amount")!!,
    type = TxnType.valueOf(string("type")!!),
    counterparty = string("counterparty"),
    account = string("account"),
    bank = string("bank"),
    upiRef = string("upi_ref"),
    source = runCatching { TxnSource.valueOf(string("source")!!) }.getOrDefault(TxnSource.MANUAL),
    sourceApp = string("source_app"),
    rawText = string("raw_text")!!,
    timestamp = long("timestamp")!!,
    category = string("category")!!,
    isSplit = int("is_split") == 1,
    myShare = double("my_share"),
    reviewed = int("reviewed") == 1,
    note = string("note"),
    paymentMode = string("payment_mode")?.let { runCatching { PaymentMode.valueOf(it) }.getOrNull() },
    refundStatus = string("refund_status")?.let { runCatching { RefundStatus.valueOf(it) }.getOrNull() } ?: RefundStatus.NONE,
    refundAmount = double("refund_amount"),
    refundOfId = long("refund_of_id"),
    paidForOthers = int("paid_for_others") == 1,
    paidFor = string("paid_for"),
    issuer = string("issuer"),
    instrument = string("instrument")?.let { runCatching { Instrument.valueOf(it) }.getOrNull() },
    last4 = string("last4"),
    isTransfer = int("is_transfer") == 1,
    transferKind = string("transfer_kind")?.let { runCatching { TransferKind.valueOf(it) }.getOrNull() },
    splitwiseId = string("splitwise_id"),
)

internal fun Investment.toValues(): Map<String, Any?> = linkedMapOf(
    "name" to name,
    "asset_type" to assetType.name,
    "platform" to platform,
    "invested_amount" to investedAmount,
    "current_value" to currentValue,
    "units" to units,
    "start_date" to startDate,
    "maturity_date" to maturityDate,
    "interest_rate" to interestRate,
    "is_sip" to if (isSip) 1 else 0,
    "sip_amount" to sipAmount,
    "notes" to notes,
    "updated_at" to updatedAt,
)

internal fun SqlRow.toInvestment(): Investment = Investment(
    id = long("id")!!,
    name = string("name")!!,
    assetType = runCatching { AssetType.valueOf(string("asset_type")!!) }.getOrDefault(AssetType.OTHER),
    platform = string("platform"),
    investedAmount = double("invested_amount")!!,
    currentValue = double("current_value"),
    units = double("units"),
    startDate = long("start_date"),
    maturityDate = long("maturity_date"),
    interestRate = double("interest_rate"),
    isSip = int("is_sip") == 1,
    sipAmount = double("sip_amount"),
    notes = string("notes"),
    updatedAt = long("updated_at")!!,
)
