package com.jayant.spendsense.ui

import com.jayant.spendsense.AppScope
import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.orders.OrderProvider
import com.jayant.spendsense.orders.OrderSync
import com.jayant.spendsense.platform.Platform
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Debug builds only: `spendsense://debug/...` links drive QA screenshots without touching the screen
 * (adb `am start -d` / `simctl openurl`). Release builds ignore them.
 *  - debug/open?to=home|settings|connected|txn&id=N
 *  - debug/fake?on=1[&base=http://…]
 *  - debug/connect?p=SWIGGY | debug/sync?p=SWIGGY
 */
object DebugLinks {
    /** Navigation request for [AppShell]: "home", "settings", "connected", "txn:<id>". Cleared once handled. */
    val nav = MutableStateFlow<String?>(null)

    fun handle(context: PlatformContext, link: String) {
        if (!com.jayant.spendsense.platform.DebugGate.enabled()) return
        val u = runCatching { Url(link) }.getOrNull() ?: return
        if (u.protocol.name != "spendsense" || u.host != "debug") return
        val q = u.parameters
        when (u.encodedPath.trim('/')) {
            "open" -> nav.value = when (val to = q["to"]) {
                "txn" -> q["id"]?.toLongOrNull()?.let { "txn:$it" }
                else -> to
            }
            "fake" -> OrderSync.setFake(context, q["on"] == "1", q["base"])
            "connect" -> OrderProvider.fromName(q["p"])?.let { p -> AppScope.launch { OrderSync.connect(context, p) } }
            "sync" -> OrderProvider.fromName(q["p"])?.let { p -> AppScope.launch { OrderSync.sync(context, p) } }
        }
    }
}
