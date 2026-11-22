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
- **本人の端末。**どのリクエストにも`AppGrowStack-Device`（`stack.deviceHash`）と`AppGrowStack-Install`（install id）のヘッダーも付けます。appgrowstackはこれで本人の端末を数から外し、利用者だけを数えます。`deviceId`に`Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)`を渡します。同じ署名のキーで入れ直しても、アカウントを作り直しても変わりません。送るのは`sha256(公開キー+ ":" +端末のid)`だけで、端末のidそのものは端末の外に出ません。本人は、appgrowstackのconsoleの本人のリンクをその端末で開き、同じネットワークのままアプリを開いて、端末に1回だけマークを付けます。`deviceId`を渡さなければ、乱数を`AppGrowStackStorage.deviceId`に置きます（そのときは入れ直すと変わります）
- **答えの読み方は1つの表。** `AppGrowStackAnswer` がどのコネクターでも同じに読みます。2xxは終わり、401・403・408・429・5xxは後で送り直し、ほかの4xxは捨てます
- **Kotlin/JVMだけ。** Androidに依存しないので、headlessのテストからJVMでコネクターを動かせます

### コネクター

| モジュール | ライブラリ | 足す物 |
|---|---|---|
| `appgrowstack-core` | なし | `AppGrowStack`・`AppGrowStackApp`・`AppGrowStackEnvironment`・`AppGrowStackStorage` |
| `campaign-appgrowstack` | kotlin-app-campaign | `stack.campaignSource(fallback)`: 組をサーバーから取る（ETag・304）。最初の取得が通るまでは同梱した組を使う。`stack.campaignContext`: 初めて見た日を入れたエンジンの文脈 |
| `links-appgrowstack` | kotlin-app-links | `stack.attributionResolver()`: 入れた人の流入元をサーバーで決める（`POST /v1/links/attributions`）。`AttributionReporter` の `firstOpenAt` につなぐ型の `firstSeenAt` を渡す |
| `support-appgrowstack` | kotlin-app-support | `stack.supportTransport(uid, tokens)`: 問い合わせ・スクリーンショット・オーナーのトークンで読む返事・ヘルプの記事（`/v1/support/*`）と、アンケートや意見の画面で書いた文を送る `sendFeedback(text, campaign, choice, context, lang)`（`POST /v1/support/feedback`）。`stack.supportContext(device)`: つなぐ型の直近の記録を添えた文脈 |
| `sessions-appgrowstack` | kotlin-app-sessions | `stack.sessionSink()`: 軽い記録のバッチを送る `SessionSink`（`POST /v1/sessions/batches`）。サーバーが決めたリプレイの要否と残すプロパティを返す |
| `config-appgrowstack` | kotlin-app-config | `stack.configSource()`: 設定の組をサーバーから取る（`GET /v1/config/sets/<public key>`・ETag・304）。`stack.configFacts()`: 規則に使う端末の値 |

## はじめに

```kotlin
import android.provider.Settings
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
    deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
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
    implementation("com.github.appgrowstack.kotlin-appgrowstack:campaign-appgrowstack:0.3.0")
    implementation("com.github.appgrowstack.kotlin-appgrowstack:sessions-appgrowstack:0.3.0")
    implementation("com.github.appgrowstack.kotlin-appgrowstack:links-appgrowstack:0.3.0")
    implementation("com.github.appgrowstack.kotlin-appgrowstack:support-appgrowstack:0.3.0")
    implementation("com.github.appgrowstack.kotlin-appgrowstack:config-appgrowstack:0.3.0")
    // appgrowstack-core alone
    // implementation("com.github.appgrowstack.kotlin-appgrowstack:appgrowstack-core:0.2.0")
}
```

## 動く環境

- JDK 17+
- Android: `java.time` のためにminSdk 26、またはcore library desugaring
- Kotlin 2.4+

## ライセンス

MIT。[LICENSE](./LICENSE) を見てください。
