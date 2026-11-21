package com.appgrowstack.stack.config

import com.appgrowstack.config.ConfigFetchResult
import com.appgrowstack.config.ConfigSdk
import com.appgrowstack.config.ConfigSetFetchFailure
import com.appgrowstack.config.ConfigSetSource
import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * The set from appgrowstack: `GET /v1/config/sets/<public key>` with `If-None-Match` (ETag
 * `"v<version>"`). The set is the same for everyone; the device picks rules and variants.
 *
 * The `AppGrowStack-Client` header names kotlin-app-config and its version
 * (`android/2.1.0/kotlin-app-config/0.1.0`), so the server counts which versions fetch.
 */
public class AppGrowStackConfigSetSource(private val stack: AppGrowStack) : ConfigSetSource {
    override suspend fun fetch(etag: String?): ConfigFetchResult = withContext(Dispatchers.IO) {
        val base = stack.request("GET", "/v1/config/sets/${stack.publicKey}", ConfigSdk.NAME, ConfigSdk.VERSION)
        // The key is in the path (auth "public_key_path").
        val headers = base.headers.filterKeys { it != "Authorization" } +
            (if (etag != null) mapOf("If-None-Match" to etag) else emptyMap())
        val response = stack.send(AppGrowStackRequest(base.method, base.url, headers, null))
        when (response.status) {
            200 -> ConfigFetchResult.Fetched(response.body.toString(Charsets.UTF_8), response.header("ETag"))
            304 -> ConfigFetchResult.NotModified
            else -> throw ConfigSetFetchFailure(response.status)
        }
    }
}

/** The source for `AppConfig(source = …)`. */
public fun AppGrowStack.configSource(): AppGrowStackConfigSetSource = AppGrowStackConfigSetSource(this)

/**
 * The SDK's device values for the rules, from this stack (`platform`, `app_version`, `build`,
 * `channel`, `lang`, `region`, `days_since_first_seen`). Add the app's own facts to the map.
 */
public fun AppGrowStack.configFacts(): Map<String, JsonPrimitive> = buildMap {
    put("platform", JsonPrimitive(app.platform))
    put("app_version", JsonPrimitive(app.version))
    put("build", JsonPrimitive(app.build))
    put("channel", JsonPrimitive(app.channel.wire))
    put("lang", JsonPrimitive(app.lang))
    app.region?.let { put("region", JsonPrimitive(it)) }
    val days = java.time.Duration.between(firstSeenAt, clock.instant()).toDays().coerceAtLeast(0)
    put("days_since_first_seen", JsonPrimitive(days))
}
