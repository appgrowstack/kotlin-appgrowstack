package com.appgrowstack.stack.links

import com.appgrowstack.links.Attribution
import com.appgrowstack.links.AttributionReporter
import com.appgrowstack.links.AttributionRequest
import com.appgrowstack.links.InMemoryAttributionStorage
import com.appgrowstack.links.InstallReferrer
import com.appgrowstack.links.AttributionResolution
import com.appgrowstack.links.LinksSdk
import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackApp
import com.appgrowstack.stack.AppGrowStackEnvironment
import com.appgrowstack.stack.AppGrowStackHttp
import com.appgrowstack.stack.AppGrowStackRequest
import com.appgrowstack.stack.AppGrowStackResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class LinksAppGrowStackTest {
    private val app = AppGrowStackApp(version = "2.1.0", build = "21", channel = AppGrowStackApp.Channel.Play, lang = "ja")

    private fun stack(http: AppGrowStackHttp) =
        AppGrowStack("pk_KUdMabcdefghijklmnopqrst", AppGrowStackEnvironment.Production, "3F2A9C1E-0B7D-4E55-9A10-7C3D2B1A0F99", app = app, http = http)

    private val request = AttributionRequest(
        installId = "ignored", appVersion = "2.1.0", firstOpenAt = 1_800_000_000_000,
        playInstallReferrer = "utm_source=youtube&utm_campaign=v12",
    )

    @Test
    fun postsTheCluesWithTheStacksInstallId() = runTest {
        val sent = mutableListOf<AppGrowStackRequest>()
        val answer = """{"matched_by":"play_install_referrer","source":"youtube","medium":null,"campaign":"v12","code":null,"ad":null}"""
        val resolution = stack { sent += it; AppGrowStackResponse(200, answer.toByteArray()) }.attributionResolver().resolve(request)
        assertEquals(AttributionResolution.Decided(Attribution(Attribution.MatchedBy.PLAY_INSTALL_REFERRER, "youtube", campaign = "v12")), resolution)

        val http = sent.single()
        assertEquals("POST", http.method)
        assertEquals("https://api.appgrowstack.com/v1/links/attributions", http.url)
        assertEquals("Bearer pk_KUdMabcdefghijklmnopqrst", http.headers["Authorization"])
        assertEquals("application/json", http.headers["Content-Type"])
        assertEquals("android/2.1.0/kotlin-app-links/${LinksSdk.VERSION}", http.headers["AppGrowStack-Client"])
        val body = Json.parseToJsonElement(http.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(setOf("install_id", "platform", "app_version", "first_open_at", "play_install_referrer"), body.keys)
        assertEquals("3f2a9c1e-0b7d-4e55-9a10-7c3d2b1a0f99", body.getValue("install_id").jsonPrimitive.content)
        assertEquals("android", body.getValue("platform").jsonPrimitive.content)
    }

    @Test
    fun aBadBodyIsRejectedAndEverythingElseIsTriedLater() = runTest {
        for (status in listOf(400, 404, 413)) {
            assertEquals(AttributionResolution.Rejected, stack { AppGrowStackResponse(status, ByteArray(0)) }.attributionResolver().resolve(request))
        }
        // 401 and 403 are the key or the project, fixed on the server: the same body passes later
        for (status in listOf(401, 403, 429, 500, 503)) {
            assertEquals(AttributionResolution.RetryLater, stack { AppGrowStackResponse(status, ByteArray(0)) }.attributionResolver().resolve(request))
        }
        assertEquals(AttributionResolution.RetryLater, stack { AppGrowStackResponse(200, "not json".toByteArray()) }.attributionResolver().resolve(request))
        assertEquals(AttributionResolution.RetryLater, stack { throw IOException("offline") }.attributionResolver().resolve(request))
    }

    @Test
    fun worksWithTheReporterAndTheStacksFirstSeenDate() = runTest {
        val sent = mutableListOf<AppGrowStackRequest>()
        val answers = ArrayDeque(listOf(503 to "", 200 to """{"matched_by":"organic","source":"organic"}"""))
        val stack = AppGrowStack(
            "pk_KUdMabcdefghijklmnopqrst", AppGrowStackEnvironment.Production, "3F2A9C1E-0B7D-4E55-9A10-7C3D2B1A0F99",
            firstSeenAt = Instant.ofEpochMilli(1_700_000_000_000), app = app,
            http = { sent += it; val (status, body) = answers.removeFirst(); AppGrowStackResponse(status, body.toByteArray()) },
        )
        val storage = InMemoryAttributionStorage()
        fun reporter() = AttributionReporter(
            installId = stack.installId, appVersion = stack.app.version, resolver = stack.attributionResolver(), storage = storage,
            installReferrer = { InstallReferrer.Found("utm_source=google-play&utm_medium=organic") },
            firstOpenAt = stack.firstSeenAt.toEpochMilli(), urlGraceMillis = 1, sleep = {},
        )
        assertEquals(null, reporter().reportIfNeeded())
        assertEquals(Attribution.MatchedBy.ORGANIC, reporter().reportIfNeeded()?.matchedBy)
        assertEquals(2, sent.size)
        val body = Json.parseToJsonElement(sent.last().body!!.toString(Charsets.UTF_8)).jsonObject
        // Updated into the SDK: the first open is the stack's first-seen date, not today
        assertEquals("1700000000000", body.getValue("first_open_at").jsonPrimitive.content)
        assertEquals("utm_source=google-play&utm_medium=organic", body.getValue("play_install_referrer").jsonPrimitive.content)
    }
}
