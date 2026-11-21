# kotlin-appgrowstack

kotlin-app-*のライブラリをappgrowstackにつなぐ。使うライブラリのコネクターのモジュールだけを足す。

[English](./README.md)

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)
![JVM](https://img.shields.io/badge/JVM-17-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

kotlin-app-*のライブラリはどれも単体で動きます。このライブラリはappgrowstackのサーバーとやり取りする所だけを足します。`appgrowstack-core` の値の `AppGrowStack` が1つと、ライブラリごとのコネクターのモジュールです。

## 機能

- **つなぐ値は1つ。** `AppGrowStack` に公開キー・環境・install id・アプリの時計を1回渡すと、どのコネクターのモジュールもそこから読みます
- **ライブラリごとにモジュールが1つ。** 使うライブラリのコネクターのモジュールだけを足します。どのモジュールも自分のライブラリだけを取り込みます
- **共通の情報。** install idの小文字への揃えは1か所で行い、初回の日付は知っている中で一番早い物にします。起動をまたいで保存するときは `AppGrowStackStorage` を渡します。指定しなければメモリーにだけ持ちます
- **環境。** `AppGrowStackEnvironment.Production`・`Staging`・`Local(baseUrl)`。どのリクエストにも公開キーと `AppGrowStack-Client` のヘッダーを付けます
- **Kotlin/JVMだけ。** Androidに依存しないので、headlessのテストからJVMでコネクターを動かせます

### コネクター

| モジュール | ライブラリ | 足す物 |
|---|---|---|
| `appgrowstack-core` | なし | `AppGrowStack`・`AppGrowStackApp`・`AppGrowStackEnvironment`・`AppGrowStackStorage` |
| `campaign-appgrowstack` | kotlin-app-campaign | `stack.campaignSource(fallback)`: 組をサーバーから取る（ETag・304）。最初の取得が通るまでは同梱した組を使う |
| `sessions-appgrowstack` | kotlin-app-sessions | `stack.sessionSink()`: 軽い記録のバッチを送る `SessionSink`（`POST /v1/sessions/batches`）。サーバーが決めたリプレイの要否と残すプロパティを返す |

## はじめに

```kotlin
import com.appgrowstack.campaign.BundledCampaignSetFetcher
import com.appgrowstack.stack.AppGrowStack
import com.appgrowstack.stack.AppGrowStackApp
import com.appgrowstack.stack.AppGrowStackEnvironment
import com.appgrowstack.stack.campaign.campaignSource
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
```

## ドキュメント

APIの説明は各モジュールの `src/main` のKDocです。変更は [CHANGELOG.md](./CHANGELOG.md) にあります。

## 入れ方

モジュールは [JitPack](https://jitpack.io/#appgrowstack/kotlin-appgrowstack) で公開しています。`settings.gradle.kts` にリポジトリを足します。

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

アプリの `build.gradle.kts` に、使うライブラリのコネクターのモジュールを足します。どのコネクターのモジュールも `appgrowstack-core` を含みます。

```kotlin
dependencies {
    implementation("com.github.appgrowstack.kotlin-appgrowstack:campaign-appgrowstack:0.1.0")
    implementation("com.github.appgrowstack.kotlin-appgrowstack:sessions-appgrowstack:0.1.0")
    // appgrowstack-core alone
    // implementation("com.github.appgrowstack.kotlin-appgrowstack:appgrowstack-core:0.1.0")
}
```

## 動く環境

- JDK 17+
- Android: `java.time` のためにminSdk 26、またはcore library desugaring
- Kotlin 2.4+

## ライセンス

MIT。[LICENSE](./LICENSE) を見てください。
