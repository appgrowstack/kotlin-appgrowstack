package com.appgrowstack.stack.links

import com.appgrowstack.links.Attribution
import com.appgrowstack.links.AttributionRequest
import com.appgrowstack.links.AttributionResolution
import com.appgrowstack.links.AttributionResolver
import com.appgrowstack.links.LinksSdk
import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decides the install's source on appgrowstack (`POST /v1/links/attributions`). The server also
 * knows the short links' own sources and records `attribution_recorded` for the person.
 *
 * The status is read with [AppGrowStackAnswer]:
 * - 2xx: [AttributionResolution.Decided] with the server's answer
 * - a body that will never pass (400 and the other 4xx but 401, 403, 408 and 429): [AttributionResolution.Rejected]
 * - the rest (503 while the server waits on a store, 401 and 403, 429, 5xx, no connection):
 *   [AttributionResolution.RetryLater], up to the reporter's `maxAttempts`
 *
 * The `AppGrowStack-Client` header names kotlin-app-links and its version.
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
        when (AppGrowStackAnswer.of(response.status)) {
            AppGrowStackAnswer.Accepted -> runCatching { json.decodeFromString(Attribution.serializer(), response.body.toString(Charsets.UTF_8)) }
                .getOrNull()
                ?.let { AttributionResolution.Decided(it) }
                ?: AttributionResolution.RetryLater
            AppGrowStackAnswer.Rejected -> AttributionResolution.Rejected
            AppGrowStackAnswer.RetryLater -> AttributionResolution.RetryLater
        }
    }
}

/** The resolver for `AttributionReporter(resolver = …)`. The install id and the platform are the stack's. */
public fun AppGrowStack.attributionResolver(): AppGrowStackAttributionResolver = AppGrowStackAttributionResolver(this)
