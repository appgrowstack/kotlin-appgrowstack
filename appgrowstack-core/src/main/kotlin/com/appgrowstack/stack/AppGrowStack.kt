package com.appgrowstack.stack

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

/**
 * The one value that connects the kotlin-app-* SDKs to appgrowstack.
 *
 * Give it the public key, the environment and the install id once; every connector module
 * (`campaign-appgrowstack`, `links-appgrowstack`, …) reads them from here. The engines are still
 * built by the app: placements, actions and screens are the app's.
 *
 * @param installId the id the app's analytics uses for this install. Any case; it is lowercased.
 * @param firstSeenAt when the person first used the app, if the app knew it before this value
 *   existed. The earlier of this and the stored date is kept.
 * @param clock the app's clock. Connectors pass it on (the campaign engine waits with it).
 * @param deviceId the device's id that stays the same when the app is installed again. Pass
 *   `Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)` (the same value for every
 *   reinstall signed with the same key). Null or blank keeps a random id in [storage] instead, which
 *   changes on reinstall. Only [deviceHash] leaves the device.
 */
public class AppGrowStack(
    public val publicKey: String,
    public val environment: AppGrowStackEnvironment = AppGrowStackEnvironment.Production,
    installId: String,
    firstSeenAt: Instant? = null,
    public val app: AppGrowStackApp,
    public val clock: Clock = Clock.systemUTC(),
    public val isInternalDevice: () -> Boolean = { false },
    storage: AppGrowStackStorage = MemoryAppGrowStackStorage(),
    deviceId: String? = null,
    private val http: AppGrowStackHttp = UrlConnectionAppGrowStackHttp(),
) {
    /** Always lowercase (the contract's form), whatever case the app passed. */
    public val installId: String = installId.lowercase()

    /** The earlier of the date passed in and the date stored from an earlier launch. */
    public val firstSeenAt: Instant

    /** Recent events and campaigns shown, written by the analytics and campaign connectors. */
    public val activity: AppGrowStackActivity = AppGrowStackActivity()

    /**
     * `sha256(publicKey + ":" + device id)` in lowercase hex (64 characters), sent as
     * `AppGrowStack-Device` on every request. The server uses it to tell the owner's own devices from
     * real users. The raw id never leaves the device; hashing with the public key gives each project a
     * different value.
     */
    public val deviceHash: String = Companion.deviceHash(publicKey, deviceId?.takeIf { it.isNotBlank() } ?: storedDeviceId(storage))

    init {
        val stored = storage.firstSeenAt
        val earliest = listOfNotNull(stored, firstSeenAt).minOrNull() ?: clock.instant()
        if (stored != earliest) storage.firstSeenAt = earliest
        this.firstSeenAt = earliest
    }

    /** The person the connectors report for: the install id until people are resolved on the server. */
    public fun person(): String = installId

    /**
     * `AppGrowStack-Client: <platform>/<app version>/<sdk>/<sdk version>` (contracts K2-5).
     *
     * [sdk] and [version] are the SDK the connector serves (`CampaignSdk.NAME`, `SessionsSdk.VERSION`,
     * …), not this package, so the server counts which versions of that SDK call.
     */
    public fun clientHeader(sdk: String, version: String): String = "${app.platform}/${app.version}/$sdk/$version"

    /**
     * A request to [path] under the environment's root, with the public key, the client header
     * naming [sdk] at [version] ([clientHeader]), `AppGrowStack-Device` ([deviceHash]) and
     * `AppGrowStack-Install` ([installId]). The answer does not change with the last two.
     */
    public fun request(
        method: String,
        path: String,
        sdk: String,
        version: String,
        query: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
    ): AppGrowStackRequest {
        val q = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val headers = buildMap {
            put("Authorization", "Bearer $publicKey")
            put("AppGrowStack-Client", clientHeader(sdk, version))
            put("AppGrowStack-Device", deviceHash)
            put("AppGrowStack-Install", installId)
            if (contentType != null) put("Content-Type", contentType)
        }
        return AppGrowStackRequest(method, environment.baseUrl.trimEnd('/') + path + q, headers, body)
    }

    /** Sends a request built with [request]. */
    public fun send(request: AppGrowStackRequest): AppGrowStackResponse = http.send(request)

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    public companion object {
        /** `sha256(publicKey + ":" + deviceId)` in lowercase hex (the contract's `DeviceHash`). */
        public fun deviceHash(publicKey: String, deviceId: String): String =
            MessageDigest.getInstance("SHA-256").digest("$publicKey:$deviceId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun storedDeviceId(storage: AppGrowStackStorage): String =
            storage.deviceId ?: java.util.UUID.randomUUID().toString().also { storage.deviceId = it }
    }
}

/** Which appgrowstack the connectors talk to. Only the root URL; each connector adds its path. */
public sealed interface AppGrowStackEnvironment {
    public val baseUrl: String

    public data object Production : AppGrowStackEnvironment {
        override val baseUrl: String = "https://api.appgrowstack.com"
    }

    public data object Staging : AppGrowStackEnvironment {
        override val baseUrl: String = "https://api-staging.appgrowstack.com"
    }

    /** A stack on this machine (`scripts/dev-stack.sh`), e.g. `http://10.0.2.2:8787` from the emulator. */
    public data class Local(override val baseUrl: String) : AppGrowStackEnvironment
}

/** The build the connectors describe. */
public data class AppGrowStackApp(
    val version: String,
    val build: String,
    val channel: Channel,
    val lang: String,
    val region: String? = null,
    val platform: String = "android",
) {
    /** Where the build came from (the contract's `buildChannel`). */
    public enum class Channel(public val wire: String) { Play("play"), PlayTest("play_test"), Debug("debug") }
}

/** What [AppGrowStack] keeps between launches. */
public interface AppGrowStackStorage {
    public var firstSeenAt: Instant?

    /** A random device id, used only when the app passes no `deviceId` (or a blank one). Not kept by default. */
    public var deviceId: String?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) {}
}

/** In memory. Android apps pass one backed by SharedPreferences or DataStore. */
public class MemoryAppGrowStackStorage(
    override var firstSeenAt: Instant? = null,
    override var deviceId: String? = null,
) : AppGrowStackStorage

public class AppGrowStackRequest(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String>,
    public val body: ByteArray?,
)

public class AppGrowStackResponse(
    public val status: Int,
    public val body: ByteArray,
    /** Header names in lowercase. */
    public val headers: Map<String, String> = emptyMap(),
) {
    /** A header by name, any case. */
    public fun header(name: String): String? = headers[name.lowercase()]
}

/** Sends the connectors' requests. Blocking; call it off the main thread. */
public fun interface AppGrowStackHttp {
    public fun send(request: AppGrowStackRequest): AppGrowStackResponse
}

public class UrlConnectionAppGrowStackHttp(private val timeoutMillis: Int = 20_000) : AppGrowStackHttp {
    override fun send(request: AppGrowStackRequest): AppGrowStackResponse {
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val headers = connection.headerFields.entries
                .filter { (name, values) -> name != null && values.isNotEmpty() }
                .associate { (name, values) -> name.lowercase() to values.first() }
            return AppGrowStackResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0), headers)
        } finally {
            connection.disconnect()
        }
    }
}
