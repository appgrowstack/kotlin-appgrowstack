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
