package com.appgrowstack.stack.links

import com.appgrowstack.links.Attribution
import com.appgrowstack.links.AttributionRequest
import com.appgrowstack.links.AttributionResolution
import com.appgrowstack.links.AttributionResolver
import com.appgrowstack.links.LinksSdk
import com.appgrowstack.stack.AppGrowStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decides the install's source on appgrowstack (`POST /v1/links/attributions`). The server also
 * knows the short links' own sources and records `attribution_recorded` for the person.
 *
 * - 200: [AttributionResolution.Decided] with the server's answer
 * - 400: [AttributionResolution.Rejected] (the body will never pass; sending it again on every launch would only add noise)
 * The `AppGrowStack-Client` header names kotlin-app-links and its version.
 *
 * - anything else and no connection: [AttributionResolution.RetryLater] (503 while the server waits on a store, 429, 5xx)
 */
public class AppGrowStackAttributionResolver(private val stack: AppGrowStack) : AttributionResolver {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    override suspend fun resolve(request: AttributionRequest): AttributionResolution = withContext(Dispatchers.IO) {
        val body = json.encodeToString(AttributionRequest.serializer(), request.copy(installId = stack.installId, platform = stack.app.platform))
        val response = try {
            stack.send(stack.request("POST", "/v1/links/attributions", LinksSdk.NAME, LinksSdk.VERSION, body = body.toByteArray(Charsets.UTF_8), contentType = "application/json"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext AttributionResolution.RetryLater
        }
        when (response.status) {
            200 -> runCatching { json.decodeFromString(Attribution.serializer(), response.body.toString(Charsets.UTF_8)) }
                .getOrNull()
                ?.let { AttributionResolution.Decided(it) }
                ?: AttributionResolution.RetryLater
            400 -> AttributionResolution.Rejected
            else -> AttributionResolution.RetryLater
        }
    }
}

/** The resolver for `AttributionReporter(resolver = …)`. The install id and the platform are the stack's. */
public fun AppGrowStack.attributionResolver(): AppGrowStackAttributionResolver = AppGrowStackAttributionResolver(this)
