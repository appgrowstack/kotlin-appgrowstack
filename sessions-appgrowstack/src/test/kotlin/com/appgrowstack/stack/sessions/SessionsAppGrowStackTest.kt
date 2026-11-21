package com.appgrowstack.stack.sessions

import com.appgrowstack.sessions.SessionBatch
import com.appgrowstack.sessions.SessionDevice
import com.appgrowstack.sessions.SessionDeviceInfo
import com.appgrowstack.sessions.SessionReplayMode
import com.appgrowstack.sessions.SessionScreen
import com.appgrowstack.sessions.SessionSinkReply
import com.appgrowstack.sessions.SessionsSdk
import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackApp
import com.appgrowstack.stack.AppGrowStackEnvironment
import com.appgrowstack.stack.AppGrowStackHttp
import com.appgrowstack.stack.AppGrowStackRequest
import com.appgrowstack.stack.AppGrowStackResponse
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionsAppGrowStackTest {
    private val app = AppGrowStackApp(version = "3.4.0", build = "340", channel = AppGrowStackApp.Channel.Play, lang = "ja")

    private fun stack(http: AppGrowStackHttp) =
        AppGrowStack("pk_KUdMabcdefghijklmnopqrst", AppGrowStackEnvironment.Staging, "a1b2c3d4-0000-4000-8000-00000000000f", app = app, http = http)

    private fun answering(status: Int, body: String = ""): Pair<MutableList<AppGrowStackRequest>, AppGrowStack> {
        val sent = mutableListOf<AppGrowStackRequest>()
        return sent to stack { sent += it; AppGrowStackResponse(status, body.toByteArray()) }
    }

    private val batch = SessionBatch(
        SessionDevice(
            sessionId = "ses_abcdefghijklmnopqrst",
            startedAt = 1_795_230_000_000,
            installId = "a1b2c3d4-0000-4000-8000-00000000000f",
            info = SessionDeviceInfo(
                appVersion = "3.4.0", build = "340", channel = "play", lang = "ja", region = "JP", timeZone = "Asia/Tokyo",
                osVersion = "16", deviceModel = "Pixel 9", screen = SessionScreen(412.0, 915.0, 2.625),
            ),
        ),
        seq = 0,
        events = emptyList(),
    )

    @Test
    fun theBatchGoesWithTheStacksInstallId() {
        val (sent, stack) = answering(200, """{"replay":"buffer"}""")
        val own = batch.copy(session = batch.session.copy(installId = "ffffffff-0000-4000-8000-000000000001"))
        stack.sessionSink().send(own)
        assertEquals(batch.jsonText(), sent.single().body!!.toString(Charsets.UTF_8))
    }

    @Test
    fun postsTheBatchAsItsJson() {
        val (sent, stack) = answering(200, """{"replay":"buffer"}""")
        assertEquals(SessionSinkReply.Accepted(SessionReplayMode.BUFFER, null), stack.sessionSink().send(batch))

        val request = sent.single()
        assertEquals("POST", request.method)
        assertEquals("https://api-staging.appgrowstack.com/v1/sessions/batches", request.url)
        assertEquals("Bearer pk_KUdMabcdefghijklmnopqrst", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("android/3.4.0/kotlin-app-sessions/${SessionsSdk.VERSION}", request.headers["AppGrowStack-Client"])
        assertEquals(batch.jsonText(), request.body!!.toString(Charsets.UTF_8))
    }

    @Test
    fun theReplayDecisionAndTheNewerKeptPropsComeBack() {
        val answer = """{"replay":"record","upload_token":"${"a".repeat(64)}","policy":{"version":3,"updated_at":1795230000000,"project":"gamification-app","mask":"text","no_capture_screens":[],"screens":{},"events":{"keep_props":{"habit_achieved":["habit_kind"]}},"replay":{},"retention":{}}}"""
        val (_, stack) = answering(200, answer)
        assertEquals(
            SessionSinkReply.Accepted(SessionReplayMode.RECORD, mapOf("habit_achieved" to listOf("habit_kind"))),
            stack.sessionSink().send(batch),
        )
    }

    @Test
    fun sessionsTurnedOffAnswerDisabled() {
        assertEquals(SessionSinkReply.Disabled, answering(204).second.sessionSink().send(batch))
    }

    @Test
    fun aBatchThatWillNeverPassIsDropped() {
        for (status in listOf(400, 404, 413)) {
            assertEquals(SessionSinkReply.Accepted(), answering(status, """{"error":{"code":"bad_request","message":"x"}}""").second.sessionSink().send(batch))
        }
    }

    @Test
    fun anAnswerThisVersionCannotReadStillTakesTheBatch() {
        // A newer server's `replay`: the batch arrived, so it must not stay at the head of the queue
        assertEquals(SessionSinkReply.Accepted(), answering(200, """{"replay":"stream"}""").second.sessionSink().send(batch))
        assertEquals(SessionSinkReply.Accepted(), answering(200, "{}").second.sessionSink().send(batch))
    }

    @Test
    fun busyServersTheKeyAndNoConnectionKeepTheBatch() {
        // 401 and 403 are the key or the project, fixed on the server: the same batch passes later
        for (status in listOf(401, 403, 429, 503)) {
            assertEquals(status, assertFailsWith<AppGrowStackSessionSink.Failure> { answering(status).second.sessionSink().send(batch) }.status)
        }
        assertFailsWith<IOException> { stack { throw IOException("offline") }.sessionSink().send(batch) }
    }
}
