package com.jayant.spendsense.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class TransactionDb(context: Context) :
    SQLiteOpenHelper(context, "spendsense.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
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
                payment_mode TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_txn_ts ON txn(timestamp)")
        db.execSQL("CREATE INDEX idx_txn_ref ON txn(upi_ref)")
        db.execSQL(
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
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        @Volatile private var instance: TransactionDb? = null
        fun get(context: Context): TransactionDb =
            instance ?: synchronized(this) { instance ?: TransactionDb(context.applicationContext).also { instance = it } }
    }
}

internal fun Transaction.toValues() = ContentValues().apply {
    put("amount", amount)
    put("type", type.name)
    put("counterparty", counterparty)
    put("account", account)
    put("bank", bank)
    put("upi_ref", upiRef)
    put("source", source.name)
    put("source_app", sourceApp)
    put("raw_text", rawText)
    put("timestamp", timestamp)
    put("category", category)
    put("is_split", if (isSplit) 1 else 0)
    if (myShare == null) putNull("my_share") else put("my_share", myShare)
    put("reviewed", if (reviewed) 1 else 0)
    put("note", note)
    put("payment_mode", paymentMode?.name)
}

internal fun Cursor.toTransaction(): Transaction {
    fun str(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getString(it) }
    return Transaction(
        id = getLong(getColumnIndexOrThrow("id")),
        amount = getDouble(getColumnIndexOrThrow("amount")),
        type = TxnType.valueOf(getString(getColumnIndexOrThrow("type"))),
        counterparty = str("counterparty"),
        account = str("account"),
        bank = str("bank"),
        upiRef = str("upi_ref"),
        source = TxnSource.valueOf(getString(getColumnIndexOrThrow("source"))),
        sourceApp = str("source_app"),
        rawText = getString(getColumnIndexOrThrow("raw_text")),
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
        category = getString(getColumnIndexOrThrow("category")),
        isSplit = getInt(getColumnIndexOrThrow("is_split")) == 1,
        myShare = getColumnIndexOrThrow("my_share").let { if (isNull(it)) null else getDouble(it) },
        reviewed = getInt(getColumnIndexOrThrow("reviewed")) == 1,
        note = str("note"),
        paymentMode = str("payment_mode")?.let { runCatching { PaymentMode.valueOf(it) }.getOrNull() },
    )
}

internal fun Investment.toValues() = ContentValues().apply {
    put("name", name)
    put("asset_type", assetType.name)
    put("platform", platform)
    put("invested_amount", investedAmount)
    if (currentValue == null) putNull("current_value") else put("current_value", currentValue)
    if (units == null) putNull("units") else put("units", units)
    if (startDate == null) putNull("start_date") else put("start_date", startDate)
    if (maturityDate == null) putNull("maturity_date") else put("maturity_date", maturityDate)
    if (interestRate == null) putNull("interest_rate") else put("interest_rate", interestRate)
    put("is_sip", if (isSip) 1 else 0)
    if (sipAmount == null) putNull("sip_amount") else put("sip_amount", sipAmount)
    put("notes", notes)
    put("updated_at", updatedAt)
}

internal fun Cursor.toInvestment(): Investment {
    fun str(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getString(it) }
    fun dbl(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getDouble(it) }
    fun lng(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getLong(it) }
    return Investment(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        assetType = runCatching { AssetType.valueOf(getString(getColumnIndexOrThrow("asset_type"))) }.getOrDefault(AssetType.OTHER),
        platform = str("platform"),
        investedAmount = getDouble(getColumnIndexOrThrow("invested_amount")),
        currentValue = dbl("current_value"),
        units = dbl("units"),
        startDate = lng("start_date"),
        maturityDate = lng("maturity_date"),
        interestRate = dbl("interest_rate"),
        isSip = getInt(getColumnIndexOrThrow("is_sip")) == 1,
        sipAmount = dbl("sip_amount"),
        notes = str("notes"),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )
}
