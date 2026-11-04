package com.jayant.spendsense.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Base64
import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.security.KeystoreCipher

private class PrefsStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun contains(key: String) = prefs.contains(key)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun clear() { prefs.edit().clear().commit() }
}

actual fun keyValueStore(context: PlatformContext, name: String): KeyValueStore = PrefsStore(context, name)

/** Same on-disk format as before KMP: Base64(KeystoreCipher blob under ALIAS_PREFS) in the prefs file. */
private class KeystoreSecretStore(context: Context, prefsName: String) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun read(key: String): String? {
        if (com.jayant.spendsense.security.AppIntegrity.tampered) return null
        val enc = prefs.getString(key, null) ?: return null
        return runCatching {
            String(KeystoreCipher.decrypt(KeystoreCipher.ALIAS_PREFS, Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun write(key: String, value: String?) {
        com.jayant.spendsense.security.AppIntegrity.requireGenuine()
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val blob = KeystoreCipher.encrypt(KeystoreCipher.ALIAS_PREFS, value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }
}

actual fun secretStore(context: PlatformContext, prefsName: String): SecretStore = KeystoreSecretStore(context, prefsName)

/**
 * [SqlDb] over the SQLCipher helper. Arguments are bound as strings, exactly as the pre-KMP repositories did
 * (SQLite's column affinity converts them), so query behaviour on existing databases is unchanged.
 */
private class SqlCipherDb(private val helper: TransactionDb) : SqlDb {
    override fun query(sql: String, args: List<Any?>): List<SqlRow> =
        helper.readableDatabase.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.toRow()) }
        }

    override fun execute(sql: String, args: List<Any?>) {
        if (args.isEmpty()) helper.writableDatabase.execSQL(sql) else helper.writableDatabase.execSQL(sql, args.toTypedArray())
    }

    override fun insert(table: String, values: Map<String, Any?>): Long =
        helper.writableDatabase.insert(table, null, values.toContentValues())

    override fun update(table: String, values: Map<String, Any?>, where: String, args: List<Any?>): Int =
        helper.writableDatabase.update(table, values.toContentValues(), where, args.map { it?.toString() }.toTypedArray())

    override fun delete(table: String, where: String, args: List<Any?>): Int =
        helper.writableDatabase.delete(table, where, args.map { it?.toString() }.toTypedArray())

    override fun <T> transaction(block: () -> T): T {
        val w = helper.writableDatabase
        w.beginTransaction()
        try {
            return block().also { w.setTransactionSuccessful() }
        } finally {
            w.endTransaction()
        }
    }

    private fun Cursor.toRow(): SqlRow {
        val m = LinkedHashMap<String, Any?>(columnCount)
        for (i in 0 until columnCount) {
            m[getColumnName(i)] = when (getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
                Cursor.FIELD_TYPE_BLOB -> getBlob(i)
                else -> getString(i)
            }
        }
        return SqlRow(m)
    }

    private fun Map<String, Any?>.toContentValues() = ContentValues().apply {
        for ((k, v) in this@toContentValues) when (v) {
            null -> putNull(k)
            is String -> put(k, v)
            is Long -> put(k, v)
            is Int -> put(k, v)
            is Double -> put(k, v)
            is Float -> put(k, v)
            is Boolean -> put(k, if (v) 1 else 0)
            is ByteArray -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}

@Volatile private var sqlDb: SqlDb? = null

actual fun openDatabase(context: PlatformContext): SqlDb {
    // A repackaged (non-genuine) install never gets a database handle.
    com.jayant.spendsense.security.AppIntegrity.requireGenuine()
    return sqlDb ?: synchronized(SqlCipherDb::class) { sqlDb ?: SqlCipherDb(TransactionDb.get(context)).also { sqlDb = it } }
}

/** Drops the cached handle after [TransactionDb.wipe] (the process restarts right after). */
internal fun resetDatabaseHandle() { sqlDb = null }
