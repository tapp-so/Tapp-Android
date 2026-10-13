## [Unreleased]

### Fixed

- Restored deferred deep-link delivery when the backend reports an impression as already counted for the device (`HTTP 200 {"error":true}` from `/ref/deeplink`, e.g. a reinstall within 90 days). 1.1.5 treated this as a failure, so Native and Adjust integrations received `didFailResolvingUrl` and the link was lost. Non-2xx and `success:false` responses are still failures.
- Native installs that cannot be attributed now reach the terminal "not attributed" state instead of fingerprinting again on every cold start. A Play Install Referrer that contains no `fpid` no longer triggers a fingerprint request, and the backend's explicit no-match responses (`404 Data miss-match`, and `422 Fpid does not exist` when the real referrer was sent) end processing. Authentication, configuration, other validation and transient failures are not treated as "not attributed", so they are tried again on the next launch.
- The Native fingerprint request is retried once only for transient failures (network errors, timeouts, HTTP 429 and 5xx), not for permanent API errors. Coroutine cancellation is propagated instead of being swallowed.
- Tapp-Core now declares `android.permission.INTERNET`, so it is merged into every consuming app. Before, apps using only Tapp-Native that did not declare it themselves had every SDK request fail with a `SecurityException` (Adjust integrations already received it from the Adjust SDK).

## [1.1.5] - 2026-07-04

### Added

- Added Native and Adjust sample-app flavors for provider-specific testing.
- Added sample actions for Tapp backend events and Adjust-native events.

### Fixed

- Treat non-2xx responses and modern or legacy backend error envelopes as failures.
- Harden Adjust wrapper calls and distinguish event submission, confirmation, and failure.
- Redact credentials, tokens, device identifiers, deeplinks, and request/response payloads from SDK logs.
- Reduce repeated configuration-loading log noise.
