package com.appgrowstack.stack.sessions

import com.appgrowstack.sessions.AppSessions
import com.appgrowstack.sessions.SessionBatch
import com.appgrowstack.sessions.SessionDevice
import com.appgrowstack.sessions.SessionDeviceInfo
import com.appgrowstack.sessions.SessionReplayUpload
import com.appgrowstack.sessions.SessionReplayUploadReply
import com.appgrowstack.sessions.SessionReplayMode
import com.appgrowstack.sessions.SessionScreen
import com.appgrowstack.sessions.SessionSinkReply
import com.appgrowstack.sessions.SessionsSdk
import com.appgrowstack.sessions.testing.DirectExecutor
import com.appgrowstack.sessions.testing.FakeSessionClock
import com.appgrowstack.sessions.testing.InMemorySessionStateStore
import com.appgrowstack.sessions.testing.RecordingSessionSink
import com.appgrowstack.sessions.testing.SequenceSessionIds
import com.appgrowstack.sessions.testing.test
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
import kotlin.test.assertTrue

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
    fun eventPropsCarryTheSessionAndTheScreen() {
        val sessions = AppSessions(
            RecordingSessionSink(), { SessionDeviceInfo.test() }, InMemorySessionStateStore(), FakeSessionClock(), SequenceSessionIds(),
            sender = DirectExecutor,
        )
        // No recorder (an app without sessions) and a recorder not started yet: nothing is added
        assertTrue(sessionEventProps(null).isEmpty())
        assertTrue(sessionEventProps(sessions).isEmpty())

        sessions.start()
        val id = sessions.currentSessionId!!
        assertTrue(id.startsWith("ses_"))
        assertEquals(mapOf("session_id" to id), sessionEventProps(sessions))

        sessions.screen("paywall")
        assertEquals(mapOf("session_id" to id, "screen" to "paywall"), sessionEventProps(sessions))
        // The event's own value wins
        assertEquals(mapOf("session_id" to id, "screen" to "own"), sessionEventProps(sessions) + mapOf("screen" to "own"))
    }

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

    // ---- The recording

    private val token = "1795920000." + "0123456789abcdef".repeat(4)

    @Test
    fun theFirstAnswerCarriesTheUploadTokenAndLive() {
        val answer = """{"replay":"buffer","upload_token":"$token","live":{"token":"$token","url":"wss://api-staging.appgrowstack.com/v1/sessions/live"}}"""
        val reply = answering(200, answer).second.sessionSink().send(batch) as SessionSinkReply.Accepted
        assertEquals(SessionReplayMode.BUFFER, reply.replay)
        assertEquals(token, reply.uploadToken)
        val live = reply.live!!
        assertEquals("wss://api-staging.appgrowstack.com/v1/sessions/live", live.url)
        assertEquals(token, live.token)
        assertEquals("Bearer pk_KUdMabcdefghijklmnopqrst", live.headers["Authorization"])
        assertEquals("a1b2c3d4-0000-4000-8000-00000000000f", live.headers["X-Session-Install"])
        assertEquals("android/3.4.0/kotlin-app-sessions/${SessionsSdk.VERSION}", live.headers["AppGrowStack-Client"])
    }

    @Test
    fun aPolicyWithoutTheRecordingAnswersNone() {
        val answer = """{"replay":"buffer","upload_token":"$token","policy":{"replay":{"enabled":false,"sample":0,"daily_cap":0,"daily_bytes":0},"events":{"keep_props":{}}}}"""
        val reply = answering(200, answer).second.sessionSink().send(batch) as SessionSinkReply.Accepted
        assertEquals(SessionReplayMode.NONE, reply.replay)
        assertEquals(token, reply.uploadToken)
    }

    @Test
    fun aTokenOfTheWrongShapeIsNotKept() {
        val reply = answering(200, """{"replay":"record","upload_token":"x","live":{"token":"x","url":"https://x"}}""").second.sessionSink().send(batch) as SessionSinkReply.Accepted
        assertEquals(SessionSinkReply.Accepted(SessionReplayMode.RECORD), reply)
    }

    private val upload = SessionReplayUpload("ses_abcdefghijklmnopqrst", 3, "ffffffff-0000-4000-8000-000000000001", token, byteArrayOf(0x41, 0x47, 0x53, 0x52, 1))

    @Test
    fun putsTheSegmentWithTheInstallAndTheToken() {
        val (sent, stack) = answering(204)
        assertEquals(SessionReplayUploadReply.UPLOADED, stack.sessionReplaySink().upload(upload))
        val request = sent.single()
        assertEquals("PUT", request.method)
        assertEquals("https://api-staging.appgrowstack.com/v1/sessions/ses_abcdefghijklmnopqrst/segments/3", request.url)
        assertEquals("Bearer pk_KUdMabcdefghijklmnopqrst", request.headers["Authorization"])
        assertEquals("application/vnd.appgrowstack.replay-segment", request.headers["Content-Type"])
        // The stack's install id, as the batches went with it, not the one the recorder kept
        assertEquals("a1b2c3d4-0000-4000-8000-00000000000f", request.headers["X-Session-Install"])
        assertEquals(token, request.headers["X-Session-Upload"])
        assertEquals(upload.bytes.toList(), request.body!!.toList())
    }

    @Test
    fun theStatusesOfASegment() {
        assertEquals(SessionReplayUploadReply.FORBIDDEN, answering(403).second.sessionReplaySink().upload(upload))
        for (status in listOf(400, 404, 413, 415)) {
            assertEquals(SessionReplayUploadReply.REJECTED, answering(status).second.sessionReplaySink().upload(upload))
        }
        for (status in listOf(401, 408, 429, 500, 503)) {
            assertEquals(status, assertFailsWith<AppGrowStackSessionSink.Failure> { answering(status).second.sessionReplaySink().upload(upload) }.status)
        }
        assertFailsWith<IOException> { stack { throw IOException("offline") }.sessionReplaySink().upload(upload) }
    }

    @Test
    fun aSegmentWithoutATokenIsNotSent() {
        val (sent, stack) = answering(204)
        val without = SessionReplayUpload(upload.sessionId, upload.seq, upload.installId, null, upload.bytes)
        assertEquals(SessionReplayUploadReply.FORBIDDEN, stack.sessionReplaySink().upload(without))
        assertEquals(0, sent.size)
    }
}
