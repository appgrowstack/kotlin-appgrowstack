# kotlin-appgrowstack

Connect the kotlin-app-* libraries to appgrowstack, adding only the connector modules of the libraries you use.

[Japanese](./README.ja.md)

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)
![JVM](https://img.shields.io/badge/JVM-17-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

Each kotlin-app-* library works on its own. This library adds the parts that talk to the
appgrowstack server: one `AppGrowStack` value in `appgrowstack-core`, and one connector module per
library.

## Features

- **One value to connect.** `AppGrowStack` takes the public key, the environment, the install id and
  the app's clock once. Every connector module reads them from it
- **One module per library.** Add only the connector modules of the libraries you use; each pulls in
  only its own library
- **Shared context.** The install id is lowercased in one place, and the first-seen date is the
  earliest one known. Pass an `AppGrowStackStorage` to keep it between launches; the default keeps
  it in memory
- **Environments.** `AppGrowStackEnvironment.Production`, `Staging` and `Local(baseUrl)`. Every
  request carries the public key and the `AppGrowStack-Client` header
- **Kotlin/JVM only.** No Android dependency, so headless tests run the connectors on the JVM

### Connectors

| Module | Library | What it adds |
|---|---|---|
| `appgrowstack-core` | none | `AppGrowStack`, `AppGrowStackApp`, `AppGrowStackEnvironment`, `AppGrowStackStorage` |
| `campaign-appgrowstack` | kotlin-app-campaign | `stack.campaignSource(fallback)`: the set fetched from the server (ETag, 304), the bundled set until the first fetch succeeds. `stack.campaignContext`: the engine's context with the first-seen date |
| `links-appgrowstack` | kotlin-app-links | `stack.attributionResolver()`: the install's source decided on the server (`POST /v1/links/attributions`) |
| `support-appgrowstack` | kotlin-app-support | `stack.supportTransport(uid, tokens)`: requests, screenshots, replies with the owner token and help articles on `/v1/support/*`. `stack.supportContext(device)`: the context with the stack's recent activity |
| `sessions-appgrowstack` | kotlin-app-sessions | `stack.sessionSink()`: the `SessionSink` that sends the light record's batches (`POST /v1/sessions/batches`) and hands back the server's replay decision and kept properties |
| `config-appgrowstack` | kotlin-app-config | `stack.configSource()`: the config set fetched from the server (`GET /v1/config/sets/<public key>`, ETag, 304). `stack.configFacts()`: the device values for the rules |

## Quick Start

```kotlin
import com.appgrowstack.campaign.BundledCampaignSetFetcher
import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackApp
import com.appgrowstack.stack.AppGrowStackEnvironment
import com.appgrowstack.stack.campaign.campaignSource
import com.appgrowstack.stack.config.configSource
import com.appgrowstack.stack.sessions.sessionSink

val stack = AppGrowStack(
    publicKey = "pk_…",
    environment = AppGrowStackEnvironment.Production,
    installId = installId,                // the id your analytics uses for this install
    app = AppGrowStackApp(
        version = BuildConfig.VERSION_NAME,
        build = BuildConfig.VERSION_CODE.toString(),
        channel = AppGrowStackApp.Channel.Play,
        lang = "en",
    ),
)

// campaign-appgrowstack: pass it as CampaignEngine(fetcher = …)
val fetcher = stack.campaignSource(
    fallback = BundledCampaignSetFetcher { assets.open("campaigns.json").bufferedReader().use { it.readText() } },
)

// sessions-appgrowstack: pass it as AppSessions(sink = …)
val sink = stack.sessionSink()

// config-appgrowstack: pass it as AppConfig(source = …)
val source = stack.configSource()
```

## Documentation

The API reference is the KDoc in each module's `src/main`. Changes are listed in
[CHANGELOG.md](./CHANGELOG.md).

## Installation

The modules are published through [JitPack](https://jitpack.io/#appgrowstack/kotlin-appgrowstack).
Add the repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then add the connector module of each library you use in the app's `build.gradle.kts`. Each
connector module includes `appgrowstack-core`:

```kotlin
dependencies {
    implementation("com.github.appgrowstack.kotlin-appgrowstack:campaign-appgrowstack:0.1.0")
    implementation("com.github.appgrowstack.kotlin-appgrowstack:sessions-appgrowstack:0.1.0")
    // config-appgrowstack comes with the next release (not in 0.1.0)
    // appgrowstack-core alone
    // implementation("com.github.appgrowstack.kotlin-appgrowstack:appgrowstack-core:0.1.0")
}
```

## Requirements

- JDK 17+
- Android: minSdk 26 for `java.time`, or core library desugaring
- Kotlin 2.4+

## License

MIT. See [LICENSE](./LICENSE).
