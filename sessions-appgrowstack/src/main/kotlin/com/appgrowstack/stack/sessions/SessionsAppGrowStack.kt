package com.appgrowstack.stack.sessions

import com.appgrowstack.sessions.SessionBatch
import com.appgrowstack.sessions.SessionJson
import com.appgrowstack.sessions.SessionReplayMode
import com.appgrowstack.sessions.SessionSink
import com.appgrowstack.sessions.SessionSinkReply
import com.appgrowstack.stack.AppGrowStack
import java.io.IOException

/**
 * Sends the light record's batches to appgrowstack: `POST /v1/sessions/batches`.
 *
 * - 200: [SessionSinkReply.Accepted] with the server's replay decision and, when the policy came
 *   along, its `events.keep_props`
 * - 204: [SessionSinkReply.Disabled] (sessions are off or paused for the project)
 * - other 4xx (400, 413, …): `Accepted()`. The batch will never pass, so it is dropped instead of
 *   being sent on every launch
 * - 429, 5xx, no connection: throws, so the recorder keeps the batch and sends it again later
 *
 * Called on the recorder's sending thread (the request blocks).
 */
public class AppGrowStackSessionSink(private val stack: AppGrowStack) : SessionSink {
    /** Thrown for 429 and 5xx, and for a 200 whose body cannot be read. */
    public class Failure(public val status: Int) : IOException("POST /v1/sessions/batches answered $status")

    override fun send(batch: SessionBatch): SessionSinkReply {
        val request = stack.request("POST", "/v1/sessions/batches", body = batch.jsonText().toByteArray(Charsets.UTF_8), contentType = "application/json")
        val response = stack.send(request)
        return when {
            response.status == 200 -> readAnswer(response.body.toString(Charsets.UTF_8)) ?: throw Failure(200)
            response.status == 204 -> SessionSinkReply.Disabled
            response.status == 429 || response.status >= 500 -> throw Failure(response.status)
            else -> SessionSinkReply.Accepted()
        }
    }

    /** The parts of `SessionBatchAnswer` the recorder uses. `upload_token` and `live` belong to the recording. */
    private fun readAnswer(text: String): SessionSinkReply.Accepted? {
        val json = runCatching { SessionJson.parse(text) }.getOrNull() as? Map<*, *> ?: return null
        val replay = SessionReplayMode.entries.firstOrNull { it.wire == json["replay"] } ?: return null
        val keep = ((json["policy"] as? Map<*, *>)?.get("events") as? Map<*, *>)?.get("keep_props") as? Map<*, *>
        val keepProps = keep?.entries?.associate { (name, props) ->
            name.toString() to ((props as? List<*>)?.map { it.toString() } ?: emptyList())
        }
        return SessionSinkReply.Accepted(replay, keepProps)
    }
}

/** The sink for `AppSessions(sink = …)`. */
public fun AppGrowStack.sessionSink(): AppGrowStackSessionSink = AppGrowStackSessionSink(this)
