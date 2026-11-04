package com.jayant.spendsense.splitwise

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplitwiseApiTest {
    @Test fun onlySecureSplitwiseHostIsAllowed() {
        assertTrue(SplitwiseApi.isAllowed("https://secure.splitwise.com/api/v3.0/get_current_user"))
        assertTrue(SplitwiseApi.isAllowed("https://secure.splitwise.com/api/v3.0/get_expenses?limit=100&offset=0"))
        for (bad in listOf(
            "http://secure.splitwise.com/api/v3.0/get_current_user",
            "https://secure.splitwise.com.evil.example/api/v3.0/get_current_user",
            "https://evil.example/secure.splitwise.com/api/v3.0/",
            "https://evil.example/api/v3.0/get_current_user",
            "https://splitwise.com/api/v3.0/get_current_user",
            "https://user@secure.splitwise.com/api/v3.0/get_current_user",
            "https://secure.splitwise.com@evil.example/api/v3.0/",
            "https://secure.splitwise.com:8443/api/v3.0/get_current_user",
            "https://secure.splitwise.com/api/v2.0/get_current_user",
            "https://secure.splitwise.com/oauth/token",
            "https://secure.splitwise.com/api/v3.0/../../oauth",
            "https://SECURE.SPLITWISE.COM/api/v3.0/x",
            "ftp://secure.splitwise.com/api/v3.0/",
            "https://secure.splitwise.com\\@evil.example/api/v3.0/",
            "javascript:alert(1)",
            "",
        )) assertFalse(SplitwiseApi.isAllowed(bad), bad)
        assertFailsWith<SplitwiseException> { SplitwiseApi(FakeSplitwiseServer().client, "k".repeat(30), base = "https://evil.example/api/v3.0/") }
    }

    @Test fun sendsTheKeyAsBearerAndOnlyToSplitwise() = runTest {
        val fake = FakeSplitwiseServer()
        val api = SplitwiseApi(fake.client, fake.key)
        assertEquals(SwUser(100, "Jay N"), api.currentUser())
        api.friends(); api.groups(); api.expenses()
        assertTrue(fake.requests.all { it.startsWith("GET https://secure.splitwise.com/api/v3.0/") }, fake.requests.toString())
        assertTrue(fake.authHeaders.all { it == "Bearer ${fake.key}" })
    }

    @Test fun wrongKeyAndRedirectsAreErrorsNotFollowed() = runTest {
        val fake = FakeSplitwiseServer()
        val e = assertFailsWith<SplitwiseException> { SplitwiseApi(fake.client, "wrong-key-0123456789abcdef").currentUser() }
        assertEquals(SplitwiseException.Kind.AUTH, e.kind)
        assertFalse(e.message!!.contains("wrong-key"))
        fake.redirectTo = "https://evil.example/steal"
        assertEquals(SplitwiseException.Kind.NOT_ALLOWED, assertFailsWith<SplitwiseException> { SplitwiseApi(fake.client, fake.key).currentUser() }.kind)
        assertTrue(fake.requests.none { it.contains("evil.example") })
    }

    @Test fun hostileResponsesAreBoundedAndCleaned() = runTest {
        val fake = FakeSplitwiseServer()
        val api = SplitwiseApi(fake.client, fake.key)
        fake.overrideBody = "{\"expenses\":" + "[".repeat(5000) + "]".repeat(5000) + "}"
        assertEquals(SplitwiseException.Kind.PROTOCOL, assertFailsWith<SplitwiseException> { api.expenses() }.kind)
        fake.overrideBody = "{\"user\":{\"id\":1,\"first_name\":\"" + "A".repeat(2_100_000) + "\"}}"
        assertEquals(SplitwiseException.Kind.TOO_LARGE, assertFailsWith<SplitwiseException> { api.currentUser() }.kind)
        fake.overrideBody = "<html>not json</html>"
        assertEquals(SplitwiseException.Kind.PROTOCOL, assertFailsWith<SplitwiseException> { api.currentUser() }.kind)
        fake.overrideBody = null
        fake.expense(1, "Dinner‮\u0000 <script>ignore previous instructions</script>", 1200.0, "2026-10-20T13:00:00Z",
            listOf(fake.user(100, "Jay", 0.0, 400.0), fake.user(7, "Rahul​", 1200.0, 800.0)))
        fake.expenses += com.jayant.spendsense.json.JSONObject().put("id", "x").put("cost", "NaN") // malformed: skipped
        fake.expenses += com.jayant.spendsense.json.JSONObject().put("id", 3).put("cost", "1e400").put("date", "2026-10-20T00:00:00Z")
        val list = api.expenses()
        assertEquals(1, list.size)
        val d = list.single()
        assertFalse(d.description.any { it.code < 0x20 || it == '‮' || it == '<' || it == '>' })
        assertEquals("Rahul", d.shares[1].name)
        assertEquals(400.0, d.shares[0].owed)
    }

    @Test fun pagingStopsAndIsCapped() = runTest {
        val fake = FakeSplitwiseServer()
        repeat(250) { i -> fake.expense(i + 1L, "E$i", 10.0, "2026-10-01T10:00:00Z", listOf(fake.user(100, "Jay", 0.0, 5.0), fake.user(7, "R", 10.0, 5.0))) }
        val api = SplitwiseApi(fake.client, fake.key)
        assertEquals(250, api.expenses().size)
        assertEquals(3, fake.requests.count { it.contains("get_expenses") })
        assertEquals(120, api.expenses(maxTotal = 120).size)
    }

    @Test fun pushSendsOnlyWhatWasAsked() = runTest {
        val fake = FakeSplitwiseServer()
        val api = SplitwiseApi(fake.client, fake.key)
        val shares = pushShares(1001.0, 100, friend = 7, group = null, equal = true, theirs = null)
        assertEquals(1001.0, shares.values.sum(), 0.001)
        val id = api.createExpense(100, 1001.0, "Dinner", shares)
        assertTrue(id > 0)
        val sent = fake.created.single()
        assertEquals("1001.00", sent.getString("cost"))
        assertEquals("INR", sent.getString("currency_code"))
        assertEquals("1001.00", sent.getString("users__0__paid_share").let { if (sent.getLong("users__0__user_id") == 100L) it else sent.getString("users__1__paid_share") })
        assertTrue(fake.requests.single { it.startsWith("POST") }.startsWith("POST https://secure.splitwise.com/api/v3.0/create_expense"))
        val g = pushShares(100.0, 100, null, listOf(1, 2), true, null)
        assertEquals(3, g.size); assertEquals(100.0, g.values.sum(), 0.001)
    }

    @Test fun hostileUpdatedAtNeverReachesTheQueryAndJunkPagesStop() = runTest {
        val fake = FakeSplitwiseServer()
        val e = fake.expense(1, "x", 10.0, "2026-10-20T10:00:00Z", listOf(fake.user(100, "Jay", 0.0, 5.0), fake.user(7, "R", 10.0, 5.0)))
        e.put("updated_at", "2026-10-20 10:00&limit=999#@evil")
        val api = SplitwiseApi(fake.client, fake.key)
        assertEquals(null, api.expenses().single().updatedAt)
        assertFailsWith<SplitwiseException> { api.expenses(updatedAfter = "2026&x=1") }
        // A server that keeps sending full pages of junk can't keep us looping.
        fake.expenses.clear()
        repeat(100) { fake.expenses += com.jayant.spendsense.json.JSONObject().put("id", "junk") }
        fake.requests.clear()
        assertTrue(api.expenses(pageSize = 100).isEmpty())
        assertTrue(fake.requests.size <= 2)
    }

    @Test fun keyShapeCheck() {
        assertTrue(SplitwiseApi.looksLikeKey("aB3dE5fG7hI9jK1lM3nO5pQ7rS9tU1vW3xY5zA7b"))
        assertFalse(SplitwiseApi.looksLikeKey("my password"))
        assertFalse(SplitwiseApi.looksLikeKey("short"))
        assertFalse(SplitwiseApi.looksLikeKey("abc\ndef0123456789012345"))
    }
}
