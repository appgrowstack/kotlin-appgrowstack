package com.appgrowstack.stack

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppGrowStackTest {
    private val app = AppGrowStackApp(version = "3.3.0", build = "120", channel = AppGrowStackApp.Channel.Play, lang = "ja", region = "JP")

    private fun clock(seconds: Long) = Clock.fixed(Instant.ofEpochSecond(seconds), ZoneOffset.UTC)

    private fun stack(
        environment: AppGrowStackEnvironment = AppGrowStackEnvironment.Production,
        firstSeenAt: Instant? = null,
        storage: AppGrowStackStorage = MemoryAppGrowStackStorage(),
        http: AppGrowStackHttp = AppGrowStackHttp { AppGrowStackResponse(200, ByteArray(0)) },
        now: Long = 1_800_000_000,
    ) = AppGrowStack("pk_KUdMabcdefghijklmnopqrst", environment, "A1B2C3D4-0000-4000-8000-00000000000F", firstSeenAt, app, clock(now), storage = storage, http = http)

    @Test
    fun installIdIsLowercasedAndIsThePerson() {
        val s = stack()
        assertEquals("a1b2c3d4-0000-4000-8000-00000000000f", s.installId)
        assertEquals(s.installId, s.person())
    }

    @Test
    fun requestsCarryTheKeyAndTheClientHeader() {
        val sent = mutableListOf<AppGrowStackRequest>()
        val s = stack(AppGrowStackEnvironment.Staging, http = { sent += it; AppGrowStackResponse(200, ByteArray(0)) })
        s.send(s.request("GET", "/v1/support/articles", "kotlin-app-support", "0.2.0", mapOf("lang" to "zh-Hant")))
        val request = assertNotNull(sent.firstOrNull())
        assertEquals("GET", request.method)
        assertEquals("https://api-staging.appgrowstack.com/v1/support/articles?lang=zh-Hant", request.url)
        assertEquals("Bearer pk_KUdMabcdefghijklmnopqrst", request.headers["Authorization"])
        // contracts K2-5: <platform>/<app version>/<sdk>/<sdk version>, naming the SDK the connector serves
        assertEquals("android/3.3.0/kotlin-app-support/0.2.0", request.headers["AppGrowStack-Client"])
        val pattern = Regex("""^(ios|android)/\d+\.\d+(\.\d+)?/[a-z][a-z0-9-]{1,40}/\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""")
        assert(pattern.matches(s.clientHeader("kotlin-app-sessions", "0.1.0")))
    }

    @Test
    fun firstSeenIsTheEarliestAndIsKept() {
        val storage = MemoryAppGrowStackStorage()
        assertEquals(Instant.ofEpochSecond(1_800_000_000), stack(storage = storage).firstSeenAt)
        assertEquals(Instant.ofEpochSecond(1_800_000_000), stack(storage = storage, now = 1_900_000_000).firstSeenAt)
        assertEquals(Instant.ofEpochSecond(1_700_000_000), stack(firstSeenAt = Instant.ofEpochSecond(1_700_000_000), storage = storage).firstSeenAt)
        assertEquals(Instant.ofEpochSecond(1_700_000_000), storage.firstSeenAt)
        assertEquals(Instant.ofEpochSecond(1_700_000_000), stack(firstSeenAt = Instant.ofEpochSecond(1_750_000_000), storage = storage).firstSeenAt)
    }

    @Test
    fun localEnvironmentKeepsItsRoot() {
        val s = stack(AppGrowStackEnvironment.Local("http://10.0.2.2:8787/"))
        assertEquals("http://10.0.2.2:8787/v1/notify/opened", s.request("POST", "/v1/notify/opened", "kotlin-app-notifications", "0.1.0").url)
    }

    @Test
    fun activityKeepsTheNewestWithinTheLimits() {
        val activity = AppGrowStackActivity()
        repeat(25) { activity.record(AppGrowStackActivity.Event("e$it", Instant.ofEpochSecond(it.toLong()), mapOf("i" to AppGrowStackActivity.Value.Integer(it.toLong())))) }
        repeat(12) { activity.record(AppGrowStackActivity.ShownCampaign("c$it", 3, Instant.ofEpochSecond(it.toLong()))) }
        assertEquals(AppGrowStackActivity.EVENT_LIMIT, activity.events.size)
        assertEquals("e24", activity.events.first().name)
        assertEquals("e5", activity.events.last().name)
        assertEquals(AppGrowStackActivity.CAMPAIGN_LIMIT, activity.campaigns.size)
        assertEquals("c11", activity.campaigns.first().campaign)
    }
}
