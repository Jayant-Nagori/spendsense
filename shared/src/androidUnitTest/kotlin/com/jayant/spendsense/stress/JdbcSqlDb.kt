package com.jayant.spendsense.stress

import com.jayant.spendsense.data.DbSchema
import com.jayant.spendsense.data.SqlDb
import com.jayant.spendsense.data.SqlRow
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

/**
 * Test-only [SqlDb] over sqlite-jdbc with the app's schema. Binds every argument as a string, exactly like the
 * device's SqlCipherDb, so SQL behaviour (affinity, LIKE, ordering) matches production. Serialised like Android's
 * single SQLiteDatabase connection.
 */
class JdbcSqlDb(
    file: File = File.createTempFile("spendsense-test", ".db").apply { deleteOnExit() },
    schema: List<String> = DbSchema.CREATE,
) : SqlDb {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").apply {
        createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        createStatement().use { it.execute("PRAGMA synchronous=NORMAL") }
        schema.forEach { sql -> createStatement().use { it.execute(sql) } }
    }
    private val lock = Any()
    private var depth = 0

    private fun PreparedStatement.bind(args: List<Any?>) = args.forEachIndexed { i, a ->
        if (a == null) setNull(i + 1, Types.VARCHAR) else setString(i + 1, a.toString())
    }

    override fun query(sql: String, args: List<Any?>): List<SqlRow> = synchronized(lock) {
        conn.prepareStatement(sql).use { st ->
            st.bind(args)
            st.executeQuery().use { rs ->
                val md = rs.metaData
                buildList {
                    while (rs.next()) {
                        val m = LinkedHashMap<String, Any?>()
                        for (c in 1..md.columnCount) m[md.getColumnLabel(c)] = rs.getObject(c)?.let { v -> if (v is Int) v.toLong() else v }
                        add(SqlRow(m))
                    }
                }
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>) = synchronized(lock) {
        conn.prepareStatement(sql).use { it.bind(args); it.execute() }
        Unit
    }

    override fun insert(table: String, values: Map<String, Any?>): Long = synchronized(lock) {
        val cols = values.keys.joinToString(",")
        val qs = values.keys.joinToString(",") { "?" }
        conn.prepareStatement("INSERT INTO $table ($cols) VALUES ($qs)").use { st ->
            values.values.forEachIndexed { i, v ->
                when (v) {
                    null -> st.setNull(i + 1, Types.NULL)
                    is Long -> st.setLong(i + 1, v)
                    is Int -> st.setInt(i + 1, v)
                    is Double -> st.setDouble(i + 1, v)
                    is Boolean -> st.setInt(i + 1, if (v) 1 else 0)
                    else -> st.setString(i + 1, v.toString())
                }
            }
            st.executeUpdate()
        }
        conn.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    override fun update(table: String, values: Map<String, Any?>, where: String, args: List<Any?>): Int = synchronized(lock) {
        val set = values.keys.joinToString(",") { "$it = ?" }
        conn.prepareStatement("UPDATE $table SET $set WHERE $where").use { st ->
            st.bind(values.values.map { v -> if (v is Boolean) (if (v) 1 else 0) else v } + args)
            st.executeUpdate()
        }
    }

    override fun delete(table: String, where: String, args: List<Any?>): Int = synchronized(lock) {
        conn.prepareStatement("DELETE FROM $table WHERE $where").use { it.bind(args); it.executeUpdate() }
    }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        if (depth++ == 0) conn.autoCommit = false
        try {
            val r = block()
            if (depth == 1) conn.commit()
            r
        } catch (e: Throwable) {
            if (depth == 1) conn.rollback()
            throw e
        } finally {
            if (--depth == 0) conn.autoCommit = true
        }
    }

    fun count(table: String = "txn"): Int = query("SELECT COUNT(*) AS n FROM $table").first().int("n") ?: 0
}
