package com.jayant.spendsense.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.jayant.spendsense.security.DbPassphrase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.io.File
import java.io.RandomAccessFile

/**
 * SQLCipher (AES-256, per-page HMAC) encrypted store. The 256-bit key is random, generated on first
 * run and only ever persisted wrapped by a non-exportable Android Keystore key (see [DbPassphrase]).
 * Written as a raw key ("x'..'") so no KDF cost is paid per open.
 */
internal class TransactionDb(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext, DB_NAME, prepare(context.applicationContext), null,
        /* version = */ 1, /* minimumSupportedVersion = */ 0,
        /* errorHandler = */ null, /* hook = */ null, /* enableWriteAheadLogging = */ true,
    ) {

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
        const val DB_NAME = "spendsense.db"
        private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        init { System.loadLibrary("sqlcipher") }

        @Volatile private var instance: TransactionDb? = null
        fun get(context: Context): TransactionDb =
            instance ?: synchronized(this) { instance ?: TransactionDb(context.applicationContext).also { instance = it } }

        /**
         * Closes and deletes the database (all journal/WAL files) and destroys its key material.
         * Repositories keep a reference to the old helper, so the caller must restart the process.
         */
        @Synchronized
        fun wipe(context: Context) {
            val app = context.applicationContext
            runCatching { instance?.close() }
            instance = null
            app.deleteDatabase(DB_NAME)
            dbFiles(app.getDatabasePath(DB_NAME)).forEach { it.delete() }
            DbPassphrase.destroy(app)
        }

        private fun dbFiles(db: File) = listOf(
            db, File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal"), File(db.path + ".enc"),
        )

        /** Returns the raw-key passphrase, first migrating a legacy plaintext database if one exists. */
        private fun prepare(app: Context): ByteArray {
            val pass = DbPassphrase.getOrCreate(app)
            val file = app.getDatabasePath(DB_NAME)
            file.parentFile?.mkdirs()
            if (isPlaintextDb(file)) migratePlaintext(file, pass)
            return pass
        }

        private fun isPlaintextDb(file: File): Boolean {
            if (!file.exists() || file.length() < PLAIN_HEADER.size) return false
            val header = ByteArray(PLAIN_HEADER.size)
            file.inputStream().use { it.read(header) }
            return header.contentEquals(PLAIN_HEADER)
        }

        private fun migratePlaintext(file: File, pass: ByteArray) {
            val tmp = File(file.path + ".enc")
            tmp.delete()
            val ok = runCatching {
                val db = SQLiteDatabase.openDatabase(file.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, null, null)
                try {
                    val version = db.version
                    db.rawExecSQL("ATTACH DATABASE '${tmp.path}' AS encrypted KEY \"${String(pass, Charsets.US_ASCII)}\"")
                    db.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use { it.moveToFirst() }
                    db.rawExecSQL("PRAGMA encrypted.user_version = $version")
                    db.rawExecSQL("DETACH DATABASE encrypted")
                } finally {
                    db.close()
                }
            }.isSuccess
            // Never leave plaintext financial data behind, even if the export failed.
            dbFiles(file).filter { it != tmp }.forEach { shred(it) }
            if (ok) tmp.renameTo(file) else tmp.delete()
        }

        private fun shred(f: File) {
            if (!f.exists()) return
            runCatching {
                RandomAccessFile(f, "rw").use { raf ->
                    val zeros = ByteArray(8192)
                    var left = raf.length()
                    raf.seek(0)
                    while (left > 0) { val n = minOf(left, zeros.size.toLong()).toInt(); raf.write(zeros, 0, n); left -= n }
                    raf.fd.sync()
                }
            }
            f.delete()
        }
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
