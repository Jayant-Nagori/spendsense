package com.jayant.spendsense.orders

import com.jayant.spendsense.AppScope
import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.ai.llmHttpEngine
import com.jayant.spendsense.currentTimeMillis
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.keyValueStore
import com.jayant.spendsense.data.secretStore
import com.jayant.spendsense.ioDispatcher
import com.jayant.spendsense.platform.Platform
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** What Settings › Connected apps shows for one provider. */
data class ProviderStatus(
    val connected: Boolean,
    val lastSync: Long = 0L,
    val message: String? = null,
    val busy: Boolean = false,
)

/**
 * App-level glue for order prefill: connection state, sync triggers (connect, app open, payment ingest with retries,
 * periodic job) and matching. Never logs order, transaction or token contents.
 */
object OrderSync {
    private const val PREFS = "orders"
    private const val DAY = 86_400_000L
    private val RETRY_DELAYS = listOf(0L, 30_000L, 120_000L, 600_000L)

    private val _status = MutableStateFlow<Map<OrderProvider, ProviderStatus>>(emptyMap())
    val status: StateFlow<Map<OrderProvider, ProviderStatus>> = _status

    private val syncLock = Mutex()

    private val http: HttpClient by lazy {
        HttpClient(llmHttpEngine()) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
                requestTimeoutMillis = 45_000
            }
        }
    }

    private class SecretVault(context: PlatformContext) : ConnectionVault {
        private val secrets = secretStore(context, PREFS)
        override fun load(provider: OrderProvider): Connection? = Connection.fromJson(runCatching { secrets.read("conn_${provider.name}") }.getOrNull())
        override fun save(provider: OrderProvider, connection: Connection?) = secrets.write("conn_${provider.name}", connection?.toJson())
    }

    // ---- debug fake server ------------------------------------------------------------------------------------

    private fun isDebug(): Boolean = com.jayant.spendsense.platform.DebugGate.enabled()

    /** Base URL of the local fake MCP/OAuth server, only in debug builds and only when switched on. */
    fun fakeBase(context: PlatformContext): String? {
        if (!isDebug()) return null
        val prefs = keyValueStore(context, PREFS)
        if (!prefs.getBoolean("use_fake", false)) return null
        return prefs.getString("fake_base", null)?.ifBlank { null } ?: Platform.services.defaultFakeOrderServer
    }

    fun fakeEnabled(context: PlatformContext): Boolean = fakeBase(context) != null

    /** Debug only. Switching servers forgets existing connections so real and fake tokens never mix. */
    fun setFake(context: PlatformContext, enabled: Boolean, base: String? = null) {
        if (!isDebug()) return
        val prefs = keyValueStore(context, PREFS)
        if (prefs.getBoolean("use_fake", false) != enabled) OrderProvider.entries.forEach { SecretVault(context).save(it, null) }
        prefs.putBoolean("use_fake", enabled)
        base?.let { prefs.putString("fake_base", it.trim()) }
        refreshStatus(context)
    }

    // ---- state ------------------------------------------------------------------------------------------------

    private fun connector(context: PlatformContext) = OrderConnector(http, SecretVault(context), fakeBase(context))

    fun refreshStatus(context: PlatformContext) {
        val prefs = keyValueStore(context, PREFS)
        val vault = SecretVault(context)
        _status.value = OrderProvider.entries.associateWith { p ->
            val old = _status.value[p]
            ProviderStatus(
                connected = vault.load(p) != null,
                lastSync = prefs.getLong("last_sync_${p.name}", 0L),
                message = old?.message,
                busy = old?.busy == true,
            )
        }
    }

    fun anyConnected(context: PlatformContext): Boolean {
        val vault = SecretVault(context)
        return OrderProvider.entries.any { vault.load(it) != null }
    }

    private fun update(p: OrderProvider, block: (ProviderStatus) -> ProviderStatus) {
        val cur = _status.value[p] ?: ProviderStatus(false)
        _status.value = _status.value + (p to block(cur))
    }

    // ---- actions ----------------------------------------------------------------------------------------------

    /** Settings › Connect. Signs in through the platform browser, then pulls the last 30 days. */
    suspend fun connect(context: PlatformContext, provider: OrderProvider) {
        val browser = Platform.services.authBrowser
        if (browser == null) {
            update(provider) { it.copy(message = "Sign-in isn't available on this device") }
            return
        }
        update(provider) { it.copy(busy = true, message = null) }
        try {
            withContext(ioDispatcher) { connector(context).connect(provider, browser) }
            refreshStatus(context)
            update(provider) { it.copy(message = "Connected. Fetching your recent orders…") }
            sync(context, provider, currentTimeMillis() - 30 * DAY)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OrderServiceException) {
            update(provider) { it.copy(message = if (e.kind == OrderServiceException.Kind.CANCELLED) null else friendly(provider, e)) }
        } catch (e: Exception) {
            update(provider) { it.copy(message = "Couldn't connect ${provider.displayName}. Try again.") }
        } finally {
            refreshStatus(context)
            update(provider) { it.copy(busy = false) }
            runCatching { Platform.services.onOrderConnectionsChanged() }
        }
    }

    suspend fun disconnect(context: PlatformContext, provider: OrderProvider) {
        update(provider) { it.copy(busy = true, message = null) }
        runCatching { withContext(ioDispatcher) { connector(context).disconnect(provider) } }
            .onFailure { if (it is CancellationException) throw it }
        keyValueStore(context, PREFS).remove("last_sync_${provider.name}")
        refreshStatus(context)
        update(provider) { it.copy(busy = false, message = null) }
        runCatching { Platform.services.onOrderConnectionsChanged() }
    }

    /** Fetches [provider]'s orders since [sinceMs] (default: a day before the last sync), stores and matches them. */
    suspend fun sync(context: PlatformContext, provider: OrderProvider, sinceMs: Long? = null): Int = syncLock.withLock {
        val prefs = keyValueStore(context, PREFS)
        val since = sinceMs ?: prefs.getLong("last_sync_${provider.name}", 0L).let { if (it == 0L) currentTimeMillis() - 30 * DAY else it - DAY }
        update(provider) { it.copy(busy = true) }
        try {
            val matched = withContext(ioDispatcher) {
                val orders = connector(context).fetchOrders(provider, since)
                OrderRepository.get(context).upsert(orders)
                prefs.putLong("last_sync_${provider.name}", currentTimeMillis())
                matchAndPrefill(context)
            }
            update(provider) { it.copy(message = null, lastSync = currentTimeMillis()) }
            matched
        } catch (e: CancellationException) {
            throw e
        } catch (e: OrderServiceException) {
            if (e.kind == OrderServiceException.Kind.AUTH) refreshStatus(context)
            update(provider) { it.copy(message = friendly(provider, e)) }
            0
        } catch (e: Exception) {
            update(provider) { it.copy(message = "Couldn't fetch orders. Will try again later.") }
            0
        } finally {
            update(provider) { it.copy(busy = false) }
        }
    }

    /** App open / periodic job: incremental sync of every connected provider (at most every 15 minutes). */
    suspend fun syncAll(context: PlatformContext, force: Boolean = false) {
        val prefs = keyValueStore(context, PREFS)
        val vault = SecretVault(context)
        for (p in OrderProvider.entries) {
            if (vault.load(p) == null) continue
            val last = prefs.getLong("last_sync_${p.name}", 0L)
            if (!force && currentTimeMillis() - last < 15 * 60_000L) continue
            sync(context, p)
        }
    }

    /** Fire-and-forget [syncAll] on app start. */
    fun onAppOpen(context: PlatformContext) {
        AppScope.launch(ioDispatcher) { runCatching { refreshStatus(context); syncAll(context) } }
    }

    /**
     * A live debit was stored. If it looks like a delivery-app payment and that app is connected, look for its
     * order now and again at +30 s, +2 min and +10 min (order history can lag the payment).
     */
    fun onPaymentIngested(context: PlatformContext, txn: Transaction) {
        if (txn.type != TxnType.DEBIT) return
        val provider = OrderProvider.forPayment(txn) ?: return
        if (runCatching { SecretVault(context).load(provider) }.getOrNull() == null) return
        AppScope.launch(ioDispatcher) {
            for (d in RETRY_DELAYS) {
                if (d > 0) delay(d)
                runCatching { sync(context, provider, txn.timestamp - OrderMatcher.WINDOW_MS) }
                if (OrderRepository.get(context).forTransaction(txn.id) != null) break
            }
        }
    }

    /** Links unmatched orders to payments from the last ~5 weeks and pre-fills them. Returns links made. Blocking. */
    fun matchAndPrefill(context: PlatformContext): Int {
        val orders = OrderRepository.get(context)
        val txns = TransactionRepository.get(context)
        val now = currentTimeMillis()
        val pending = orders.unmatched(now - 35 * DAY, now + DAY)
        if (pending.isEmpty()) return 0
        val candidates = txns.between(now - 35 * DAY - OrderMatcher.WINDOW_MS, now + DAY).filter { it.type == TxnType.DEBIT }
        val linked = candidates.filter { orders.forTransaction(it.id) != null }.map { it.id }.toSet()
        var made = 0
        for ((order, txn) in OrderMatcher.match(pending, candidates, linked)) {
            if (!orders.assign(order.id, txn.id)) continue
            val fresh = txns.get(txn.id) ?: continue
            txns.update(OrderPrefill.apply(fresh, order))
            made++
        }
        return made
    }

    private fun friendly(p: OrderProvider, e: OrderServiceException): String = when (e.kind) {
        OrderServiceException.Kind.REGISTRATION -> e.message ?: OrderConnector.notOpenYet(p).message!!
        OrderServiceException.Kind.NETWORK -> "Couldn't reach ${p.displayName}. Check your connection."
        OrderServiceException.Kind.AUTH -> "${p.displayName} sign-in expired. Connect again."
        OrderServiceException.Kind.NOT_SUPPORTED -> e.message ?: "${p.displayName} doesn't share order history yet."
        OrderServiceException.Kind.TOO_LARGE, OrderServiceException.Kind.PROTOCOL -> "${p.displayName} sent something unexpected. Try again later."
        OrderServiceException.Kind.CANCELLED -> "Sign-in was cancelled"
    }
}
