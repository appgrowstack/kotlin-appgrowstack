# kotlin-appgrowstack

kotlin-app-*のSDK（kotlin-app-campaign・kotlin-app-links・kotlin-app-supportなど）をappgrowstackにつなぎます。SDKはどれも単体で動き、このライブラリはサーバーと話す所だけを足します。

[English](./README.md)

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

> 開発は非公開のモノリポでしていて、ここへは片方向にコピーするだけです。Issueは受けます。PRは受けません。

## 機能

- **つなぐ値は1つ。** `AppGrowStack`に公開キー・環境・install id・アプリの時計を1回渡すと、全部のコネクタのモジュールがそこから読みます
- **コネクタはSDKごとのモジュール。**使うSDKのコネクタのモジュールだけを入れると、そのSDKだけが入ります
- **共通の文脈。** install idの小文字への揃えは1か所で行い、初回の日付は知っている中で一番早い物にし、直近の出来事と出したキャンペーンを問い合わせのコネクタのために持ちます

## はじめに

```kotlin
val stack = AppGrowStack(
    publicKey = "pk_…",
    environment = AppGrowStackEnvironment.Production,
    installId = installId,
    app = AppGrowStackApp(version = BuildConfig.VERSION_NAME, build = "${BuildConfig.VERSION_CODE}", channel = AppGrowStackApp.Channel.Play, lang = "ja"),
)
```

## コネクタ

| モジュール | SDK | 足す物 |
|---|---|---|
| `campaign-appgrowstack` | kotlin-app-campaign | `stack.campaignSource(fallback)`：組をサーバーから取る（ETag・304）。最初に取れるまでは同梱の組 |

```kotlin
val campaigns = CampaignEngine(fetcher = stack.campaignSource(BundledCampaignSetFetcher { readAsset("campaigns.json") }), …)
```

## 入れ方

[JitPack](https://jitpack.io/#appgrowstack/kotlin-appgrowstack)から入れます。`appgrowstack-core`と、使うSDKのコネクタのモジュールを足します。

```kotlin
dependencies {
    implementation("com.github.appgrowstack.kotlin-appgrowstack:appgrowstack-core:0.1.0")
    implementation("com.github.appgrowstack.kotlin-appgrowstack:campaign-appgrowstack:0.1.0")
}
```

## 動く環境

- JVM 17+（Android API 26+）
- Kotlin 2.4+

## ライセンス

MIT
