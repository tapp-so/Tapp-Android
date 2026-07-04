## [1.1.5] - 2026-07-04

### Added

- Added Native and Adjust sample-app flavors for provider-specific testing.
- Added sample actions for Tapp backend events and Adjust-native events.

### Fixed

- Treat non-2xx responses and modern or legacy backend error envelopes as failures.
- Harden Adjust wrapper calls and distinguish event submission, confirmation, and failure.
- Redact credentials, tokens, device identifiers, deeplinks, and request/response payloads from SDK logs.
- Reduce repeated configuration-loading log noise.
