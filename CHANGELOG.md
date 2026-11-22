# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.6.0] - 2026-11-23

### Changed

- Depends on app-campaign 0.7.0 and app-sessions 0.2.0 (a person who already had what a `whats_new`
  campaign announces is not shown it; `version_age_days` is available to campaign conditions).

## [0.5.0] - 2026-11-23

### Changed

- Depends on app-campaign 0.6.0 and app-config 0.2.0 (the campaign and config judges read the
  server-resolved `app_version` range).

## [0.4.0] - 2026-11-22

### Added

- `appgrowstack-core`: every request carries `AppGrowStack-Device` (`stack.deviceHash`,
  `sha256(publicKey + ":" + device id)` in lowercase hex) and `AppGrowStack-Install` (the install id),
  so the server can tell the owner's own devices from real users. `AppGrowStack` takes `deviceId`:
  pass `Settings.Secure.ANDROID_ID` (the same for every reinstall signed with the same key). Without it,
  a random id is kept in `AppGrowStackStorage.deviceId` (a new property with a default that keeps
  nothing, so existing storages still compile).

## [0.3.0] - 2026-11-22

### Added

- `appgrowstack-core`: `AppGrowStackAnswer`, the one table every connector reads a status with:
  2xx is `Accepted`; 401, 403, 408, 429 and 5xx are `RetryLater` (a key or a paused project is
  fixed on the server, so the same body passes later); any other 4xx is `Rejected`.
- `support-appgrowstack`: `sendFeedback(text, campaign, choice, context, lang)` sends what a person
  wrote in a survey or a feedback screen (`POST /v1/support/feedback`), the same as Swift's
  `SupportAppGrowStack.sendFeedback`. Empty text is not sent; a choice without a campaign is left out.

### Changed

- `support-appgrowstack`: `submit` returns kotlin-app-support's `SupportSubmission`. Once the request
  is made, an image that fails is handed back in `unsentImages` instead of throwing, so sending the
  form again no longer makes a second request.
- `links-appgrowstack`: statuses are read with `AppGrowStackAnswer`. 404, 409, 413 and 422 are now
  rejected (were tried again on every launch); 401 and 403 are still tried later, up to
  `AttributionReporter`'s `maxAttempts`.
- `sessions-appgrowstack`: statuses are read with `AppGrowStackAnswer`. 401 and 403 keep the batch
  (were dropped). A 200 whose body cannot be read (a `replay` this version does not know) takes the
  batch as `Accepted()` instead of throwing, which kept it at the head of the queue and blocked the
  batches behind it.

## [0.2.0] - 2026-11-22

### Added

- `campaign-appgrowstack`: `stack.campaignContext`, the engine's context from the stack's values
  (the clock's time zone, the first-seen date, whether the device is internal).
- `links-appgrowstack` (kotlin-app-links 0.2): `stack.attributionResolver()` decides the install's
  source with `POST /v1/links/attributions` (the stack's install id and platform). 200 is decided,
  400 is rejected, anything else and a failed connection are tried on a later launch.
- `support-appgrowstack` (kotlin-app-support 0.3): `stack.supportTransport(uid, tokens)` (requests,
  screenshots, replies with the owner token kept by the app's `SupportTokenStore`, help articles on
  `/v1/support/*`) and `stack.supportContext(device)` (the context with the stack's recent activity).
- `config-appgrowstack` (kotlin-app-config 0.1): `stack.configSource()` fetches the config set with
  `GET /v1/config/sets/<public key>` (ETag, 304, the `AppGrowStack-Client` header naming
  kotlin-app-config); `stack.configFacts()` gives the SDK's device values for the rules.

### Changed

- `campaign-appgrowstack` needs kotlin-app-campaign 0.4 and `sessions-appgrowstack` kotlin-app-sessions 0.1.1.

### Fixed

- `sessions-appgrowstack` sends each batch with `AppGrowStack.installId` as its `install_id`, so the light
  record joins the same install as the analytics, links and support. It sent the id the recorder kept.

## [0.1.0] - 2026-11-21

### Added

- `appgrowstack-core`: `AppGrowStack`, the one value the connectors read (public key, environment,
  lowercase install id, first-seen date, the app's clock, recent activity) and `request(method, path,
  sdk, version, …)`, whose `AppGrowStack-Client` header names the SDK the connector serves and its
  version, from that SDK's constants (`android/3.4.0/kotlin-app-sessions/0.1.0`), never this package.
- Connector modules are added per SDK (`<sdk>-appgrowstack`), so an app pulls in only the SDKs
  whose modules it adds.
- `campaign-appgrowstack` (kotlin-app-campaign 0.3): `stack.campaignSource(fallback)` fetches the set
  with `GET /v1/campaigns/sets/<public key>` (ETag, 304, the `AppGrowStack-Client` header naming
  kotlin-app-campaign) and uses the bundled set until the first fetch succeeds.
- `sessions-appgrowstack` (kotlin-app-sessions 0.1): `stack.sessionSink()` sends the light record's
  batches with `POST /v1/sessions/batches`. 200 is accepted with the replay decision and the policy's
  kept properties, 204 is disabled, other 4xx drop the batch, 429, 5xx and a failed connection throw
  so the batch is sent again later.
- `AppGrowStackResponse.headers` (lowercase names) and `header(name)`.
