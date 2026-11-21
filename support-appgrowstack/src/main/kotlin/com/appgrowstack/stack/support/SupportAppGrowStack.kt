package com.appgrowstack.stack.support

import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackActivity
import com.appgrowstack.stack.AppGrowStackRequest
import com.appgrowstack.support.HelpArticle
import com.appgrowstack.support.HelpArticleSource
import com.appgrowstack.support.SupportAttachment
import com.appgrowstack.support.SupportCampaignShown
import com.appgrowstack.support.SupportCapabilities
import com.appgrowstack.support.SupportContext
import com.appgrowstack.support.SupportDevice
import com.appgrowstack.support.SupportError
import com.appgrowstack.support.SupportImage
import com.appgrowstack.support.SupportMessage
import com.appgrowstack.support.SupportRecentEvent
import com.appgrowstack.support.SupportRequest
import com.appgrowstack.support.SupportSdk
import com.appgrowstack.support.SupportTicket
import com.appgrowstack.support.SupportTransport
import com.appgrowstack.support.SupportValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps the owner token: the random key that lets this install read its own requests and replies.
 * The server stores only its SHA-256. Android apps keep it in their own private storage
 * (SharedPreferences or DataStore), never in a backup that goes to another device.
 */
public interface SupportTokenStore {
    public fun load(): String?
    public fun save(token: String)
}

/** In memory, for tests and previews. */
public class MemorySupportTokenStore(private var token: String? = null) : SupportTokenStore {
    override fun load(): String? = synchronized(this) { token }
    override fun save(token: String): Unit = synchronized(this) { this.token = token }
}

/**
 * Support on appgrowstack (`/v1/support/…`): requests with screenshots, the install's own requests
 * and replies (read with the owner token), and the product's help articles.
 */
public class AppGrowStackSupportTransport(
    private val stack: AppGrowStack,
    private val uid: () -> String?,
    private val tokens: SupportTokenStore,
) : SupportTransport, HelpArticleSource {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    override val capabilities: SupportCapabilities = SupportCapabilities(replies = true, images = true)

    /** The owner token, made on first use (43 URL-safe characters from 32 random bytes). */
    public fun ownerToken(): String {
        tokens.load()?.takeIf { it.length >= 32 }?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tokens.save(token)
        return token
    }

    /** Whether this install has ever sent a request (and so may have replies to read). */
    public val hasOwnerToken: Boolean get() = (tokens.load()?.length ?: 0) >= 32

    override suspend fun submit(request: SupportRequest, context: SupportContext, lang: String): String {
        val subject = request.subject?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
        val email = request.email?.trim()?.takeIf { it.isNotEmpty() }
        val body = buildJsonObject {
            put("install_id", stack.installId)
            uid()?.let { put("uid", it) }
            put("owner_token", ownerToken())
            put("lang", lang)
            email?.let { put("email", it) }
            put("category", request.category.wire)
            subject?.let { put("subject", it) }
            put("body", request.body.trim().take(4000))
            put("context", json.encodeToJsonElement(SupportContext.serializer(), context.forSending()))
        }
        val created = call(post("/v1/support/tickets", body, token = false)) { it.jsonObject.getValue("ticket_id").jsonPrimitive.content }
        for (image in request.images.take(4)) uploadImage(image, created)
        return created
    }

    override suspend fun tickets(): List<SupportTicket> {
        if (!hasOwnerToken) return emptyList()
        return call(withToken(stack.request("GET", "/v1/support/tickets", SupportSdk.NAME, SupportSdk.VERSION))) { decode(it, "tickets", ListSerializer(SupportTicket.serializer())) }
    }

    override suspend fun reply(body: String, ticketId: String): SupportMessage =
        call(post("/v1/support/tickets/${enc(ticketId)}/messages", buildJsonObject { put("body", body.take(4000)) }, token = true)) {
            json.decodeFromJsonElement(SupportMessage.serializer(), it)
        }

    public suspend fun uploadImage(image: SupportImage, ticketId: String): SupportAttachment =
        call(
            withToken(stack.request("POST", "/v1/support/tickets/${enc(ticketId)}/attachments", SupportSdk.NAME, SupportSdk.VERSION, body = image.data, contentType = image.contentType)),
        ) { json.decodeFromJsonElement(SupportAttachment.serializer(), it) }

    override suspend fun articles(lang: String): List<HelpArticle> =
        call(stack.request("GET", "/v1/support/articles", SupportSdk.NAME, SupportSdk.VERSION, query = mapOf("lang" to lang))) { decode(it, "articles", ListSerializer(HelpArticle.serializer())) }

    // HTTP

    private fun <T> decode(answer: JsonElement, key: String, list: DeserializationStrategy<T>): T =
        json.decodeFromJsonElement(list, answer.jsonObject.getValue(key))

    private fun post(path: String, body: JsonObject, token: Boolean): AppGrowStackRequest {
        val request = stack.request("POST", path, SupportSdk.NAME, SupportSdk.VERSION, body = body.toString().toByteArray(Charsets.UTF_8), contentType = "application/json")
        return if (token) withToken(request) else request
    }

    private fun withToken(request: AppGrowStackRequest): AppGrowStackRequest =
        AppGrowStackRequest(request.method, request.url, request.headers + ("X-Support-Token" to ownerToken()), request.body)

    /** Sends the request and reads a 2xx answer with [read]; anything else throws a [SupportError]. */
    private suspend fun <T> call(request: AppGrowStackRequest, read: (JsonElement) -> T): T = withContext(Dispatchers.IO) {
        val response = try {
            stack.send(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SupportError.Transport(e.message ?: e.toString())
        }
        val text = response.body.toString(Charsets.UTF_8)
        when (response.status) {
            in 200..299 -> try {
                read(json.parseToJsonElement(text))
            } catch (e: Exception) {
                throw SupportError.Transport("unreadable answer: ${e.message}")
            }
            404 -> throw SupportError.NotFound
            429 -> throw SupportError.RateLimited
            else -> {
                // `{ "error": { "code", "message" } }`
                val error = runCatching { json.parseToJsonElement(text).jsonObject.getValue("error").jsonObject }.getOrNull()
                throw SupportError.Rejected(
                    response.status,
                    error?.get("code")?.jsonPrimitive?.content ?: "unknown",
                    error?.get("message")?.jsonPrimitive?.content ?: "",
                )
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}

/**
 * The transport for `SupportCenter(transport = …)`. It is also the article source.
 *
 * @param uid the signed-in account, if any. Read at each request.
 * @param tokens where the owner token is kept (the app's private storage).
 */
public fun AppGrowStack.supportTransport(uid: () -> String? = { null }, tokens: SupportTokenStore): AppGrowStackSupportTransport =
    AppGrowStackSupportTransport(this, uid, tokens)

/**
 * The context for `SupportCenter(context = …)`: this build (from the stack's app) and device, plus
 * the stack's recent events and campaigns shown (written by the analytics and campaign connectors).
 *
 * @param device `SupportDevice(Build.VERSION.RELEASE, Build.MODEL)`.
 */
public fun AppGrowStack.supportContext(
    device: SupportDevice,
    isPremium: () -> Boolean? = { null },
    sessionId: () -> String? = { null },
): (screen: String?) -> SupportContext {
    val channel = SupportContext.Channel.entries.firstOrNull { it.wire == app.channel.wire } ?: SupportContext.Channel.DEBUG
    return { screen ->
        SupportContext.current(
            appVersion = app.version,
            build = app.build,
            device = device,
            channel = channel,
            isPremium = isPremium(),
            sessionId = sessionId(),
            screen = screen,
            recentEvents = activity.events.map { event ->
                SupportRecentEvent(event.name, event.at.toEpochMilli(), event.props.mapValues { (_, value) -> value.toSupport() })
            },
            campaignsShown = activity.campaigns.map { SupportCampaignShown(it.campaign, it.setVersion, it.at.toEpochMilli()) },
        )
    }
}

private fun AppGrowStackActivity.Value.toSupport(): SupportValue = when (this) {
    is AppGrowStackActivity.Value.Text -> SupportValue.Text(value)
    is AppGrowStackActivity.Value.Integer -> SupportValue.Integer(value)
    is AppGrowStackActivity.Value.Decimal -> SupportValue.Decimal(value)
    is AppGrowStackActivity.Value.Flag -> SupportValue.Flag(value)
}
