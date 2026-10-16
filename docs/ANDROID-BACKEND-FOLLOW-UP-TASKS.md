# Backend follow-up tasks from the Android SDK compatibility work

Two backend changes came out of the Android SDK audit and the emulator testing against staging (`ANDROID-BACKEND-COMPATIBILITY-AUDIT.md`, `ANDROID-EMULATOR-INTEGRATION-TEST-REPORT.md`). The Android SDK fixes for 1.1.6 don't depend on either task. Task 1, however, blocks real Android deferred attribution for some apps.

---

## Task 1 — Android landing page: fall back to Google Play when deep linking is disabled

**Priority:** high. For affected apps, users never reach the install step.

**Problem.** The Android fingerprint landing page (`/fp/{token}`) POSTs `/fingerprint`. The response contains:
- `deepLink`: the Google Play URL whose `referrer` carries `utm_source`, `utm_medium`, `click_id` and `fpid`;
- `intentLink`: only set when the application has `android_deep_linking=true`.

The page only ever navigates to `intentLink`, and its `window.location` redirect is commented out. So when `android_deep_linking=false`:
- the page stays on the landing screen and never opens Google Play;
- the **Open** button re-POSTs `/fingerprint`, creating another fingerprint row on every tap;
- because the app is never installed from Play with the `fpid` referrer, deferred attribution can't start.

**Evidence**
- `resources/js/Pages/Fingerprint/Android.tsx`: lines 71–77 (only `intentLink` is used), lines 174–181 (button behavior).
- `app/Http/Controllers/FingerprintController.php`: lines 226–229 (`intentLink` built only when `android_deep_linking`).
- Observed three times on staging (`nta.staging.tapp.so`, `android_deep_linking=false`) in the emulator's Chrome: after `POST /fingerprint` 200, the final location stayed on `/fp/<token>`.

**Requested change.** When `intentLink` is empty, navigate to `deepLink` (Google Play with the referrer). Make the button open `deepLink` too, instead of re-sending the fingerprint.

**Acceptance**
- On Android, an app with `android_deep_linking=false` opens its Google Play listing with `referrer` containing `fpid` after one fingerprint POST.
- Repeated button taps don't create extra fingerprint rows.
- Apps with `android_deep_linking=true` keep the current intent behavior.

**Also check:** confirm which production applications currently have `android_deep_linking=false`. They are affected today.

---

## Task 2 — `/v1/ref/fingerprint`: whitelist the real Android SDK fields and add dedicated no-match error codes

**Priority:** medium. Nothing is broken today, but this must happen before any validation hardening.

### 2a. Field whitelist (coupling risk)

`FingerprintController` calls `SanitizationHelper::validateNoExtraKeys()` before `$validator->fails()`. Laravel's `Validator::passes()` resets the message bag, so the "unexpected key" errors are discarded and the check has no effect today. The Android SDK (1.1.5 and 1.1.6) sends 21 fields, many of them not in `androidRules()`:

```
tapp_token, bundle_id, advertising_id, fp, platform, os_version, device_model,
device_manufacturer, screen_resolution, screen_density, locale, timezone, referrer,
clickId, device_id, battery_level, isCharging, total_ram_bytes, total_storage_bytes,
avail_storage_bytes, device_uptime_ms
```

The backend also copies them under `webview.*` when no `webview` object is sent. Matching uses `referrer` (`fpid`, `click_id`) and the hash of `platform`, `screen_density`, `locale` and `timezone`. The SDK must keep sending these fields.

**Requested change**
- Before making the extra-key check effective, add all of the fields above, top-level and under `webview.*`, to the Android rules. Alternatively, ignore unknown keys explicitly. Do not reject the current SDK payload.
- Add a feature test that posts the real Android SDK payload above to `/v1/ref/fingerprint` and asserts it is not rejected with `VALIDATION_ERROR`.

**Acceptance:** the current Android SDK payload passes validation, and the test fails if a future rule change would reject it.

### 2b. Dedicated no-match error codes

The Android SDK now treats these responses as "this install is not attributable", stops fingerprinting on later launches, and recognizes them by **status + `error_code` + exact message**:

| Status | `error_code` | `message` |
|---|---|---|
| 404 | `NOT_FOUND` | `Data miss-match` |
| 422 | `VALIDATION_ERROR` | `Fpid does not exist` |

`NOT_FOUND` is also used for `"Application not found."`, which is a configuration problem the SDK must **not** treat as terminal.

**Requested change**
- Add distinct codes, for example `FINGERPRINT_NO_MATCH` (instead of `NOT_FOUND` for "Data miss-match") and `FINGERPRINT_MISSING_FPID` (for "Fpid does not exist").
- **Keep the current messages** and HTTP statuses during the transition. A later SDK release can then match on the new codes alone.
- Tell the SDK team before changing any of these messages. A changed message makes current SDKs treat the outcome as non-terminal (safe, but it brings back repeated fingerprinting).

**Acceptance:** each case returns a distinct `error_code`, the messages are unchanged, and "Application not found." keeps a different code.

---

## Related observation (lower priority)

`GET /fp/{token}` renders the full `Application` model as public page props, including owner and team ids, signing certificate fingerprints and store links. Pass only the fields the landing page displays.
