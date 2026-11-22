package com.appgrowstack.stack.campaign

import com.appgrowstack.campaign.BundledCampaignSetFetcher
import com.appgrowstack.campaign.CampaignContext
import com.appgrowstack.campaign.CampaignFetchResult
import com.appgrowstack.campaign.CampaignSdk
import com.appgrowstack.campaign.CampaignSetFetchFailure
import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackApp
import com.appgrowstack.stack.AppGrowStackEnvironment
import com.appgrowstack.stack.AppGrowStackHttp
import com.appgrowstack.stack.AppGrowStackRequest
import com.appgrowstack.stack.AppGrowStackResponse
import com.appgrowstack.stack.MemoryAppGrowStackStorage
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignAppGrowStackTest {
    /** packages/contract の APPGROWSTACK_CLIENT_PATTERN と同じ */
    private val clientPattern = Regex("""^(ios|android)/\d+\.\d+(\.\d+)?/[a-z][a-z0-9-]{1,40}/\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""")

    private val app = AppGrowStackApp(version = "2.1.0", build = "21", channel = AppGrowStackApp.Channel.Play, lang = "ja")

    private fun stack(http: AppGrowStackHttp) =
        AppGrowStack("pk_KUdMabcdefghijklmnopqrst", AppGrowStackEnvironment.Production, "a1", app = app, deviceId = "abc", http = http)

    private val bundledJson = """{"version":1,"campaigns":[]}"""
    private val bundled = BundledCampaignSetFetcher { bundledJson }

    @Test
    fun fetchesTheSetWithTheETagAndTheClientHeader() = runTest {
        val sent = mutableListOf<AppGrowStackRequest>()
        val answers = ArrayDeque(listOf(
            AppGrowStackResponse(200, """{"version":4}""".toByteArray(), mapOf("etag" to "\"v4\"")),
            AppGrowStackResponse(304, ByteArray(0)),
        ))
        val source = stack { sent += it; answers.removeFirst() }.campaignSource()
        assertEquals(CampaignFetchResult.Fetched("""{"version":4}""", "\"v4\""), source.fetch(null))
        assertEquals(CampaignFetchResult.NotModified, source.fetch("\"v4\""))

        val first = sent[0]
        assertEquals("GET", first.method)
        assertEquals("https://api.appgrowstack.com/v1/campaigns/sets/pk_KUdMabcdefghijklmnopqrst", first.url)
        assertNull(first.headers["Authorization"])
        // The set is the same for everyone; the device headers are only read on the server
        assertEquals(AppGrowStack.deviceHash("pk_KUdMabcdefghijklmnopqrst", "abc"), first.headers["AppGrowStack-Device"])
        assertEquals("a1", first.headers["AppGrowStack-Install"])
        assertNull(first.headers["If-None-Match"])
        val client = first.headers.getValue("AppGrowStack-Client")
        assertEquals("android/2.1.0/kotlin-app-campaign/${CampaignSdk.VERSION}", client)
        assertTrue(clientPattern.matches(client))
        assertEquals("\"v4\"", sent[1].headers["If-None-Match"])
    }

    @Test
    fun contextCarriesTheBuildAndTheEarlierFirstSeenDate() {
        val earlier = Instant.parse("2023-11-14T22:13:20Z")
        val zone = ZoneId.of("Asia/Tokyo")
        val stack = AppGrowStack(
            "pk_KUdMabcdefghijklmnopqrst", AppGrowStackEnvironment.Production, "a1",
            firstSeenAt = earlier,
            app = app.copy(region = "JP"),
            clock = Clock.fixed(Instant.parse("2026-12-10T03:00:00Z"), zone),
            isInternalDevice = { true },
            storage = MemoryAppGrowStackStorage(Instant.parse("2025-10-09T09:06:40Z")),
            http = { AppGrowStackResponse(500, ByteArray(0)) },
        )
        assertEquals(
            CampaignContext("2.1.0", "21", "play", "ja", "JP", zone, internalDevice = true, firstSeenAt = earlier.toEpochMilli()),
            stack.campaignContext,
        )
    }

    @Test
    fun otherStatusesThrow() = runTest {
        val source = stack { AppGrowStackResponse(503, ByteArray(0)) }.campaignSource()
        assertEquals(503, assertFailsWith<CampaignSetFetchFailure> { source.fetch("\"v4\"") }.status)
    }

    @Test
    fun theBundledSetIsUsedOnlyUntilTheFirstFetch() = runTest {
        val offline = stack { throw IOException("offline") }
        assertEquals(CampaignFetchResult.Fetched(bundledJson, bundled.etag), offline.campaignSource(bundled).fetch(null))
        // A set on hand (an ETag) is kept instead: the failure goes to the engine.
        assertFailsWith<IOException> { offline.campaignSource(bundled).fetch("\"v4\"") }
        assertFailsWith<IOException> { offline.campaignSource().fetch(null) }
    }
}
