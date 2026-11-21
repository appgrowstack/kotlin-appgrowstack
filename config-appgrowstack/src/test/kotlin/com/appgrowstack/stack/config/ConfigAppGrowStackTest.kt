package com.appgrowstack.stack.config

import com.appgrowstack.config.ConfigFetchResult
import com.appgrowstack.config.ConfigSdk
import com.appgrowstack.config.ConfigSetFetchFailure
import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackApp
import com.appgrowstack.stack.AppGrowStackEnvironment
import com.appgrowstack.stack.AppGrowStackHttp
import com.appgrowstack.stack.AppGrowStackRequest
import com.appgrowstack.stack.AppGrowStackResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigAppGrowStackTest {
    /** packages/contract の APPGROWSTACK_CLIENT_PATTERN と同じ */
    private val clientPattern = Regex("""^(ios|android)/\d+\.\d+(\.\d+)?/[a-z][a-z0-9-]{1,40}/\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""")

    private val app = AppGrowStackApp(version = "2.1.0", build = "21", channel = AppGrowStackApp.Channel.Play, lang = "ja", region = "JP")

    private fun stack(http: AppGrowStackHttp, clock: Clock = Clock.systemUTC(), firstSeenAt: Instant? = null) =
        AppGrowStack("pk_KUdMabcdefghijklmnopqrst", AppGrowStackEnvironment.Production, "A1", firstSeenAt = firstSeenAt, app = app, clock = clock, http = http)

    @Test
    fun fetchesTheSetWithTheETagAndTheClientHeader() = runTest {
        val sent = mutableListOf<AppGrowStackRequest>()
        val answers = ArrayDeque(listOf(
            AppGrowStackResponse(200, """{"version":4}""".toByteArray(), mapOf("etag" to "\"v4\"")),
            AppGrowStackResponse(304, ByteArray(0)),
        ))
        val source = stack({ sent += it; answers.removeFirst() }).configSource()
        assertEquals(ConfigFetchResult.Fetched("""{"version":4}""", "\"v4\""), source.fetch(null))
        assertEquals(ConfigFetchResult.NotModified, source.fetch("\"v4\""))

        val first = sent[0]
        assertEquals("GET", first.method)
        assertEquals("https://api.appgrowstack.com/v1/config/sets/pk_KUdMabcdefghijklmnopqrst", first.url)
        assertNull(first.headers["Authorization"])
        assertNull(first.headers["If-None-Match"])
        val client = first.headers.getValue("AppGrowStack-Client")
        assertEquals("android/2.1.0/kotlin-app-config/${ConfigSdk.VERSION}", client)
        assertTrue(clientPattern.matches(client))
        assertEquals("\"v4\"", sent[1].headers["If-None-Match"])
    }

    @Test
    fun otherStatusesThrow() = runTest {
        val source = stack({ AppGrowStackResponse(503, ByteArray(0)) }).configSource()
        assertEquals(503, assertFailsWith<ConfigSetFetchFailure> { source.fetch("\"v4\"") }.status)
    }

    @Test
    fun deviceFactsComeFromTheStack() {
        val now = Instant.parse("2026-12-10T00:00:00Z")
        val facts = stack({ error("no network") }, Clock.fixed(now, ZoneOffset.UTC), firstSeenAt = now.minusSeconds(3 * 86_400 + 60)).configFacts()
        assertEquals(JsonPrimitive("android"), facts["platform"])
        assertEquals(JsonPrimitive("play"), facts["channel"])
        assertEquals(JsonPrimitive("JP"), facts["region"])
        assertEquals(JsonPrimitive(3L), facts["days_since_first_seen"])
    }
}
