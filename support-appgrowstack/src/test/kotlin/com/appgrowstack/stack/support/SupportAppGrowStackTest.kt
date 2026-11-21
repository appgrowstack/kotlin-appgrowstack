package com.appgrowstack.stack.support

import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackActivity
import com.appgrowstack.stack.AppGrowStackApp
import com.appgrowstack.stack.AppGrowStackEnvironment
import com.appgrowstack.stack.AppGrowStackHttp
import com.appgrowstack.stack.AppGrowStackRequest
import com.appgrowstack.stack.AppGrowStackResponse
import com.appgrowstack.support.SupportCategory
import com.appgrowstack.support.SupportContext
import com.appgrowstack.support.SupportDevice
import com.appgrowstack.support.SupportError
import com.appgrowstack.support.SupportImage
import com.appgrowstack.support.SupportRequest
import com.appgrowstack.support.SupportSdk
import com.appgrowstack.support.SupportTicketStatus
import com.appgrowstack.support.SupportValue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SupportAppGrowStackTest {
    private val app = AppGrowStackApp(version = "3.4.0", build = "340", channel = AppGrowStackApp.Channel.Play, lang = "ja")

    private fun stack(http: AppGrowStackHttp) =
        AppGrowStack("pk_KUdMabcdefghijklmnopqrst", AppGrowStackEnvironment.Staging, "A1B2C3D4-0000-4000-8000-00000000000F", app = app, http = http)

    private fun answering(vararg answers: Pair<Int, String>): Pair<MutableList<AppGrowStackRequest>, AppGrowStack> {
        val sent = mutableListOf<AppGrowStackRequest>()
        val queue = ArrayDeque(answers.toList())
        return sent to stack { request ->
            sent += request
            val (status, body) = queue.removeFirstOrNull() ?: (500 to "")
            AppGrowStackResponse(status, body.toByteArray())
        }
    }

    private val device = SupportDevice("16", "Pixel 9")

    @Test
    fun submitSendsTheRequestTheContextAndTheScreenshots() = runTest {
        val (sent, stack) = answering(
            201 to """{"ticket_id":"tkt_0123456789abcdef"}""",
            201 to """{"id":"att_1","content_type":"image/jpeg","bytes":3,"at":1}""",
        )
        stack.activity.record(AppGrowStackActivity.Event("habit_achieved", Instant.ofEpochMilli(1_700_000_000_000), mapOf("streak_days" to AppGrowStackActivity.Value.Integer(3))))
        stack.activity.record(AppGrowStackActivity.ShownCampaign("trial-offer", 3, Instant.ofEpochMilli(1_700_000_000_000)))
        val tokens = MemorySupportTokenStore()
        val transport = stack.supportTransport(uid = { "uid_1" }, tokens = tokens)
        val context = stack.supportContext(device, isPremium = { true })("settings")

        val id = transport.submit(
            SupportRequest(SupportCategory.BILLING, subject = "  ", body = " 解約したい \n", email = " a@example.com ", images = listOf(SupportImage(byteArrayOf(1, 2, 3), "image/jpeg"))),
            context, "ja",
        )
        assertEquals("tkt_0123456789abcdef", id)

        val create = sent[0]
        assertEquals("POST", create.method)
        assertEquals("https://api-staging.appgrowstack.com/v1/support/tickets", create.url)
        assertEquals(null, create.headers["X-Support-Token"])
        assertEquals("android/3.4.0/kotlin-app-support/${SupportSdk.VERSION}", create.headers["AppGrowStack-Client"])
        val body = Json.parseToJsonElement(create.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(setOf("install_id", "uid", "owner_token", "lang", "email", "category", "body", "context"), body.keys)
        assertEquals("a1b2c3d4-0000-4000-8000-00000000000f", body.getValue("install_id").jsonPrimitive.content)
        assertEquals("billing", body.getValue("category").jsonPrimitive.content)
        assertEquals("解約したい", body.getValue("body").jsonPrimitive.content)
        assertEquals("a@example.com", body.getValue("email").jsonPrimitive.content)
        assertEquals(43, body.getValue("owner_token").jsonPrimitive.content.length)
        assertEquals(tokens.load(), body.getValue("owner_token").jsonPrimitive.content)
        val ctx = body.getValue("context").jsonObject
        assertEquals("play", ctx.getValue("channel").jsonPrimitive.content)
        assertEquals("android", ctx.getValue("platform").jsonPrimitive.content)
        assertEquals("Pixel 9", ctx.getValue("device_model").jsonPrimitive.content)
        assertEquals("""[{"name":"habit_achieved","at":1700000000000,"props":{"streak_days":3}}]""", ctx.getValue("recent_events").toString())
        assertEquals("""[{"campaign":"trial-offer","set_version":3,"at":1700000000000}]""", ctx.getValue("campaigns_shown").toString())

        val upload = sent[1]
        assertEquals("https://api-staging.appgrowstack.com/v1/support/tickets/tkt_0123456789abcdef/attachments", upload.url)
        assertEquals("image/jpeg", upload.headers["Content-Type"])
        assertEquals(tokens.load(), upload.headers["X-Support-Token"])
        assertTrue(upload.body!!.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun ticketsNeedTheOwnerTokenAndCarryIt() = runTest {
        val (sent, stack) = answering(
            200 to """{"tickets":[{"ticket_id":"tkt_0123456789abcdef","status":"answered","subject":null,"created_at":1,"updated_at":2,"messages":[{"id":"msg_1","from":"support","body":"はい","at":2}]}]}""",
        )
        assertTrue(stack.supportTransport(tokens = MemorySupportTokenStore()).tickets().isEmpty())
        assertTrue(sent.isEmpty())

        val token = "t".repeat(43)
        val tickets = stack.supportTransport(tokens = MemorySupportTokenStore(token)).tickets()
        assertEquals(SupportTicketStatus.ANSWERED, tickets.single().status)
        assertEquals("はい", tickets.single().latestReply?.body)
        assertEquals(token, sent.single().headers["X-Support-Token"])
        assertEquals("GET", sent.single().method)
    }

    @Test
    fun articlesAskInTheLanguage() = runTest {
        val (sent, stack) = answering(200 to """{"articles":[{"slug":"widget","lang":"ja","title":"ウィジェット","summary":"","url":"https://x.com/help/widget/","tags":[],"updated_at":1}]}""")
        assertEquals(listOf("widget"), stack.supportTransport(tokens = MemorySupportTokenStore()).articles("zh-Hant").map { it.slug })
        assertEquals("https://api-staging.appgrowstack.com/v1/support/articles?lang=zh-Hant", sent.single().url)
    }

    @Test
    fun answersBecomeSupportErrors() = runTest {
        val token = MemorySupportTokenStore("t".repeat(43))
        suspend fun replyAnswering(status: Int, body: String = ""): SupportError {
            val (_, stack) = answering(status to body)
            return assertFailsWith<SupportError> { stack.supportTransport(tokens = token).reply("x", "tkt_x") }
        }
        assertEquals(SupportError.NotFound, replyAnswering(404))
        assertEquals(SupportError.RateLimited, replyAnswering(429))
        assertEquals(SupportError.Rejected(400, "invalid_body", "too long"), replyAnswering(400, """{"error":{"code":"invalid_body","message":"too long"}}"""))
        assertEquals(SupportError.Rejected(500, "unknown", ""), replyAnswering(500, "oops"))
        assertTrue(replyAnswering(200, "not json") is SupportError.Transport)
        val offline = assertFailsWith<SupportError> { stack { throw IOException("offline") }.supportTransport(tokens = token).reply("x", "tkt_x") }
        assertEquals(SupportError.Transport("offline"), offline)
    }

    @Test
    fun theContextTakesTheStacksBuildAndChannel() {
        val (_, stack) = answering()
        val context = stack.supportContext(device, sessionId = { "ses_1" })(null)
        assertEquals(SupportContext.Channel.PLAY, context.channel)
        assertEquals("3.4.0", context.appVersion)
        assertEquals("340", context.build)
        assertEquals("ses_1", context.sessionId)
        assertTrue(context.recentEvents.isEmpty())
        assertEquals(SupportValue.Integer(3), SupportValue.of(3))
    }
}
