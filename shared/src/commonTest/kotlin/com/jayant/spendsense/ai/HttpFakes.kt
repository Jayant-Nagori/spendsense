package com.jayant.spendsense.ai

import com.jayant.spendsense.json.JSONObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

/** What the fake server saw: final URL and headers (the JSON body is recorded alongside). */
class RecordedRequest(val url: String, private val headers: Headers) {
    fun header(name: String): String? = headers[name]
}

/** Ktor MockEngine that records requests and replays (status, body) pairs in order. */
class FakeHttp(private val replies: MutableList<Pair<Int, String>>) {
    val requests = mutableListOf<Pair<RecordedRequest, JSONObject>>()
    val client: HttpClient = HttpClient(
        MockEngine { req ->
            val body = (req.body as TextContent).text
            requests += RecordedRequest(req.url.toString(), req.headers) to JSONObject(body)
            val (code, text) = replies.removeAt(0)
            respond(text, HttpStatusCode.fromValue(code), headersOf("Content-Type", "application/json"))
        },
    ) { expectSuccess = false }
}

/** A client whose transport always fails, like a dropped connection. */
fun failingHttpClient(): HttpClient = HttpClient(MockEngine { throw kotlinx.io.IOException("down") }) { expectSuccess = false }
