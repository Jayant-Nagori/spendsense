package com.jayant.spendsense.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.ReentrantLock
import com.jayant.spendsense.withLock
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import kotlin.concurrent.Volatile

private class DefaultsStore(name: String) : KeyValueStore {
    private val d = NSUserDefaults(suiteName = "spendsense.$name")
    override fun getString(key: String, default: String?): String? = d.stringForKey(key) ?: default
    override fun getBoolean(key: String, default: Boolean) = if (d.objectForKey(key) == null) default else d.boolForKey(key)
    override fun getInt(key: String, default: Int) = if (d.objectForKey(key) == null) default else d.integerForKey(key).toInt()
    override fun getLong(key: String, default: Long) = if (d.objectForKey(key) == null) default else d.integerForKey(key)
    override fun contains(key: String) = d.objectForKey(key) != null
    override fun putString(key: String, value: String?) = if (value == null) d.removeObjectForKey(key) else d.setObject(value, key)
    override fun putBoolean(key: String, value: Boolean) = d.setBool(value, key)
    override fun putInt(key: String, value: Int) = d.setInteger(value.toLong(), key)
    override fun putLong(key: String, value: Long) = d.setInteger(value, key)
    override fun remove(key: String) = d.removeObjectForKey(key)
    override fun clear() {
        d.dictionaryRepresentation().keys.forEach { k -> (k as? String)?.let { d.removeObjectForKey(it) } }
        d.synchronize()
    }
}

actual fun keyValueStore(context: PlatformContext, name: String): KeyValueStore = DefaultsStore(name)

actual fun secretStore(context: PlatformContext, prefsName: String): SecretStore = KeychainSecretStore("com.jayant.spendsense.$prefsName")

/** Application Support/SpendSense, NSFileProtectionComplete and excluded from backups (Android: allowBackup=false). */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun protectedDataDir(): String {
    val fm = NSFileManager.defaultManager
    val base = fm.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)!!
    val dir = base.URLByAppendingPathComponent("SpendSense", isDirectory = true)!!
    fm.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete), error = null)
    fm.setAttributes(mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete), ofItemAtPath = dir.path!!, error = null)
    dir.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    return dir.path!!
}

/** SQLite (bundled build via androidx.sqlite: iOS's libsqlite3 lacks symbols the driver links). One connection, serialised by a re-entrant lock. */
internal class NativeDb(path: String) : SqlDb {
    private val lock = ReentrantLock()
    private val conn: SQLiteConnection = BundledSQLiteDriver().open(path)

    init {
        lock.withLock {
            exec("PRAGMA journal_mode = WAL")
            val version = query("PRAGMA user_version").firstOrNull()?.long("user_version")?.toInt() ?: 0
            if (version < DbSchema.VERSION) transaction {
                if (version == 0) DbSchema.CREATE.forEach { exec(it) } else DbSchema.upgrade(version).forEach { exec(it) }
                exec("PRAGMA user_version = ${DbSchema.VERSION}")
            }
        }
    }

    private fun exec(sql: String) {
        conn.prepare(sql).use { st -> while (st.step()) Unit }
    }

    private fun SQLiteStatement.bindAll(args: List<Any?>) {
        args.forEachIndexed { i, v ->
            val idx = i + 1
            when (v) {
                null -> bindNull(idx)
                is String -> bindText(idx, v)
                is Long -> bindLong(idx, v)
                is Int -> bindLong(idx, v.toLong())
                is Boolean -> bindLong(idx, if (v) 1 else 0)
                is Double -> bindDouble(idx, v)
                is Float -> bindDouble(idx, v.toDouble())
                is ByteArray -> bindBlob(idx, v)
                else -> bindText(idx, v.toString())
            }
        }
    }

    override fun query(sql: String, args: List<Any?>): List<SqlRow> = lock.withLock {
        conn.prepare(sql).use { st ->
            st.bindAll(args)
            val out = ArrayList<SqlRow>()
            while (st.step()) {
                val m = LinkedHashMap<String, Any?>()
                for (i in 0 until st.getColumnCount()) {
                    m[st.getColumnName(i)] = when (st.getColumnType(i)) {
                        SQLITE_INTEGER -> st.getLong(i)
                        SQLITE_FLOAT -> st.getDouble(i)
                        SQLITE_BLOB -> st.getBlob(i)
                        SQLITE_NULL -> null
                        else -> st.getText(i)
                    }
                }
                out += SqlRow(m)
            }
            out
        }
    }

    override fun execute(sql: String, args: List<Any?>) = lock.withLock {
        conn.prepare(sql).use { st -> st.bindAll(args); while (st.step()) Unit }
    }

    private fun changes(): Int = query("SELECT changes() AS n").first().int("n") ?: 0

    override fun insert(table: String, values: Map<String, Any?>): Long = lock.withLock {
        val cols = values.keys.toList()
        execute("INSERT INTO $table (${cols.joinToString(",")}) VALUES (${cols.joinToString(",") { "?" }})", cols.map { values[it] })
        query("SELECT last_insert_rowid() AS id").first().long("id") ?: -1L
    }

    override fun update(table: String, values: Map<String, Any?>, where: String, args: List<Any?>): Int = lock.withLock {
        val cols = values.keys.toList()
        execute("UPDATE $table SET ${cols.joinToString(",") { "$it = ?" }} WHERE $where", cols.map { values[it] } + args)
        changes()
    }

    override fun delete(table: String, where: String, args: List<Any?>): Int = lock.withLock {
        execute("DELETE FROM $table WHERE $where", args)
        changes()
    }

    private var depth = 0

    override fun <T> transaction(block: () -> T): T = lock.withLock {
        val outer = depth == 0
        if (outer) exec("BEGIN IMMEDIATE")
        depth++
        try {
            val r = block()
            depth--
            if (outer) exec("COMMIT")
            r
        } catch (e: Throwable) {
            depth--
            if (outer) runCatching { exec("ROLLBACK") }
            throw e
        }
    }

    private companion object {
        const val SQLITE_INTEGER = 1
        const val SQLITE_FLOAT = 2
        const val SQLITE_BLOB = 4
        const val SQLITE_NULL = 5
    }
}

@Volatile private var db: SqlDb? = null
private val dbLock = ReentrantLock()

actual fun openDatabase(context: PlatformContext): SqlDb {
    com.jayant.spendsense.security.AppIntegrity.requireGenuine()
    return db ?: dbLock.withLock { db ?: NativeDb(protectedDataDir() + "/spendsense.db").also { db = it } }
}

/**
 * Recovery: the database can't be opened. Renames its files to `spendsense.db.unreadable-<time>` (kept, never deleted)
 * so the next open starts an empty database. Keychain secrets are left alone.
 */
@OptIn(ExperimentalForeignApi::class)
fun setAsideIosDatabase() {
    dbLock.withLock {
        db = null
        val dir = protectedDataDir()
        val fm = NSFileManager.defaultManager
        val stamp = com.jayant.spendsense.currentTimeMillis()
        listOf("", "-wal", "-shm", "-journal").forEach { s ->
            val from = "$dir/spendsense.db$s"
            if (fm.fileExistsAtPath(from)) fm.moveItemAtPath(from, "$dir/spendsense.db.unreadable-$stamp$s", null)
        }
    }
    TransactionRepository.resetForRecovery()
    com.jayant.spendsense.orders.OrderRepository.resetForRecovery()
    PortfolioRepository.resetForRecovery()
    AccountRepository.resetForRecovery()
    com.jayant.spendsense.splitwise.SplitwiseStore.resetForRecovery()
}

@OptIn(ExperimentalForeignApi::class)
/** Deletes the database files and keychain secrets ("Wipe all data"). The app must be relaunched afterwards. */
fun wipeIosData() {
    dbLock.withLock {
        db = null
        val dir = protectedDataDir()
        val fm = NSFileManager.defaultManager
        listOf("", "-wal", "-shm", "-journal").forEach { fm.removeItemAtPath("$dir/spendsense.db$it", null) }
    }
    KeychainSecretStore.deleteAll()
}
