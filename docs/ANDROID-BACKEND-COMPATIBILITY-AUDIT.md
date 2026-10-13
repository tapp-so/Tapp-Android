# Tapp Android SDK — Backend Compatibility & Regression Audit

| | |
|---|---|
| **Audit date** | 2026-10-13 |
| **SDK repo / revision** | `tapp-so/Tapp-Android` · branch `main` · HEAD `77b5031` (= tag `1.1.5`) |
| **Backend repo / revision** | Tapp backend repository (Laravel 12) · branch `dev` · HEAD `6814153` (= tag `rc-0.0.63`), compared with `0.0.61` (latest non-RC tag) |
| **Scope** | Audit only. No source, dependency, version, tag or branch changes were made. This file is the only artifact created. |
| **Status update** | Findings **F-01, F-02 and F-03**, and the missing `INTERNET` permission from **F-12**, are fixed on branch `fix/android-backend-compatibility-1.1.6` and verified on an emulator against staging. See `ANDROID-EMULATOR-INTEGRATION-TEST-REPORT.md`. Backend items are tracked in `ANDROID-BACKEND-FOLLOW-UP-TASKS.md`. The rest of this document is the original audit, unchanged. |

---

## A. Executive Summary

**Overall status: PASS WITH RISKS.** Two P1 defects are confirmed, and on-device end-to-end verification was not possible.

**Wire-level compatibility.** All six backend endpoints the SDK calls accept the SDK's current requests. Evidence comes from three sources:

- the current backend source (`rc-0.0.63`, identical on these code paths to `0.0.61`);
- read-only live probes against the SANDBOX (staging) API;
- the compiled SDK parser, exercised against real backend response bodies.

The checks covered auth, paths, methods, field names, types, and happy-path success parsing for secrets, fingerprint, deeplink impression, link data, events and influencer URL generation. The backend's recent change from Sanctum auth to team API tokens (`rc-0.0.63`) stays compatible with existing embedded SDK tokens. This was confirmed live.

**Behavioral compatibility.** Two confirmed **P1** defects mean the SDK does not behave correctly in some realistic flows against the current backend:

| ID | Summary | Origin |
|---|---|---|
| **F-01** | A **reinstall on the same device within 90 days loses the deferred deep link** (Native and Adjust). The backend answers `/ref/deeplink` with `200 {"error":true,"message":"Impression already incremented…"}`, which is a deliberate non-fatal outcome. Since 1.1.5 the SDK treats it as a failure. | **Regression introduced by the 1.1.5 backend-contract hardening** (`4c04530`) |
| **F-02** | **For non-attributed Native installs, the SDK never reaches its "not attributed" terminal state.** The fingerprint request is re-sent on every cold start, indefinitely. That includes device data: GAID, ANDROID_ID, RAM/storage, locale and timezone. | **Backend contract change** `ad152fc` (2026-04-28): Android "no match" moved from `200 {"error":true}` to 4xx. The SDK was never updated. 1.1.5 doubles the traffic (F-03). |

**Hidden coupling risk.** The backend's strict "no unexpected keys" check for `/ref/fingerprint` is currently a no-op, because of Laravel `Validator::passes()` semantics. The Android SDK sends 15+ keys that are not on the backend whitelist, so it works only *because* that check is broken. If the backend team "fixes" the check, **all Android Native attribution breaks** (every request becomes 422). See B-1.

**Release readiness: CONDITIONAL.** `1.1.5` is already tagged and publicly built on JitPack (status `ok`, commit `77b5031`). Ship a 1.1.6 patch with the two mandatory fixes in §H (both are small and local) before recommending 1.1.5+ to integrators. See §I.

---

## B. Repository Baseline

### B.1 SDK repository

| Item | Value |
|---|---|
| Branch / HEAD | `main` @ `77b5031` "Merge pull request #12 from tapp-so/version/1.0.5-prep" |
| Tags | `1.0.0`, `1.0.1`, `1.0.2`, `1.1.0`, `1.1.1`, `1.1.2`, `1.1.3`, `1.1.4` (`aa37a66`), **`1.1.5` (`77b5031` = HEAD)** |
| Regression baseline used | `1.1.4` (`aa37a66`), the last release before the hardening |
| Uncommitted changes | `M app/.DS_Store` (pre-existing, preserved, untouched) |
| JitPack | `com.github.tapp-so.Tapp-Android:{Tapp-Core,Tapp-Native,Tapp-Adjust,Tapp-Appsflyer}`. All versions 1.0.0–1.1.5 report `ok`. 1.1.5 was built from `77b5031`. |
| SDK version (all modules) | `1.1.5` (`Tapp-*/build.gradle.kts:9-10`) |

**Hardening history.**

| Commit | Branch(es) | In `main`? | Content |
|---|---|---|---|
| `58e1da3` "fix(android): harden backend contract handling" (2026-07-02) | **only** `backup/backend-contract-hardening-before-reset` | **No** | Broad attempt, +1,142 lines: `HttpResult`, `EndpointResponseParser`, `FingerprintRequestMapper`, `ApiEnvironmentResolver`, 5 test classes. |
| `4c04530` "fix(android): handle backend error envelopes" | `fix/backend-contract-hardening` → PR #11 | **Yes** | `BackendResponseParser`, `requireBackendSuccess()`, non-2xx ⇒ failure. **Source of F-01 and F-03.** |
| `271b213` "fix(adjust): harden Adjust wrapper logging" | same | Yes | Adjust input guards, `runAdjustCall`, `callback_id`, event listeners (F-05, F-08) |
| `88d4ae4` "fix(android): redact sensitive SDK logs" | same | Yes | `Logger.redact`, removal of payload/token logging |
| `017e649` "chore(release): prepare 1.1.5" | `version/1.0.5-prep` → PR #12 | Yes | Version bumps, CHANGELOG |

> **Warning about `58e1da3`:** do not revive it as-is. Its `FingerprintRequestMapper` sends only `{fp, bundle_id, device_id, platform, tapp_token, webview:{platform, device_id, referrer}}`. That drops `screen_density`, `locale` and `timezone`, the exact fields the backend and web landing page hash for IP/hash matching (§C, row 2). It would silently break the hash-fallback attribution path.

### B.2 Architecture (verified, differs from the brief)

The brief describes a separate **Tapp-Networking-Android** library. **No such module or repository exists** for Android. On this machine only `IOS/Tapp_so/Tapp-Networking-iOS` exists. On Android, HTTP lives inside **Tapp-Core** (`services/network/NetworkManager.kt`, `HttpURLConnection`).

```
settings.gradle.kts → :app, :Tapp-Core, :Tapp-Native, :Tapp-Adjust, :Tapp-Appsflyer

Tapp-Core      (com.tapp.core)       TappEngine, TappExtensions, NetworkManager, TappEndpoint,
                                     BackendResponseParser, KeystoreUtils (AES-GCM prefs), Logger
Tapp-Native    (com.tapp.tappnative) api(Tapp-Core) + installreferrer:2.2 — TappNative fingerprint flow
Tapp-Adjust    (com.tapp.adjust)     api(Tapp-Core) + adjust-android 5.0.0 (+webbridge, signature 3.47.0 strictly)
Tapp-Appsflyer (com.tapp.appsflyer)  api(Tapp-Core) + af-android-sdk 6.12.1 ("coming soon" per docs)
app                                  sample; flavors `native` / `adjust`
```

Each adapter ships its own `com.example.tapp.Tapp` class. Adapters are mutually exclusive, and the docs say so ("Choose ONE adapter module").

### B.3 Toolchain & dependency versions

| Component | Version |
|---|---|
| Gradle wrapper | 8.9 |
| AGP | 8.7.3 |
| Kotlin Android plugin | 1.9.0 |
| kotlinx-serialization plugin | **1.8.0** (mismatch with Kotlin 1.9.0, see F-12) |
| JDK used | 17.0.9 (Oracle) |
| compileSdk / minSdk / targetSdk (sample) | 34 / 24 / 33 |
| JVM target | 11 |
| Core runtime deps | core-ktx 1.9.0, appcompat 1.7.0, material 1.12.0, kotlinx-serialization-json 1.6.0, security-crypto 1.1.0-alpha06, **api** play-services-ads-identifier 18.0.1; kotlinx-coroutines **1.6.4 (transitive only)** |

### B.4 Backend repository

| Item | Value |
|---|---|
| Location | Tapp backend repository (Laravel 12); SDK controllers in `app/Http/Controllers/Api/V1/SDK/` |
| Freshness | Remote refs fetched on 2026-10-13; `dev` == `origin/dev` == `6814153` |
| Deploy model (`.gitlab-ci.yml`) | `dev` → dev host; tags → staging (auto) and **production (manual on tag)** |
| Tags | `0.0.61` (`4ac5a5d`, 2026-06-27, latest non-RC), `rc-0.0.62` (`3fc9c16`), `rc-0.0.63` (`6814153`, 2026-09-17 = dev HEAD) |
| SDK-relevant changes `0.0.61 → rc-0.0.63` | (1) `auth:sanctum` → `team-api.token` middleware (teams / "centrally managed API credentials"). (2) All `uid` lookups → `tenantUid()`. (3) `/ref/fingerprint` application lookup is now **tenant-scoped**; it used to be global. Fingerprint, attribution and URL helpers are unchanged between the two tags. |
| Earlier SDK-relevant change | `ad152fc` (2026-04-28, **included in `0.0.61`**): "Apply API exceptions, error codes and enforce json response on SDK requests". This introduced the `{success:false,message,error_code}` envelope and moved Android fingerprint misses from `200 {error:true}` to 4xx. |
| Production version | **Not determinable** from the repo (manual tag deploy). All findings below hold for both `0.0.61` and `rc-0.0.63`; this was verified per file. |

---

## C. Backend Contract Matrix

Base URLs come from `TappEndpoint.kt:16-19`:

- PRODUCTION `https://api.tapp.so/v1/ref/`
- SANDBOX `https://api.staging.tapp.so/v1/ref/`

The backend mounts `routes/api_v1.php` under `v1` on `services.tapp.api_url` (`routes/api.php:9`). All routes are `POST` and HTTPS-only.

**Auth (all rows).** The SDK sends `Authorization: Bearer <authToken>` and `Content-Type: application/json`. The backend:

- `0.0.61`: `auth:sanctum`.
- `rc-0.0.63`: `team-api.token`. `TeamApiTokenAuthenticator.php:16-41` accepts team tokens (`<id>|<secret>`) **or** legacy Sanctum tokens named `TAPP_API_TOKEN_*`, which are auto-bound to a team. Tokens have always been created with that name (`UserSettingsController.php:77`, `SetupSecretsHelper.php:44`).
- Unauthenticated → `401 {"success":false,"message":"Unauthenticated.","error_code":"AUTH_UNAUTHENTICATED"}`. **Live-verified on staging.**

**Error envelope (all rows).** Since `ad152fc`, errors are `{"success":false,"message":…,"error_code":…}` with a 4xx/5xx status (`bootstrap/app.php:97-205`). Validation errors also carry `errors`. The SDK maps these to `BackendApiException(statusCode, message, error_code)` (`BackendResponseParser.kt:20-42`).

| # | SDK Operation (entry point) | Backend Endpoint | SDK Request (`TappEndpoint.kt`) | Backend Contract (current) | Compatibility | Evidence |
|---|---|---|---|---|---|---|
| 1 | Fetch secrets / app token (`TappEngine.start` → `fetchSecretsAndInitializeReferralEngineIfNeeded` → `TappAffiliateService.fetchSecrets`) | `POST /v1/ref/secrets` → `SecretsController@fetch` | `:44-60` `{tapp_token, bundle_id, mmp}`. `mmp` = `Affiliate.toIntValue()` (ADJUST=1, APPSFLYER=2, TAPP_NATIVE=3, TAPP=4). | Requires truthy `tapp_token`, `bundle_id`, `mmp`, else 422. `tapp_token` must equal the tenant's `tapp_ref_token`, else 403. `mmp==3` → `200 {error:false, secret:<tapp_ref_token>, message, branded_url}`. Other MMPs → `200 {error:false, secret:<MMP helper secret or false>, …}`. Missing integration → 422. Bad app/MMP → 400. (`SecretsController.php:20-88`) | ✅ Compatible. ⚠ `secret:false` edge (F-09). | Code. **Live (staging):** `mmp=3` → 200 with secret; `mmp=1` → 200 with secret. |
| 2 | Deferred-link fingerprint (`NativeAffiliateService.initialize` → `TappNative.init` → `NativeApiService.fetchDeferredLink`) | `POST /v1/ref/fingerprint` → `FingerprintController@fingerprint` → `AndroidHelper::verifyFingerprint` | `:174-210`. 21 keys: `tapp_token, bundle_id, advertising_id, fp, platform:"Android", os_version, device_model, device_manufacturer, screen_resolution, screen_density, locale, timezone, referrer(raw Play referrer or "null"), clickId(or "null"), device_id(ANDROID_ID or "null"), battery_level, isCharging, total_ram_bytes, total_storage_bytes, avail_storage_bytes, device_uptime_ms` | The body is copied into `webview`, sanitised (numeric strings → numbers, HTML-escaped) and validated against `androidRules()` (`SanitizationHelper.php:171-209`). **Only these SDK keys are used:** `platform, screen_density, locale, timezone, referrer, clickId (regex ^[A-Za-z0-9]+$ or null), device_id (string)`. The rest are silently dropped (B-1). `referrer` is parsed (`parse_str`) and **must contain `fpid`**, else `422 "Fpid does not exist"`. Match by `fpid`, else by hash+IP, else by `click_id`. No match → `404 "Data miss-match"`. Match → `200 {error:false, tapp_url, attr_tapp_url, influencer, data(obj or null), deeplink, fingerprint, method}`. The hash is `md5` of `{locale, platform, screen_density, timezone}`, matching the web landing page (`resources/js/Pages/Fingerprint/Android.tsx:28-52`). (`AndroidHelper.php:58-251`) | ✅ Request accepted; success parsing correct. ❌ **No-match semantics broken (F-02, F-03).** ⚠ Depends on the broken whitelist (B-1). | Code. **Live (staging):** the exact SDK-shaped payload without a referrer → `422 {"success":false,"message":"Fpid does not exist","error_code":"VALIDATION_ERROR"}`. It passed key validation and reached `verifyFingerprint`. Compiled-parser harness (§F). |
| 3 | Deeplink impression (Adjust: `handleAdjustDeeplink` → `appWillOpenInt` → `handleReferralCallback`; Native: `appWillOpenIntNative` → `handleReferralCallbackNative`) → `TappAffiliateService.handleImpression` | `POST /v1/ref/deeplink` → `DeepLinkController@check` | `:23-41` `{tapp_token, bundle_id, android_id, deeplink(Uri→string), os:"android"}` | Requires `deeplink`, `tapp_token`, `bundle_id` (400 `DATA_MISS_MATCH`). Token mismatch → 403. Link token is taken from `t` / `adj_t` / `asd` (`UrlHelper.php:113-119`), else 404. Unknown influencer → 404. **Same `android_id` seen < 90 days → `200 {"error":true,"message":"Impression already incremented for this android device."}`** (`DeepLinkController.php:136-152`). Counted → `200 {error:false,…}`. Limit reached on paid plan → `202 {error:false,…}`. Free plan → `429 LIMIT_REACHED`. | ✅ Request accepted; first-impression path correct. ❌ **"Already incremented" 200 is now treated as failure (F-01).** | Code (present in `0.0.61` and `rc-0.0.63`). **Live (staging):** URL without a link token → `404 "Deep link does not exist."`. Harness (§F). |
| 4 | Link data (`Tapp.fetchLinkData(url)`, `fetchOriginalLinkData()`, and internally after an impression) → `TappAffiliateService.callLinkDataService` | `POST /v1/ref/linkData` → `DeepLinkController@linkData` | `:87-105` `{tapp_token, bundle_id, link_token}`. `link_token` is the **value** of `t` / `adj_t`. | Requires all three (400). Token mismatch → 403. App not found → 404. Influencer `creative_link LIKE %link_token%` → `200 {error:false, tapp_url, attr_tapp_url, influencer, data(obj or null)}`. **No `deepLink` or `message` key.** Not found → `400 "Invalid mmp token"`. (`DeepLinkController.php:462-557`) | ✅ Compatible on success. ⚠ `deepLink` is never returned, so the SDK reports a sentinel string (F-06). | Code. **Live (staging):** bogus token → `400 {"success":false,"message":"Invalid mmp token","error_code":"DATA_MISS_MATCH"}`. |
| 5 | Event (`Tapp.handleTappEvent(TappEvent)` → `TappAffiliateService.trackEvent`) | `POST /v1/ref/event` → `EventsController@storeEvent` | `:107-142` `{tapp_token, bundle_id, event_name, event_url(saved deeplink or ""), linkToken(ignored by backend), os:"android", metadata(primitives only)}` | Laravel `validate`: `tapp_token` required string; `bundle_id` required string; `os` in `[ios, android]`; `event_name` required string ≤255; `event_url` nullable string ≤2048; `metadata` nullable array (`EventsController.php:27-34`). Token mismatch → `403 INVALID_TAPP_TOKEN`. Created → `201 {success:true, message, data:{application_id, event_name, event_hash}}`. Limit on paid plan → `202 {success:true,…}`. Free plan → 429. | ✅ Compatible. Success is detected via `success`/absence of `error`. ⚠ Empty custom event name → 422 (F-11). | Code. **Live (staging):** invalid `os` → `422 VALIDATION_ERROR` with field `errors` (proves the validation contract without writing). |
| 6 | Generate influencer URL (`Tapp.url(influencer, adGroup, creative, data)` → `TappAffiliateService.generateAffiliateUrl`) | `POST /v1/ref/influencer/add` → `InfluencersController@generateInfluencerUrl` | `:63-85` `{tapp_token, bundle_id, mmp, adgroup(""), creative(""), influencer, data(Map<String,String>)}` | Requires `bundle_id` and `mmp` (422). Token mismatch → 403. Success → `200 {error:false, message:"Succeed on fetching influencer URL", influencer_url}`. App/MMP missing → 404. (`InfluencersController.php:24-250`) | ✅ Compatible. | Code only (endpoint mutates data, so it was not probed live). |
| — | `POST /v1/ref/device`, `GET /v1/ref/c` | — | Not called by the Android SDK (iOS device id / web click) | — | N/A | `grep` of SDK sources |

**MMP id mapping.** `TAPP_NATIVE=3` is confirmed by the backend hard-coding Tapp as MMP id 3 (`DeepLinkController.php:89,103`; `SecretsController.php:54`). `ADJUST=1` is consistent with the live `mmp=1` success but **was not verified against the `mmps` table**, because seed data is only in a compressed DB dump.

**Install Referrer.** `TappNative.kt:240-325` reads the Play referrer once, with a timeout, and sends it **raw** as `referrer`. It also parses `click_id` with `Uri.getQueryParameter`. The backend ignores the SDK's `clickId` value (`FingerprintController.php:145-148`) and re-parses the raw referrer server-side. It requires `fpid`, and uses `click_id`, `tappin`, `utm_source` and `utm_medium`. The web flow builds exactly `utm_source=tapp&utm_medium=app&click_id=<id>[&tappin=…]&fpid=<uuid>` (`app/Http/Controllers/FingerprintController.php:206-225`, `Android.tsx:37-41`). **The parsing matches the current backend.** The SDK's `clickId` regex constraint is satisfied: `click_id` is ≤8 alphanumeric characters (`UserTableHelper.php:272`), and `""` is coerced to null.

---

## D. Findings

Severity: **P0** critical/blocking · **P1** fix before release · **P2** moderate / reliability · **P3** low / improvement.
Origin tags: **[REGRESSION-1.1.5]** caused by the hardening · **[BACKEND-DRIFT]** caused by a backend change · **[PRE-EXISTING]** present in 1.1.4 · **[BEHAVIOR-CHANGE]** intentional 1.1.5 change with consumer-visible effect.

No P0 findings were confirmed.

---

### F-01 · P1 · [REGRESSION-1.1.5] Reinstall within 90 days loses the deferred deep link ("already incremented" impression treated as failure)

1. **Location**
   - `Tapp-Core/src/main/java/com/example/tapp/services/affiliate/tapp/TappAffiliateService.kt:133-135`: `handleImpression` applies `.requireBackendSuccess()`, which defaults to `rejectLegacyError = true` (`BackendResponseParser.kt:44-58, 81-88`).
   - Consumers of the result: `TappExtensions.kt:117-156` (Adjust and `appWillOpen` path) and `TappExtensions.kt:233-270` (Native path).
   - Backend: `DeepLinkController.php:136-152`.
2. **Current behavior.** For a device whose ANDROID_ID was counted in the last 90 days, the backend returns `200 {"error":true,"message":"Impression already incremented for this android device."}`. The SDK converts this into `Result.failure(BackendApiException)`. Then:
   - **Native** (`TappExtensions.kt:253-268`): calls `deferredLinkDelegate.didFailResolvingUrl(…)`. It does **not** call `setProcessedReferralEngine()` or `didReceiveDeferredLink(response)`. On the next cold start the SDK re-fingerprints, but the `fpid` row was already marked `verified=true` by the first call (`AndroidHelper.php:148-175`). The backend now answers `404 Data miss-match`, so **the deferred deep link is lost permanently**, and F-02 repeats every launch.
   - **Adjust / `appWillOpen`** (`TappExtensions.kt:145-154`): calls `didFailResolvingUrl`, `completion(failure)`. It does not save the deep link or link token, does not mark processed, and does not fetch link data. Adjust's deferred-deeplink callback fires once per install, so the link is lost.
3. **Expected behavior (backend evidence).** The backend uses `error:true` here for a non-fatal "already counted" business outcome (HTTP 200, not an `ApiException`). It is the **only** reachable `200 + error:true` on any SDK endpoint (exhaustive grep of `app/Http/Controllers/Api/V1/SDK` and `app/Helpers/Fingerprint`). The 1.1.4 SDK handled it as success. The Native path even contains explicit logic for it: `if(!tappUrlResponse.error){ saveDeepLinkUrl… }` at `TappExtensions.kt:244-247`, then still marks processed and delivers `didReceiveDeferredLink`. Since 1.1.5 that branch is **unreachable dead code**.
4. **Reproduction / evidence**
   - Harness (§F, T-6) runs the compiled `BackendResponseParser`: `HTTP 200 {"error":true,"message":"Impression already incremented…"}` → `NetworkManager: SUCCESS` → `requireBackendSuccess(): FAILURE(BackendApiException…)`.
   - The existing unit test `BackendResponseParserTest."legacy error true is rejected by success validator"` (`"Already processed"` / `DUPLICATE`) codifies this behavior.
   - Device repro (not executed, no device): install a Native build via a Tapp link, open it (attributed), uninstall, click a *new* Tapp link on the same device within 90 days, reinstall, open. 1.1.4 calls `didReceiveDeferredLink`; 1.1.5 calls `didFailResolvingUrl` and never recovers. QA devices that reinstall repeatedly hit this every time.
5. **Regression risk if left.** High for QA and reinstalling users, silent for integrators (the callback flips from success to failure). First-install happy path is unaffected.
6. **Recommended minimal fix.** In `TappAffiliateService.handleImpression`, call `.requireBackendSuccess(rejectLegacyError = false)`. Non-2xx and `success:false` still fail; `error:true` at 2xx flows to the existing `tappUrlResponse.error` handling, as in 1.1.4. Optionally coordinate B-7 so the backend expresses this as success.

---

### F-02 · P1 · [BACKEND-DRIFT] Native "not attributed" terminal state is unreachable, so fingerprinting repeats on every cold start

1. **Location**
   - `Tapp-Native/.../native/NativeAffiliateService.kt:35-43` (the only path to `appWillNotProcess()` is `deferredLinkResponse.error == true`).
   - `TappNative.kt:130-161`; `NativeApiService.kt:24-57`.
   - `TappExtensions.kt:274-308` and `AffiliateServiceFactory.kt:22-26`: a new service instance per lookup, so `isEnabled()` is always `false` and `initialize()` runs on each process start.
   - `NativeAffiliateService.kt:24-27`: skips only when `hasProcessedReferralEngine`.
   - Backend: `AndroidHelper.php:67-81, 114-139`.
2. **Current behavior.** For any install without a matching fingerprint, the backend now returns **4xx**. Organic installs, missing referrer and expired matches all land here: `422 "Fpid does not exist"` (any referrer without `fpid`, including the SDK's `"null"` placeholder) or `404 "Data miss-match"`. Then:
   - In 1.1.5 the result is a failure, so `getOrNull()` returns `null`, the SDK retries once (F-03), and logs "No deferred link found". The listener is never invoked, so `appWillNotProcess()` never runs.
   - In 1.1.4 the 4xx JSON parsed as `DeferredLinkResponse(error=false, deeplink=null)`, so the outcome was the same: no terminal state.
   - `hasProcessedReferralEngine` therefore stays `false` forever, and **every cold start re-runs the full fingerprint flow**. That means the Install Referrer read, the GAID read, and a request carrying advertising ID, ANDROID_ID, device model, RAM and storage figures, locale and timezone.
3. **Expected behavior.** Before `ad152fc` (2026-04-28) the backend returned `200 {"error":true,"deeplink":null}` for these cases (diff of `AndroidHelper.php` in `ad152fc`). That drove `handleFailedLinkProcessing()` → `appWillNotProcess()` → processed. A Play install referrer never changes after install, so an install whose referrer lacks `fpid` can **never** match (`AndroidHelper.php:75-81`). Re-asking is pure waste and needless device-data transmission.
4. **Reproduction / evidence**
   - **Live (staging):** the SDK-identical payload with `referrer:"null"` returns `422 Fpid does not exist`.
   - Harness: 422 and 404 both produce `NetworkManager: FAILURE`.
   - Device repro (not executed): install the Native sample from Android Studio (no Play referrer), launch three times. Expect two `/ref/fingerprint` requests per launch and `referral_engine_processed=false` in `logConfig()`.
5. **Regression risk if left.** Affects most Native installs (all organic users), indefinitely. It adds backend load (each request also writes `storage/logs/*_android_fp.log` entries), battery and data use, and a privacy-posture issue. No false attribution was observed, because matching is gated on `fpid`.
6. **Recommended minimal fix** (SDK-side, no API change):
   - (a) In `TappNative`, when the Install Referrer call returned `OK` and the raw referrer has no `fpid` parameter, skip the network call and invoke the existing "not processable" path (`appWillNotProcess()`).
   - (b) When `/ref/fingerprint` fails with `BackendApiException` 404 `Data miss-match` or 422 (`VALIDATION_ERROR`), treat it as terminal (`appWillNotProcess()`) and do not retry.
   - Keep IO errors, timeouts, 5xx and 429 retryable on the next launch.
   - Caveat: 404 `NOT_FOUND` is also used for `"Application not found."` (a configuration error). Do not mark processed for that message, and coordinate B-5 for a distinct error code.

---

### F-03 · P2 · [REGRESSION-1.1.5] Fingerprint request is retried on definitive 4xx (doubled traffic)

1. **Location.** `TappNative.kt:130-150` (`callOnce() ?: run { delay(200); callOnce() }`), with `NativeApiService.kt:53-56` and `NetworkManager.kt:66` (non-2xx ⇒ failure since 1.1.5).
2. **Current.** Every 4xx (422/404 above, 401, 403) makes `callOnce()` return `null`, which triggers a second identical request 200 ms later. In 1.1.4 a 4xx JSON body parsed as a non-null response, so there was no retry.
3. **Expected.** Retry only on transient failures (IO, timeout, 5xx, 429). Combined with F-02 this produces **2 requests per cold start** for every non-attributed install.
4. **Evidence.** Code path plus harness rows for 422 and 404.
5. **Regression risk.** Moderate (load and data). No functional break.
6. **Fix.** Return the `Result` from `callOnce`. Retry only when the failure is not a `BackendApiException` with status 4xx (except 429). This is resolved together with F-02.

---

### F-04 · P2 · [PRE-EXISTING] No HTTP timeouts; coroutine timeouts do not bound blocking I/O

1. **Location.** `NetworkManager.kt:23-28` (no `connectTimeout`/`readTimeout`; no `disconnect()`). `TappNative.kt:130` (`withTimeoutOrNull(4000)`), `:49-51` (GAID `withTimeoutOrNull(2500)` around a blocking call).
2. **Current.** `HttpURLConnection` timeouts default to 0 (infinite) per the `URLConnection` contract. The network call is a blocking call inside `withContext(Dispatchers.IO)`, already on IO, so a coroutine timeout cannot interrupt it. The 4 s budget is therefore not enforced, and the call returns only when the socket does. Secrets, impression, link data, events and URL generation have no bound at all. `fetchLinkData()` / `url()` can suspend indefinitely, and completion callbacks may never fire on a stalled connection.
3. **Expected.** Finite connect, read and call timeouts.
4. **Evidence.** Code inspection only. Not reproduced, because it needs a black-holed network.
5. **Risk.** Moderate (hangs under bad networks). Unchanged by the hardening.
6. **Fix.** Set `connectTimeout`/`readTimeout` (for example 10 s / 15 s) and `disconnect()` in `finally` inside `NetworkManager.httpRequest`.

---

### F-05 · P2 · [BEHAVIOR-CHANGE 1.1.5] Adjust purchase-verification wrappers can return without ever invoking the caller's callback

1. **Location.** `Tapp-Adjust/.../AdjustAffiliateService.kt:148-151` (`verifyAppStorePurchase`: blank ids → return), `:168-171` (`verifyAndTrackPlayStorePurchase`: `!event.isValid` → return), `:184-187` (`verifyPlayStorePurchase`: blank `productId` → return). Also `runAdjustCall` (`:525-535`) swallows exceptions from `getAdid`, `getAttribution` and similar, so their completion is never called.
2. **Current.** The caller's `completion` / `OnPurchaseVerificationFinishedListener` is never invoked in these cases. Only a log line is emitted, and logs are off in PRODUCTION.
3. **Expected.** 1.1.4 always delegated to Adjust, so the caller's callback fired, or an exception surfaced. Public API callback contracts should always complete. Adjust's own handling of these invalid inputs was **not verified** in this audit.
4. **Evidence.** Diff `aa37a66..HEAD` of `AdjustAffiliateService.kt`.
5. **Risk.** Moderate for apps that await these callbacks (purchase flows can hang). Low frequency (invalid inputs only).
6. **Fix.** In each guard, invoke the callback with a failure result (or delegate validation to Adjust as in 1.1.4).

---

### F-06 · P3 · [PRE-EXISTING + minor 1.1.5 wording change] Link-data response fields that the backend does not return become sentinel strings

- **Location.** `TappAffiliateService.kt:224-233`; backend `DeepLinkController.php:511-550`.
- **Detail**
  - `/ref/linkData` never returns `deepLink`, so `TappLinkDataResponse.deepLink` is always the literal string `"deepLink was not returned"` rather than `null`. Missing `tapp_url`, `attr_tapp_url` and `influencer` would likewise become sentinel strings.
  - The 1.1.5 sentinels changed wording from `"… didn't returned"` to `"… was not returned"`. That is consumer-visible if anyone compared them.
  - JSON `null` attribute values become the string `"null"` (Android `optString` semantics).
- **Fix.** Return `null` for absent or `null` keys (`isNull(key)` / `has(key)` checks). Document that `deepLink` is only populated by the Native fingerprint flow.

### F-07 · P3 · [BEHAVIOR-CHANGE 1.1.5] Error messages and fields for `url()` / `fetchLinkData()` changed on backend errors

- **Location.** `TappAffiliateService.kt:81-87, 235-237`.
- **Detail.** Before 1.1.5, non-2xx bodies were parsed as data. For example, a `400 Invalid mmp token` produced `error=true, message="Invalid mmp token"`. Now:
  - `fetchLinkData` returns `message="Failed to parse response: Invalid mmp token (HTTP 400) [DATA_MISS_MATCH]"` and `isFirstSession=false`. The "parse" prefix is misleading.
  - `url()` returns `message="Exception occurred: …"`.
  - `error` stays `true`, so this is not a correctness break, but `message` text changed.
- **Fix (optional).** Use `BackendApiException.backendMessage` for `message`.

### F-08 · P3 · [BEHAVIOR-CHANGE 1.1.5] Adjust events now carry an auto-generated `callback_id`; Adjust log level VERBOSE → INFO

- **Location.** `AdjustAffiliateService.kt:107-113`, `:42`.
- **Detail.** Every `handleEvent` now sends `callback_id=<UUID>` to Adjust. It is visible in Adjust callbacks and raw exports. This is intended for correlation, but it is a new outbound parameter. Log level dropped from VERBOSE to INFO. Whether Adjust suppresses INFO in production was not verified.
- **Action.** Document in the CHANGELOG. No fix required.

### F-09 · P3 · [PRE-EXISTING] `secret:false` is stored as the app token `"false"`

- **Location.** `TappAffiliateService.kt:244-251`; backend `SecretsController.php:72,75`, `AdjustHelper.php:9-17`.
- **Detail.** When the Adjust integration lacks `app_token`, the backend returns `200 {error:false, secret:false}`. `optString("secret")` yields `"false"`, which is persisted. Adjust is then initialised with token `"false"`, and the SDK reports success.
- **Fix.** Accept `secret` only if it is a non-blank `String`.

### F-10 · P3 · [PRE-EXISTING] Configuring `Affiliate.TAPP` crashes

- **Location.** `TappAffiliateService.kt:28-34` (`initialize()` / `handleCallback()` = `TODO()`).
- **Detail.** `Affiliate.TAPP` is public. If an integrator passes it, `initializeAffiliateService` throws `NotImplementedError`, which is an `Error` and is not caught, on the main thread.
- **Fix.** Return `false` with a log line (as `58e1da3` did), or document the value as internal.

### F-11 · P3 · [PRE-EXISTING] Empty custom event name is sent and rejected with 422

- **Location.** `TappEndpoint.kt:114-117`; `RequestModels.kt:135-139` (`isValid` is unused).
- **Detail.** `EventAction.custom("")` is sent with `event_name:""`. Laravel converts it to null, giving `422 "event_name required"`. The failure is only logged.
- **Fix.** Reject `!isValid` locally before the request.

### F-12 · P3 · Build and packaging hygiene (no functional impact today)

- No SDK module declares `android.permission.INTERNET`: the Core AAR manifest has only `AD_ID`, and the docs don't mention it. Apps without INTERNET fail silently.
- `kotlinx-coroutines` is used throughout Core but is not declared. It resolves transitively at 1.6.4 via androidx.
- `security-crypto:1.1.0-alpha06` is declared but unused. `appcompat` and `material` are forced onto consumers as runtime dependencies.
- The Kotlin plugin is 1.9.0 but the serialization plugin is 1.8.0 (`build.gradle.kts:4-6`).
- `Tapp-Core/build.gradle.kts:54-58` adds `-Xfriend-paths` pointing at `Tapp-Adjust`'s build dir, a reverse coupling.
- `Tapp-Native` and `Tapp-Appsflyer` declare `manifest.srcFile("src/main/AndroidManifest.xml")`, but the file does not exist. AGP tolerates this.
- `NativeServiceRegistrar` (a `ContentProvider`) is never registered in a manifest. It is dead code; registration happens in `Tapp.init`.

### F-13 · P3 · [PRE-EXISTING] Install Referrer timeout nesting

- **Location.** `TappNative.kt:39-41` (outer 2000 ms) wraps `getInstallReferrerSafe(timeoutMs = 2500)` (`:240-246`).
- **Detail.** The inner timeout and its log line are unreachable, so the effective budget is 2 s. Cosmetic.

---

### Backend-side observations (not SDK defects, but they affect SDK compatibility)

| ID | Severity for SDK | Observation | Evidence |
|---|---|---|---|
| **B-1** | **High coupling risk** | `validateNoExtraKeys()` adds errors *before* `$validator->fails()`. Laravel's `passes()` starts with `$this->messages = new MessageBag`, which **discards them**. The Android SDK sends ~15 non-whitelisted keys (`advertising_id, os_version, device_model, device_manufacturer, screen_resolution, battery_level, isCharging, total_ram_bytes, …`, plus their `webview.*` copies). It works only because of this no-op. **If the backend fixes the check without whitelisting these keys, every Android Native fingerprint becomes 422 and attribution stops.** | `FingerprintController.php:116-121`; `SanitizationHelper.php:338-348`; `vendor/laravel/framework/src/Illuminate/Validation/Validator.php:459-461` (v12.19.3). **Live:** the SDK payload reached `verifyFingerprint`. |
| B-2 | Low | `deepSanitize` converts numeric-looking strings to numbers. An ANDROID_ID made only of digits (or `digits e digits`) then fails the `device_id` `string` rule and returns 422, so that device can never be fingerprint-attributed. That is roughly 1 in 1,800 devices for all-digit 16-hex IDs. | `SanitizationHelper.php:367-369`, rules `:177, :200` |
| B-3 | Low | If the matched fingerprint's influencer row is missing, `verifyFingerprint` returns `false`. The controller then emits HTTP 200 with body `false`. The SDK gets a `JSONException`, so the result counts as a failure and is retried. | `AndroidHelper.php:242`; `FingerprintController.php:215-221`; harness row 7 |
| B-4 | Low (plausible, not reproduced) | `$referrer['click_id']` is read without `array_key_exists`. A referrer with an unmatched `fpid` and no `click_id` key raises a PHP warning, which Laravel turns into a 500. | `AndroidHelper.php:114` |
| B-5 | Medium (blocks a clean F-02 fix) | `NOT_FOUND` is used for both `"Application not found."` (misconfiguration) and `"Data miss-match"` (no attribution), so clients cannot distinguish terminal from fixable. | `FingerprintController.php:93-96`; `AndroidHelper.php:118-121, 134-137` |
| B-6 | Ops | API tokens can be created with an expiry (`createToken(..., $expiration)`). An expired token embedded in a shipped app returns 401 with no SDK recovery path. | `UserSettingsController.php:77` |
| B-7 | Medium (root of F-01) | `/ref/deeplink` signals a non-error "already counted" outcome as `200 {"error":true}`, which contradicts the new envelope convention. | `DeepLinkController.php:146-152` |

---

## E. Regression Assessment (1.1.4 `aa37a66` → 1.1.5 `77b5031`)

**Public API.** No changes. `Tapp.kt` in Native, Adjust and Appsflyer is unchanged. Core additions (`BackendResponseParser`, `BackendApiException`, `Logger.redact`) are `internal`. **Request payloads.** Byte-for-byte unchanged: `TappEndpoint.kt` changed only a log string. **Initialization order, lifecycle, provider mapping, timeouts and cancellation.** Unchanged.

| # | 1.1.5 change | Effect against the current backend | Classification |
|---|---|---|---|
| 1 | Non-2xx ⇒ `Result.failure` (`NetworkManager.kt:66`, `BackendResponseParser.kt:20-42`); previously any parseable body was "success" | **Fixes false success.** Example: a 1.1.4 event rejected with `422 {"success":false}` was logged as tracked, because `optBoolean("error", false)` was `false`. Side effect: F-03 (retry on 4xx). | ✅ Legitimate fix (+ P2 side effect) |
| 2 | `success:false` at 2xx ⇒ failure | No current SDK endpoint returns this, so it is a no-op today and harmless. | ✅ Legitimate |
| 3 | `error:true` at 2xx ⇒ failure for every `TappAffiliateService` call (`rejectLegacyError=true`) | `/ref/deeplink` "already incremented" now fails → **F-01**. No-op for secrets, linkData, event and influencer, because those never return `error:true` at 2xx. | ❌ **Unintended regression** (deeplink only) |
| 4 | Log redaction and removal of payload/token/ID logging (`88d4ae4`) | No wire effect. Logs remain enabled only for `SANDBOX` (`Logger.init`). | ✅ Legitimate security fix |
| 5 | Adjust: blank `appToken` ⇒ `initialize()` returns false | Previously Adjust was initialised with a `null` token and the SDK reported success. | ✅ Legitimate |
| 6 | Adjust: input guards plus `runAdjustCall` exception swallowing | Callbacks may never fire (**F-05**). | ⚠ Behavior change, partly a regression of callback contracts |
| 7 | Adjust: auto `callback_id`; VERBOSE → INFO; event success/failure listeners | New outbound Adjust parameter (**F-08**). | ⚠ Intentional behavior change |
| 8 | Error message formats for `url()` / `fetchLinkData()` | Different `message` text (**F-07**). | ⚠ Behavior change |
| 9 | Sentinel string wording in link data | **F-06**. | ⚠ Cosmetic behavior change |

**F-02 is not caused by 1.1.5.** 1.1.4 also never reached the terminal state against any backend at or after `ad152fc`. The 1.1.5 retry (F-03) doubles its cost.

**Verdict.** The hardening was mostly a net improvement. It eliminated false successes and leaked secrets in logs. However, it silently removed one valid SDK behavior (F-01) and doubled fingerprint traffic (F-03). Both violate the principle that hardening must not silently remove valid functionality. Fixing them takes about 1 line (F-01) and a small local change (F-02/F-03).

---

## F. Test Results

All commands ran from the SDK repo root unless noted. No project configuration was modified. Build outputs went to git-ignored `build/` directories.

| ID | Command | Outcome |
|---|---|---|
| T-1 | `./gradlew test --console=plain --continue` | **BUILD SUCCESSFUL.** Tapp-Core `BackendResponseParserTest` 5/5 and `LoggerTest` 4/4 (debug and release). The app `ExampleUnitTest` template passed 1/1 in each of 4 variants. 0 failures, 0 skipped. Tapp-Native, Tapp-Adjust and Tapp-Appsflyer have **no tests**. |
| T-2 | `./gradlew assembleDebug assembleRelease --console=plain --continue` | **BUILD SUCCESSFUL.** Debug and release AARs for all 4 libraries, plus `app-{native,adjust}-{debug,release-unsigned}.apk`. There were 36 Kotlin warnings (17 unique: unused variables, a redundant `!!`, unchecked cast, always-left elvis, and `optString(…, null)` nullability), but no errors. |
| T-3 | `./gradlew lint --console=plain --continue` | **BUILD SUCCESSFUL. 0 errors.** Warnings by module: Core (`DiscouragedApi`×4 for `getIdentifier` in `ConfigManager`, `GradleDependency`×8, `ObsoleteSdkInt`×1), Native (`InlinedApi`×1, `GradleDependency`×2), Adjust (`GradleDependency`×2), Appsflyer (`GradleDependency`×2), and sample-only warnings in app. Dependency-update warnings were deliberately not acted on. |
| T-4 | `./gradlew generatePomFileForReleasePublication` | **BUILD SUCCESSFUL.** POMs: Native and Adjust declare `Tapp-Core:1.1.5` at compile scope. Consumers get Core (and its networking) transitively. Adjust pins `adjust-android-signature` **strictly** to 3.47.0 with exclusions on adjust-android and webbridge. No conflicting duplicates. |
| T-5 | `./gradlew :Tapp-Core:dependencies --configuration releaseRuntimeClasspath` | Resolves. `kotlinx-coroutines-android:1.6.4` arrives only transitively (F-12). |
| T-6 | **Scratch harness** (outside the repo): `java -cp Tapp-Core/build/tmp/kotlin-classes/debug:kotlin-stdlib-1.9.20.jar:json-20240303.jar Probe.java` calls the **compiled** `BackendResponseParser.parseHttpResponse` and `requireSuccess` with real backend bodies. | `200 {"error":true,"message":"Impression already incremented…"}` → `NetworkManager: SUCCESS` → **`requireBackendSuccess(): FAILURE`** (F-01). `200 {"error":false}` → SUCCESS. `202 {"error":false}` → SUCCESS. `201 {"success":true}` → SUCCESS. `422 Fpid does not exist` → FAILURE. `404 Data miss-match` → FAILURE. `200 false` → FAILURE (`JSONException`; B-3). |
| T-7 | **Live read-only probes** against SANDBOX `https://api.staging.tapp.so/v1/ref/*`, using the sample app's SANDBOX credentials (never printed or recorded here). Every request was chosen to be rejected before any DB write. | `secrets` without auth → **401** `AUTH_UNAUTHENTICATED`. `secrets` `mmp=3` → **200** `{error:false, secret, branded_url}`. `secrets` `mmp=1` → **200**. `linkData` with a bogus token → **400** `DATA_MISS_MATCH "Invalid mmp token"`. `deeplink` without a link token → **404** `"Deep link does not exist."`. `event` with invalid `os` → **422** `VALIDATION_ERROR` + `errors.os`. **SDK-identical `fingerprint` payload without a referrer → 422 `"Fpid does not exist"`** (it passed key validation; B-1). Embedded token format is `<id>|<secret>`, which the new team-token auth accepts. |
| T-8 | `curl https://jitpack.io/api/builds/com.github.tapp-so/Tapp-Android[/1.1.5]` and the published POMs (read-only status/metadata, no build triggered) | 1.0.0–1.1.5 all `ok`. 1.1.5 was built from `77b5031` and contains modules Tapp-Adjust, Tapp-Appsflyer, Tapp-Core and Tapp-Native. The published POMs are rewritten to group `com.github.tapp-so.Tapp-Android`, and the Core dependency resolves under that group. |

**Limitations**

- **No on-device or emulator E2E.** No device was attached, and an emulator cannot produce a real Play Install Referrer. F-01, F-02 and F-03 are established from backend source, compiled-parser execution and live staging responses, not from an app run.
- **Mutating endpoints were not exercised live**: a successful impression, event creation, influencer creation and a successful fingerprint match. Their success shapes come from backend source only.
- **Production was not probed.** Which backend tag runs in production is unknown. All conclusions were checked to hold for both `0.0.61` and `rc-0.0.63`.
- **Unit tests use the reference `org.json:20240303`, not Android's `org.json`.** Semantics differ, for example `optString` on JSON `null`: Android returns `"null"`. The existing tests cannot catch Android-specific parsing behavior.
- Passing unit tests here **do not** demonstrate backend compatibility. They cover only the parser and the log redactor.
- **Environment vs source.** No environment-related failures occurred. All Gradle tasks succeeded on JDK 17 / Gradle 8.9.

---

## G. Missing Coverage

The tests most needed to establish and keep compatibility, all JVM-level unless noted:

1. **`handleImpression` result mapping**: `200 {error:true}` must reach `onSuccess` with `TappUrlResponse.error=true` (F-01 guard). `200/202 {error:false}` → success. 4xx envelope → failure with code.
2. **Native referral flow** (`handleReferralCallbackNative`): verifies `setProcessedReferralEngine` and `didReceiveDeferredLink` on success and on "already incremented". Needs `KeystoreUtils`/`NetworkManager` behind a fake.
3. **Fingerprint outcome policy**: 422 or 404 must cause no retry and call `appWillNotProcess`. IO, 5xx and 429 retry. A referrer without `fpid` makes no network call (F-02, F-03).
4. **Request serialization contract tests**: one golden JSON body per endpoint built from `TappEndpoint`. Assert key names, types and the presence of `screen_density`, `locale`, `timezone`, `platform` and `referrer` (guards against a `58e1da3`-style mapper).
5. **Backend contract fixtures**: recorded response bodies from §C for every status, run through the real `NetworkManager` against a local `MockWebServer` or `HttpServer`. Cover non-2xx with JSON, non-JSON (`false`, HTML) and empty body.
6. **Link data parsing**: absent `deepLink`, `data:null`, attribute values `null`/number (F-06).
7. **Install Referrer parsing**: the `click_id` and `fpid` extraction from the documented referrer format, including an empty `click_id=`.
8. **Timeout behavior**: `NetworkManager` against a server that accepts and never responds (F-04).
9. **Adjust wrapper callback contract**: every public method with a callback must invoke it exactly once (F-05). Needs an Adjust static facade seam.
10. **Robolectric or instrumented test** using Android's real `org.json`, for the parsing paths above.
11. **Backend side:** a feature test that posts the current Android SDK fingerprint payload to `/v1/ref/fingerprint`. This protects against B-1 being "fixed" in a way that breaks the SDK.

---

## H. Remediation Plan (prioritised, minimal change)

### Mandatory before the next SDK release (1.1.6)

| Order | Finding | Change | Size |
|---|---|---|---|
| M1 | F-01 | `TappAffiliateService.handleImpression`: `.requireBackendSuccess()` → `.requireBackendSuccess(rejectLegacyError = false)`. Add test G-1. | 1 line + test |
| M2 | F-02 + F-03 | `TappNative`: (a) if the Install Referrer returned `OK` and has no `fpid`, skip the request and invoke the existing not-processable path (listener with `error=true` → `appWillNotProcess()`); (b) make `callOnce` return `Result`, and retry only on non-4xx (or 429) failures; (c) on 404 `Data miss-match` or 422 from `/ref/fingerprint`, take the not-processable path, except when the message is `"Application not found."` (until B-5 lands). Add tests G-3 and G-7. | ~30–40 lines in `TappNative.kt` (+ `NativeApiService` passthrough) |
| M3 | Process | Add the F-01, F-02/F-03, F-05, F-07 and F-08 behavior changes to `CHANGELOG.md`, and note that 1.1.5 is affected by F-01. | Docs |

### Backend coordination (owner: backend team)

| Order | Item | Request |
|---|---|---|
| BK1 | B-1 | **Do not enforce `validateNoExtraKeys` until the Android SDK keys are whitelisted, or the SDK sends only whitelisted keys.** Add the feature test from G-11. |
| BK2 | B-5 | Give "no attribution match" its own `error_code` (for example `FINGERPRINT_NO_MATCH`), distinct from `NOT_FOUND` for "Application not found". |
| BK3 | B-7 | Optionally return `"already incremented"` as a success envelope (for example `{success:true, already_counted:true}`). M1 keeps the SDK correct either way. |
| BK4 | B-3, B-4, B-2 | Return a JSON error instead of a bare `false`; guard `click_id`; do not numeric-coerce `device_id`. |

### Optional improvements (separate, lower-risk PRs)

| Order | Finding | Change |
|---|---|---|
| O1 | F-04 | `connectTimeout` / `readTimeout` plus `disconnect()` in `NetworkManager`. |
| O2 | F-05 | Invoke callbacks with failure in Adjust guard branches. |
| O3 | F-09, F-11, F-10 | Strict `secret` type check; local `isValid` check for custom events; make `Affiliate.TAPP` init non-crashing. |
| O4 | F-06, F-07 | `null` instead of sentinel strings; use the backend message for errors. **This is a consumer-visible change**, so ship it in a minor version with release notes. |
| O5 | F-12, F-13 | Declare `INTERNET` and coroutines; drop the unused `security-crypto`; align plugin versions; remove `-Xfriend-paths` and the dead `NativeServiceRegistrar`; fix the referrer timeout nesting. Do not bump dependency versions as part of this. |

---

## I. Release Readiness

### **CONDITIONAL**

- **Is the SDK compatible with the current backend?** At the wire level, yes. All endpoints, auth (including the new team API tokens), field names, types and success responses match, as verified from backend source and live staging probes. At the behavior level, two P1 defects remain:
  - **F-01**, a 1.1.5 hardening regression, loses deferred deep links on same-device reinstalls within 90 days, for both Native and Adjust.
  - **F-02**, backend drift, makes Native re-fingerprint on every cold start for every non-attributed install. 1.1.5's retry doubles it (F-03).
- **1.1.5 status.** Already tagged and publicly built on JitPack, so it cannot be "held". Recommendation: **do not promote or recommend 1.1.5 to new integrators.** Ship **1.1.6 with M1 and M2** (small and local; no public API, dependency or payload changes), with tests G-1 to G-3.
- **Conditions for GO**
  1. M1 and M2 merged with their tests.
  2. One on-device run of the Native sample from a real Play-referrer install (internal test track): first install delivers `didReceiveDeferredLink`; reinstall within 90 days still delivers it; an organic install makes exactly one fingerprint request and is never re-fingerprinted.
  3. Backend owners acknowledge BK1 (the B-1 coupling) before any fingerprint-validation change ships to production.
- **Not blocking.** F-04 to F-13 and B-2 to B-7 can follow in later releases.
