package com.appgrowstack.stack.campaign

import com.appgrowstack.campaign.CampaignContext
import com.appgrowstack.campaign.CampaignFetchResult
import com.appgrowstack.campaign.CampaignSdk
import com.appgrowstack.campaign.CampaignSetFetchFailure
import com.appgrowstack.campaign.CampaignSetFetcher
import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The set from appgrowstack: `GET /v1/campaigns/sets/<public key>` with `If-None-Match` (ETag
 * `"v<version>"`). Until the first fetch succeeds, [fallback] (the set shipped in the app) is used.
 *
 * The `AppGrowStack-Client` header names kotlin-app-campaign and its version
 * (`android/2.1.0/kotlin-app-campaign/0.3.0`), so the server counts which versions fetch.
 */
public class AppGrowStackCampaignSetFetcher(
    private val stack: AppGrowStack,
    private val fallback: CampaignSetFetcher? = null,
) : CampaignSetFetcher {
    override suspend fun fetch(etag: String?): CampaignFetchResult =
        try {
            fromServer(etag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never fetched (no ETag): the bundled set lets the first launch decide offline.
            if (etag != null || fallback == null) throw e
            fallback.fetch(null)
        }

    private suspend fun fromServer(etag: String?): CampaignFetchResult = withContext(Dispatchers.IO) {
        val base = stack.request("GET", "/v1/campaigns/sets/${stack.publicKey}", CampaignSdk.NAME, CampaignSdk.VERSION)
        // The key is in the path (auth "public_key_path"); the set is the same for everyone.
        val headers = base.headers.filterKeys { it != "Authorization" } +
            (if (etag != null) mapOf("If-None-Match" to etag) else emptyMap())
        val response = stack.send(AppGrowStackRequest(base.method, base.url, headers, null))
        when (response.status) {
            200 -> CampaignFetchResult.Fetched(response.body.toString(Charsets.UTF_8), response.header("ETag"))
            304 -> CampaignFetchResult.NotModified
            else -> throw CampaignSetFetchFailure(response.status)
        }
    }
}

/**
 * The fetcher for `CampaignEngine(fetcher = …)`.
 *
 * @param fallback the set shipped in the app (`BundledCampaignSetFetcher { … }`), used only until the
 *   first fetch succeeds.
 */
public fun AppGrowStack.campaignSource(fallback: CampaignSetFetcher? = null): AppGrowStackCampaignSetFetcher =
    AppGrowStackCampaignSetFetcher(this, fallback)

/**
 * The context for `CampaignEngine(context = …)`, from the stack's values: the build, the channel, the
 * language and region, whether this is an internal device (read now), the clock's time zone, and the
 * first-seen date (the earlier of the app's date and the stored one), so `days_since_first_seen`
 * counts from when the person really started.
 */
public val AppGrowStack.campaignContext: CampaignContext
    get() = CampaignContext(
        appVersion = app.version,
        build = app.build,
        channel = app.channel.wire,
        lang = app.lang,
        region = app.region ?: "",
        timeZone = clock.zone,
        internalDevice = isInternalDevice(),
        platform = app.platform,
        firstSeenAt = firstSeenAt.toEpochMilli(),
    )
