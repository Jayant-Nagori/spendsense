package com.jayant.spendsense.splitwise

import com.jayant.spendsense.AppScope
import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.ai.llmHttpEngine
import com.jayant.spendsense.currentTimeMillis
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.keyValueStore
import com.jayant.spendsense.data.secretStore
import com.jayant.spendsense.ioDispatcher
import com.jayant.spendsense.platform.Platform
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Instant

data class SplitwiseStatus(
    val connected: Boolean = false,
    val userName: String? = null,
    val lastSync: Long = 0L,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * Splitwise connection + sync. The personal API key lives only in the platform secret store (Android Keystore-
 * wrapped, iOS Keychain WhenUnlockedThisDeviceOnly) and is never logged or shown. Sync runs on app open, "Sync now"
 * and the periodic background job; the first one pulls 90 days. Read-only unless the user taps a push.
 */
object SplitwiseSync {
    private const val PREFS = "splitwise"
    private const val SECRET_KEY = "api_key"
    private const val DAY = 86_400_000L
    private const val THROTTLE_MS = 10 * 60_000L

    private val _status = MutableStateFlow(SplitwiseStatus())
    val status: StateFlow<SplitwiseStatus> = _status
    private val lock = Mutex()

    /** Tests inject an in-process fake server here; production uses the platform engine with no redirects. */
    internal var httpOverride: HttpClient? = null

    private val http: HttpClient by lazy {
        HttpClient(llmHttpEngine()) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) { connectTimeoutMillis = 15_000; socketTimeoutMillis = 30_000; requestTimeoutMillis = 60_000 }
        }
    }

    private fun client() = httpOverride ?: http
    private fun prefs(c: PlatformContext) = keyValueStore(c, PREFS)
    private fun secrets(c: PlatformContext) = secretStore(c, PREFS)
    private fun key(c: PlatformContext): String? = runCatching { secrets(c).read(SECRET_KEY) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun isConnected(c: PlatformContext): Boolean = key(c) != null

    fun refreshStatus(c: PlatformContext) {
        val p = prefs(c)
        _status.value = _status.value.copy(connected = isConnected(c), userName = p.getString("user_name", null), lastSync = p.getLong("last_sync", 0L))
    }

    /** Checks the key with get_current_user, saves it, then imports the last 90 days. Returns an error to show, or null. */
    suspend fun connect(c: PlatformContext, rawKey: String): String? = lock.withLock {
        val k = rawKey.trim()
        if (!SplitwiseApi.looksLikeKey(k)) return@withLock "That doesn't look like a Splitwise API key."
        _status.value = _status.value.copy(busy = true, message = null)
        try {
            val me = SplitwiseApi(client(), k).currentUser()
            withContext(ioDispatcher) {
                // A different Splitwise account: the old account's imports and links must not mix with the new one's.
                val previous = prefs(c).getLong("user_id", -1L)
                if (previous > 0 && previous != me.id) SplitwiseStore.get(c).removeAll()
                secrets(c).write(SECRET_KEY, k)
                prefs(c).putLong("user_id", me.id)
                prefs(c).putString("user_name", me.name)
                prefs(c).remove("updated_after")
                prefs(c).putLong("last_sync", 0L)
            }
            refreshStatus(c)
            runCatching { Platform.services.onSplitwiseChanged() }
            null
        } catch (e: SplitwiseException) {
            e.message
        } finally {
            _status.value = _status.value.copy(busy = false)
        }
    }.also { if (it == null) sync(c, force = true) }

    /** Throttled unless [force]. Never throws; failures land in [status]. */
    suspend fun sync(c: PlatformContext, force: Boolean = false): SwImportStats? {
        val k = key(c) ?: return null
        if (!force && currentTimeMillis() - prefs(c).getLong("last_sync", 0L) < THROTTLE_MS) return null
        return lock.withLock {
            // Disconnect may have run while we waited for the lock (R4-03).
            if (key(c) != k) return@withLock null
            _status.value = _status.value.copy(busy = true, message = null)
            try {
                withContext(ioDispatcher) {
                    val p = prefs(c)
                    val me = p.getLong("user_id", -1L).takeIf { it > 0 } ?: SplitwiseApi(client(), k).currentUser().id.also { p.putLong("user_id", it) }
                    val api = SplitwiseApi(client(), k)
                    val updatedAfter = p.getString("updated_after", null)
                    val expenses = if (updatedAfter == null) {
                        api.expenses(datedAfter = Instant.fromEpochMilliseconds(currentTimeMillis() - 90 * DAY).toString())
                    } else api.expenses(updatedAfter = updatedAfter)
                    val store = SplitwiseStore.get(c)
                    val stats = store.apply(expenses, me)
                    // Expenses whose bank payment hadn't arrived yet are retried next time from the same point.
                    val newest = expenses.mapNotNull { it.updatedAt }.maxOrNull()
                    val next = when {
                        stats.unmatched > 0 -> updatedAfter ?: Instant.fromEpochMilliseconds(currentTimeMillis() - 90 * DAY).toString()
                        newest != null && (updatedAfter == null || newest > updatedAfter) -> newest
                        else -> updatedAfter ?: Instant.fromEpochMilliseconds(currentTimeMillis() - 90 * DAY).toString()
                    }
                    p.putString("updated_after", next)
                    runCatching { store.replaceFriends(api.friends()) }
                    p.putLong("last_sync", currentTimeMillis())
                    runCatching { TransactionRepository.get(c).notifyChanged() }
                    stats
                }.also { refreshStatus(c) }
            } catch (e: SplitwiseException) {
                _status.value = _status.value.copy(message = e.message)
                null
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                _status.value = _status.value.copy(message = "Sync failed. Try again later.")
                null
            } finally {
                _status.value = _status.value.copy(busy = false)
            }
        }
    }

    /** Deletes the key. [keepEntries]: imported expenses and links stay; otherwise they are removed / undone. */
    suspend fun disconnect(c: PlatformContext, keepEntries: Boolean) = lock.withLock {
        withContext(ioDispatcher) {
            secrets(c).write(SECRET_KEY, null)
            prefs(c).clear()
            val store = SplitwiseStore.get(c)
            if (keepEntries) store.forgetBalances() else store.removeAll()
            runCatching { TransactionRepository.get(c).notifyChanged() }
        }
        _status.value = SplitwiseStatus()
        runCatching { Platform.services.onSplitwiseChanged() }
    }

    fun onAppOpen(c: PlatformContext) {
        AppScope.launch(ioDispatcher) { runCatching { refreshStatus(c); sync(c) } }
    }

    // ---- push (off by default; every push is an explicit tap) --------------------------------------------------

    data class Target(val friends: List<SwFriend>, val groups: List<SwGroup>, val me: Long)

    suspend fun targets(c: PlatformContext): Target? {
        val k = key(c) ?: return null
        return withContext(ioDispatcher) {
            val api = SplitwiseApi(client(), k)
            Target(api.friends(), api.groups(), prefs(c).getLong("user_id", -1L))
        }
    }

    /** Adds one expense paid by the user. [owedBy] includes the user's own share. Returns an error to show, or null. */
    suspend fun push(c: PlatformContext, cost: Double, description: String, owedBy: Map<Long, Double>, groupId: Long?): String? {
        val k = key(c) ?: return "Connect Splitwise first."
        val me = prefs(c).getLong("user_id", -1L).takeIf { it > 0 } ?: return "Connect Splitwise first."
        val total = owedBy.values.sum()
        if (kotlin.math.abs(total - cost) > 0.05 || owedBy.values.any { it < 0 }) return "Shares must add up to the total."
        return try {
            withContext(ioDispatcher) { SplitwiseApi(client(), k).createExpense(me, cost, description, owedBy, groupId) }
            null
        } catch (e: SplitwiseException) {
            e.message
        }
    }
}
