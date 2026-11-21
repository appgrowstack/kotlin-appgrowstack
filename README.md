# kotlin-appgrowstack

[Japanese](./README.ja.md)

Connects the kotlin-app-* SDKs (kotlin-app-campaign, kotlin-app-links, kotlin-app-support, …) to
appgrowstack. Each SDK works on its own; this library adds the parts that talk to the server.

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

> Developed in a private monorepo and copied here one way. Issues are welcome; pull requests are
> not accepted.

## Features

- **One value to connect.** `AppGrowStack` takes the public key, the environment, the install id
  and the app's clock once. Every connector module reads them from it
- **One module per SDK.** Add only the connector modules of the SDKs you use; each pulls in only
  its own SDK
- **Shared context.** The install id is lowercased in one place, the first-seen date is the earliest
  one known, and recent events and campaigns shown are kept for the support connector

## Quick Start

```kotlin
val stack = AppGrowStack(
    publicKey = "pk_…",
    environment = AppGrowStackEnvironment.Production,
    installId = installId,
    app = AppGrowStackApp(version = BuildConfig.VERSION_NAME, build = "${BuildConfig.VERSION_CODE}", channel = AppGrowStackApp.Channel.Play, lang = "ja"),
)
```

## Connectors

| Module | SDK | What it adds |
|---|---|---|
| `campaign-appgrowstack` | kotlin-app-campaign | `stack.campaignSource(fallback)`: the set fetched from the server (ETag, 304), the bundled set until the first fetch succeeds |
| `sessions-appgrowstack` | kotlin-app-sessions | `stack.sessionSink()`: the `SessionSink` that sends the light record's batches (`POST /v1/sessions/batches`) and hands back the server's replay decision and kept properties |

```kotlin
val campaigns = CampaignEngine(fetcher = stack.campaignSource(BundledCampaignSetFetcher { readAsset("campaigns.json") }), …)
```

## Installation

From [JitPack](https://jitpack.io/#appgrowstack/kotlin-appgrowstack). Add `appgrowstack-core` and the connector module of each SDK you use.

```kotlin
dependencies {
    implementation("com.github.appgrowstack.kotlin-appgrowstack:appgrowstack-core:0.1.0")
    implementation("com.github.appgrowstack.kotlin-appgrowstack:campaign-appgrowstack:0.1.0")
}
```

## Requirements

- JVM 17+ (Android API 26+)
- Kotlin 2.4+

## License

MIT
