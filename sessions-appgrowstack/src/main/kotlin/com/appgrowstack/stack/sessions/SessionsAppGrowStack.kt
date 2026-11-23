package com.appgrowstack.stack.sessions

import com.appgrowstack.sessions.SessionBatch
import com.appgrowstack.sessions.SessionJson
import com.appgrowstack.sessions.SessionLiveOffer
import com.appgrowstack.sessions.SessionReplayMode
import com.appgrowstack.sessions.SessionReplaySink
import com.appgrowstack.sessions.SessionReplayUpload
import com.appgrowstack.sessions.SessionReplayUploadReply
import com.appgrowstack.sessions.SessionSink
import com.appgrowstack.sessions.SessionSinkReply
import com.appgrowstack.sessions.SessionsSdk
import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackAnswer
import com.appgrowstack.stack.AppGrowStackRequest
import java.io.IOException

/** The header that carries the install id on a segment and on the live connection. */
public const val SESSION_INSTALL_HEADER: String = "X-Session-Install"

/** The header that carries the session's `upload_token` on a segment. */
public const val SESSION_UPLOAD_HEADER: String = "X-Session-Upload"

/** The `Content-Type` of a segment (`.agsr`). */
public const val SESSION_SEGMENT_CONTENT_TYPE: String = "application/vnd.appgrowstack.replay-segment"

/**
 * Sends the light record's batches to appgrowstack: `POST /v1/sessions/batches`. The batch goes with
 * [AppGrowStack.installId] as its `install_id`, whatever id the recorder kept.
 *
 * The statuses are read with [AppGrowStackAnswer], the same table as every connector:
 * - 200: [SessionSinkReply.Accepted] with the server's replay decision (`NONE` when the policy that came
 *   along has `replay.enabled` false), the policy's `events.keep_props`, the `upload_token` (the
 *   session's first batch only) and `live` (someone is watching: the offer carries the headers the
 *   live connection needs). A body it cannot read (a `replay` it does not know) is `Accepted()`: the
 *   batch arrived, and must not stay at the head of the queue
 * - 204: [SessionSinkReply.Disabled] (sessions are off or paused for the project)
 * - a body that will never pass (400, 413 and the other 4xx but 401, 403, 408 and 429): `Accepted()`.
 *   It is dropped instead of being sent on every launch
 * - 401, 403, 408, 429, 5xx, no connection: throws, so the recorder keeps the batch and sends it
 *   again later
 *
 * The `AppGrowStack-Client` header names kotlin-app-sessions and its version
 * (`android/3.4.0/kotlin-app-sessions/0.1.0`), as every connector names the SDK it serves.
 *
 * Called on the recorder's sending thread (the request blocks).
 */
public class AppGrowStackSessionSink(private val stack: AppGrowStack) : SessionSink {
    /** Thrown for an answer to send again later (401, 403, 408, 429, 5xx for a batch; the same but 403 for a segment). */
    public class Failure(public val status: Int, call: String = "POST /v1/sessions/batches") : IOException("$call answered $status")

    override fun send(batch: SessionBatch): SessionSinkReply {
        // The recorder keeps its own id when the app does not hand it one. Send the stack's, so the light record
        // joins the same install as the analytics, links and support on the server
        val sent = batch.copy(session = batch.session.copy(installId = stack.installId))
        val request = stack.request(
            "POST",
            "/v1/sessions/batches",
            SessionsSdk.NAME,
            SessionsSdk.VERSION,
            body = sent.jsonText().toByteArray(Charsets.UTF_8),
            contentType = "application/json",
        )
        val response = stack.send(request)
        if (response.status == 204) return SessionSinkReply.Disabled
        return when (AppGrowStackAnswer.of(response.status)) {
            AppGrowStackAnswer.Accepted -> readAnswer(response.body.toString(Charsets.UTF_8)) ?: SessionSinkReply.Accepted()
            AppGrowStackAnswer.Rejected -> SessionSinkReply.Accepted()
            AppGrowStackAnswer.RetryLater -> throw Failure(response.status)
        }
    }

    /** The parts of `SessionBatchAnswer` the recorder uses. */
    private fun readAnswer(text: String): SessionSinkReply.Accepted? {
        val json = runCatching { SessionJson.parse(text) }.getOrNull() as? Map<*, *> ?: return null
        var replay = when (json["replay"]) {
            "record" -> SessionReplayMode.RECORD
            "buffer" -> SessionReplayMode.BUFFER
            else -> return null
        }
        val policy = json["policy"] as? Map<*, *>
        if ((policy?.get("replay") as? Map<*, *>)?.get("enabled") == false) replay = SessionReplayMode.NONE
        val keep = (policy?.get("events") as? Map<*, *>)?.get("keep_props") as? Map<*, *>
        val keepProps = keep?.entries?.associate { (name, props) ->
            name.toString() to ((props as? List<*>)?.map { it.toString() } ?: emptyList())
        }
        val uploadToken = (json["upload_token"] as? String)?.takeIf { TOKEN.matches(it) }
        val live = (json["live"] as? Map<*, *>)?.let { offer ->
            val token = (offer["token"] as? String)?.takeIf { TOKEN.matches(it) }
            val url = (offer["url"] as? String)?.takeIf { it.startsWith("wss://") }
            if (token == null || url == null) null else SessionLiveOffer(url, token, liveHeaders())
        }
        return SessionSinkReply.Accepted(replay, keepProps, uploadToken, live)
    }

    /** The key, the client and the install id, as on every request (the token goes in `Sec-WebSocket-Protocol`). */
    private fun liveHeaders(): Map<String, String> =
        stack.request("GET", "/v1/sessions/live", SessionsSdk.NAME, SessionsSdk.VERSION).headers + (SESSION_INSTALL_HEADER to stack.installId)

    private companion object {
        val TOKEN = Regex("^[0-9]{10}\\.[0-9a-f]{64}$")
    }
}

/** The sink for `AppSessions(sink = …)`. */
public fun AppGrowStack.sessionSink(): AppGrowStackSessionSink = AppGrowStackSessionSink(this)

/**
 * Sends the recording's segments to appgrowstack: `PUT /v1/sessions/<session_id>/segments/<seq>` with the
 * `.agsr` bytes, [AppGrowStack.installId] in `X-Session-Install` (the install the batches went with) and
 * the session's `upload_token` in `X-Session-Upload`.
 *
 * - 2xx: [SessionReplayUploadReply.UPLOADED] (204 also when sessions are off for the project: nothing to keep)
 * - 403 (the token is not this session's, or ran out): [SessionReplayUploadReply.FORBIDDEN]. A segment
 *   without a token never passes either, and is not sent
 * - 401, 408, 429, 5xx, no connection: throws [AppGrowStackSessionSink.Failure], so the segment is sent again later
 * - any other 4xx (400, 404, 413, 415): [SessionReplayUploadReply.REJECTED]
 *
 * Called on the recorder's sending thread (the request blocks).
 */
public class AppGrowStackReplaySink(private val stack: AppGrowStack) : SessionReplaySink {
    override fun upload(segment: SessionReplayUpload): SessionReplayUploadReply {
        val token = segment.uploadToken ?: return SessionReplayUploadReply.FORBIDDEN
        val base = stack.request(
            "PUT",
            "/v1/sessions/${segment.sessionId}/segments/${segment.seq}",
            SessionsSdk.NAME,
            SessionsSdk.VERSION,
            body = segment.bytes,
            contentType = SESSION_SEGMENT_CONTENT_TYPE,
        )
        val request = AppGrowStackRequest(
            base.method, base.url,
            base.headers + mapOf(SESSION_INSTALL_HEADER to stack.installId, SESSION_UPLOAD_HEADER to token),
            base.body,
        )
        val response = stack.send(request)
        if (response.status == 403) return SessionReplayUploadReply.FORBIDDEN
        return when (AppGrowStackAnswer.of(response.status)) {
            AppGrowStackAnswer.Accepted -> SessionReplayUploadReply.UPLOADED
            AppGrowStackAnswer.Rejected -> SessionReplayUploadReply.REJECTED
            AppGrowStackAnswer.RetryLater -> throw AppGrowStackSessionSink.Failure(response.status, "PUT /v1/sessions/:session_id/segments/:seq")
        }
    }
}

/** The sink for `AppSessions(replay = SessionReplayRecorder(directory, sink = …))`. */
public fun AppGrowStack.sessionReplaySink(): AppGrowStackReplaySink = AppGrowStackReplaySink(this)
