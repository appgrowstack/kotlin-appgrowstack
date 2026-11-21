# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `appgrowstack-core`: `AppGrowStack`, the one value the connectors read (public key, environment,
  lowercase install id, first-seen date, the app's clock, recent activity, the `AppGrowStack-Client`
  header).
- Connector modules are added per SDK (`<sdk>-appgrowstack`), so an app pulls in only the SDKs
  whose modules it adds.
- `campaign-appgrowstack` (kotlin-app-campaign 0.3): `stack.campaignSource(fallback)` fetches the set
  with `GET /v1/campaigns/sets/<public key>` (ETag, 304, the `AppGrowStack-Client` header naming
  kotlin-app-campaign) and uses the bundled set until the first fetch succeeds.
- `AppGrowStackResponse.headers` (lowercase names) and `header(name)`.
