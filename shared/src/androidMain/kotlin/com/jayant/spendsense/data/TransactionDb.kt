package com.jayant.spendsense.data

import android.content.Context
import com.jayant.spendsense.security.DbPassphrase
import com.jayant.spendsense.security.DbKeyLostException
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.io.File
import java.io.RandomAccessFile

/**
 * SQLCipher (AES-256, per-page HMAC) encrypted store. The 256-bit key is random, generated on first
 * run and only ever persisted wrapped by a non-exportable Android Keystore key (see [DbPassphrase]).
 * Written as a raw key ("x'..'") so no KDF cost is paid per open.
 */
class TransactionDb private constructor(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext, DB_NAME, prepare(context.applicationContext), null,
        /* version = */ DbSchema.VERSION, /* minimumSupportedVersion = */ 0,
        // Never let SQLite "recover" from a corrupt / wrong-key open by deleting the file: report it instead.
        /* errorHandler = */ net.zetetic.database.DatabaseErrorHandler { },
        /* hook = */ null, /* enableWriteAheadLogging = */ true,
    ) {

    override fun onCreate(db: SQLiteDatabase) {
        DbSchema.CREATE.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        DbSchema.upgrade(oldVersion).forEach { db.execSQL(it) }
    }

    companion object {
        const val DB_NAME = "spendsense.db"
        private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        init { System.loadLibrary("sqlcipher") }

        @Volatile private var instance: TransactionDb? = null
        fun get(context: Context): TransactionDb {
            com.jayant.spendsense.security.AppIntegrity.requireGenuine()
            return instance ?: synchronized(this) { instance ?: TransactionDb(context.applicationContext).also { instance = it } }
        }

        /**
         * Closes and deletes the database (all journal/WAL files) and destroys its key material.
         * Repositories keep a reference to the old helper, so the caller must restart the process.
         */
        @Synchronized
        fun wipe(context: Context) {
            val app = context.applicationContext
            runCatching { instance?.close() }
            instance = null
            resetDatabaseHandle()
            app.deleteDatabase(DB_NAME)
            dbFiles(app.getDatabasePath(DB_NAME)).forEach { it.delete() }
            DbPassphrase.destroy(app)
        }

        /**
         * Recovery: closes the database, renames its files to `spendsense.db.unreadable-<time>` (kept, never deleted)
         * and, when the key itself is lost, forgets the old key material; otherwise the old wrapped key is kept under
         * `db_key_wrapped.unreadable-<time>`. The next open creates a fresh database with a new key.
         */
        @Synchronized
        fun setAside(context: Context, keyLost: Boolean) {
            val app = context.applicationContext
            runCatching { instance?.close() }
            instance = null
            resetDatabaseHandle()
            val db = app.getDatabasePath(DB_NAME)
            val stamp = System.currentTimeMillis()
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                val f = File(db.path + suffix)
                if (f.exists()) f.renameTo(File(db.parentFile, "$DB_NAME.unreadable-$stamp$suffix"))
            }
            // Key lost: its material is useless, remove it. Otherwise keep the old key next to the file (R3-03).
            if (keyLost) DbPassphrase.destroy(app) else DbPassphrase.setAside(app, stamp)
            TransactionRepository.resetForRecovery()
            com.jayant.spendsense.orders.OrderRepository.resetForRecovery()
            PortfolioRepository.resetForRecovery()
            AccountRepository.resetForRecovery()
            com.jayant.spendsense.splitwise.SplitwiseStore.resetForRecovery()
        }

        private fun dbFiles(db: File) = listOf(
            db, File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal"), File(db.path + ".enc"),
        )

        /** Returns the raw-key passphrase, first migrating a legacy plaintext database if one exists. */
        private fun prepare(app: Context): ByteArray {
            val file = app.getDatabasePath(DB_NAME)
            file.parentFile?.mkdirs()
            // An existing encrypted database must be opened with its own key; never mint a new one for it.
            val encryptedExists = file.exists() && file.length() > 0 && !isPlaintextDb(file)
            val pass = DbPassphrase.getOrCreate(app, allowCreate = !encryptedExists)
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
