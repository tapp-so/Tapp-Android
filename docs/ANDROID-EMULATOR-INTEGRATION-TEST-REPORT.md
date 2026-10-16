# Tapp Android SDK — Emulator Integration Test Report (staging)

| | |
|---|---|
| **Date** | 2026-10-13 |
| **SDK** | branch `fix/android-backend-compatibility-1.1.6` on `77b5031` (F-01/F-02/F-03 patch; the `INTERNET` permission fix followed, see §I), version still `1.1.5` |
| **Backend** | SANDBOX `https://api.staging.tapp.so/v1/ref/*`, landing domain `nta.staging.tapp.so` |
| **Device** | Android Emulator, Pixel 8 Pro profile |
| **Scope** | Staging and local emulator only. No production endpoints or credentials used. |

**Verdict:** the patched SDK works against the real staging backend for every SDK operation that could be exercised. F-01 and F-02/F-03 behave as designed on the device, and the old 1.1.5 behavior was reproduced on the same backend for a before/after comparison. **APPROVE** the patch for commit. **Release validation is not complete**: a genuine Google Play install with a Play-delivered referrer has not been tested (§G).

---

## A. Emulator environment

| Item | Value |
|---|---|
| Device / image | `sdk_gphone64_arm64` (Pixel 8 Pro profile), `google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.036`, user build |
| Android | 15, **API 35** |
| Google Play | Play Store `53.3.21-34` and Google Play services `26.11.33` installed; one Google account signed in |
| Play Install Referrer | **Available.** For adb-installed apps, Play answers `OK` with a referrer whose parameter names are exactly `[utm_source, utm_medium]` (organic, no `fpid`). |
| Browser | Chrome `124.0.6367.219` |
| Sample app | `com.example.tapp` was not installed before testing. Installed from this branch: flavors `nativeDebug` (main) and `adjustDebug`, both with `installerPackageName=null` (sideloaded). **`nativeDebug` is left installed**, freshly reinstalled and in the organic-processed state. |
| Staging config | The sample app's SANDBOX credentials resolve to the staging application **"Native test app"** (`android_bundle_id=com.example.tapp`, host `https://nta.staging.tapp.so`, `android_deep_linking=false`). It is a dedicated test app, so the controlled writes in §C stay within it. |
| Logging | The sample runs in SANDBOX, so SDK logs are enabled (`Tapp-*` lines in logcat `System.out`) and are redacted by the SDK. All evidence below uses only those lines and test-reported summaries. No tokens, secrets, advertising IDs or ANDROID_IDs were printed. |
| Pre-existing emulator state (left untouched) | Chrome already had two tabs not opened by this testing: a Play **internal-test opt-in** page (`play.google.com/apps/internaltest/…/join`) and a landing page for the supplied link (`/fp/3h6mU82h?redirect=1`). Both were still present with the same URLs at the end. |

### How the tests were run

1. **The real sample app** (Native and Adjust flavors), driven through its UI and lifecycle with `adb`.
2. **Two opt-in instrumented tests**, added as test sources only:
   - `Tapp-Native/src/androidTest/.../StagingNativeAttributionE2ETest.kt`
   - `Tapp-Adjust/src/androidTest/.../StagingAdjustE2ETest.kt`

   They run the real SDK, real AndroidKeyStore and real HTTPS against staging on the emulator. They are skipped unless credentials are passed as base64 instrumentation arguments, so CI never calls staging.
   - **Only one value is simulated: the delivery of the Play Install Referrer.** A sideloaded app cannot receive a referrer from Play, so the referrer is injected through the existing `TappNative.installReferrerSource` seam.
   - The referrer strings themselves came from the **real staging landing page**, captured over Chrome DevTools in the emulator's Chrome.
   - The instrumented tests present `com.example.tapp` as the bundle id through a `ContextWrapper`.
3. **Chrome DevTools Protocol** (`adb forward … chrome_devtools_remote`) in a dedicated tab that the script created and then closed. It recorded the real redirect chain and the `/fingerprint` XHR request and response.

---

## B. Test execution matrix

Notation: **REAL** means real app, real SDK and real staging backend. **REAL\*** means the same, except the Play referrer value was injected (it was produced by the real landing page). **JVM** means Robolectric tests only.

| # | Test | Preconditions | Actions | Expected | Actual | Evidence | Result |
|---|---|---|---|---|---|---|---|
| A1 | First cold start, Native, organic | Fresh install of `nativeDebug` | `am start` (COLD) | secrets 200; real referrer read; no `fpid` means **0 fingerprint requests**, not-attributed path, processed persisted | `/ref/secrets` 200, `has_app_token=true`. Referrer `has_value=true`, `click_id` absent. "Install Referrer has no fpid … Skipping fingerprint request" → `AppWillNotProcess` → "Updated hasProcessedReferralEngine to true". **0** fingerprint requests | `A1_first_cold_start.tapp.log` | **PASS** (REAL) |
| A2 | Force-stop and cold relaunch | after A1 | `am force-stop`; `am start` (COLD, new pid) | No referrer read, no fingerprint | "Configuration already exists" → "Already initialized"; no network | `A2_cold_relaunch.tapp.log` | **PASS** (REAL) |
| A3 | Background → foreground | after A2 | HOME, then `am start` (HOT, same pid) | No SDK work | Same pid; 0 `Tapp-*` lines | adb output | **PASS** (REAL) |
| A4 | Activity recreation | after A3 | Rotate the screen 90° and back | No SDK work, no crash | Same pid, `MainActivity` resumed, no SDK lines, no `AndroidRuntime` errors. **Correction (2026-10-15):** the rotation command used did not actually rotate the emulator, so `MainActivity` was never recreated. The observations only repeat A3 (an idle resumed app) | adb output | **NOT VERIFIED** (originally reported as PASS; no genuine rotation took place) |
| A5 | Influencer URL generation (`url()`) | Native sample started | Type `android-sdk-emulator-qa-20261013`, tap **Generate URL** | `/ref/influencer/add` 200, URL parsed and shown | 200; UI shows `https://nta.staging.tapp.so/android-sdk-emulator-qa-20261013?t=OIEuwQrc` | logcat, UI dump, screenshot | **PASS** (REAL, staging write) |
| A6 | Event via sample button | Native sample started | Tap **Track Event** (`tapp_begin_tutorial`) | `/ref/event` 2xx, SDK reports success | 201; "Event tracking succeeded" | logcat | **PASS** (REAL, staging write) |
| B1 | Supplied link: identify the app | — | Read-only `GET /fp/auditprobe` render and `/.well-known/assetlinks.json` | Find the owning app | "Native test app", `com.example.tapp`, `android_deep_linking=false`; assetlinks 404 | curl | **PASS** (read-only) |
| B2 | Supplied link `…/js?t=3h6mU82h` in browser | — | **Not clicked during testing** (it records a click); its landing page was already open in Chrome from earlier activity | — | Same app and flow as the fresh link (B3) | — | **NOT VERIFIED** (deliberately skipped; B3 used instead) |
| B3 | Fresh link, real browser flow | Link from A5 | Chrome (CDP) → open link | Redirect into the fingerprint landing page; fingerprint stored; Play link with `fpid` referrer | 302 → `/?t` → 302 `/fp/OIEuwQrc?redirect=1` → 200 → XHR `POST /fingerprint` 200 returns `deepLink` (Play URL) with referrer params `utm_source, utm_medium, click_id(8), fpid(36)` and **empty `intentLink`**. **The page never navigates on to Play** (final location stays `/fp/…`) | `click1.events.txt` | **PASS** for the data contract; **FAIL** for the user journey (backend/web finding E-1) |
| B4 | `example://` app-scheme deep link | — | — | — | Not run. The sample declares `example://` but its `MainActivity` doesn't forward intents to the SDK, and Native `Tapp` has no `appWillOpen`. Nothing SDK-related to observe | source | **NOT APPLICABLE** |
| C1 | Fresh attribution (Scenario C) | Real `fpid` from browser click #1; clean SDK state | Instrumented `attributedInstall` | fingerprint match; impression counted; deferred link delivered; processed; no repeat on relaunch | fingerprint **200** (matched click #1's `fpid`), deeplink **200** counted (link and token saved), `didReceiveDeferredLink` once (`error=false`, `isFirstSession=true`, influencer and `tapp_url` present), processed persisted; relaunch made **no** attribution attempt | `C1_attributed.instr.log` | **PASS** (REAL\*, staging write) |
| C2 | Link data after attribution | C1 | `fetchLinkData(deliveredLink)`, `fetchOriginalLinkData()` | 200, parsed, `error=false` | 2× `/ref/linkData` 200, `error=false`, influencer and `tapp_url` present. `deepLink` is the sentinel "deepLink was not returned" (audit F-06, confirmed on real data) | same | **PASS** (REAL) |
| C3 | Uniquely named event through the SDK | C1 (saved deep link becomes `event_url`) | `handleTappEvent(custom("android_sdk_emulator_qa_20261013"), metadata)` | 2xx, persisted server-side | `/ref/event` **201**; insights (read) shows exactly **1** event `android_sdk_emulator_qa_20261013`, `os=android`, created 17:36:40 UTC | logcat + insights | **PASS** (REAL, staging write) |
| D1 | **F-01** already-counted impression, patched SDK | Browser click #2 (new real `fpid`), same device ANDROID_ID, cleared SDK state (simulated reinstall) | Instrumented `attributedInstall` | 200 `error:true` handled as non-fatal; link delivered; processed; no repeat | fingerprint 200 → deeplink **200, already counted** (link not saved; this branch runs only when `error:true`) → `didReceiveDeferredLink` delivered, **no** `didFailResolvingUrl`, processed persisted; relaunch made no attempt | `D1_already_counted.instr.log` | **PASS** (REAL\*) |
| D0 | **F-01** same scenario on **1.1.5** impression handling (control) | Browser click #3; the F-01 line temporarily reverted only in the test build, then restored (`cmp` verified) | Same test | 1.1.5 fails | deeplink **200**, yet `didFailResolvingUrl: [Impression already incremented for this android device.]`, no link delivered | `D0_115_already_counted.instr.log` | **Regression reproduced on real staging**, as expected |
| I1 | Real Play referrer of an adb install | — | `InstallReferrerClient` from the test app | — | `OK`, params `[utm_source, utm_medium]`, no `fpid` | `I1_real_referrer` | **PASS** (REAL) |
| I2 | Organic referrer | clean state | Instrumented | 0 fingerprint, processed, no repeat | secrets 200, **0** fingerprint, processed; relaunch made no attempt | `I2_organic.instr.log` | **PASS** (REAL\*) |
| I3 | Backend `404 Data miss-match` | Unknown `fpid` and `click_id` | Instrumented | 1 request, terminal, no repeat | **1** request → real 404 `NOT_FOUND "Data miss-match"` → processed; relaunch made no attempt | `I3_no_match_404.instr.log` | **PASS** (REAL\*) |
| I4 | `422 Fpid does not exist` with retrieved referrer | Referrer contains the text "fpid" but no key | Instrumented | 1 request, terminal | **1** request → real 422 → processed | `I4_….instr.log` | **PASS** (REAL\*) |
| I5 | `422 Fpid does not exist` with **unavailable** referrer | `Unavailable` (payload `"null"`) | Instrumented | 1 request, **not** terminal | **1** request → real 422 → `MISSING_FPID` → stop; **not processed** | `I5_….instr.log` | **PASS** (REAL\*) |
| I6 | `404 Application not found.` | SDK bundle id `com.example.tapp.qa.unregistered` | Instrumented | 1 request, **not** terminal | **1** request → real 404 → `NON_RETRYABLE`; **not processed** | `I6_….instr.log` | **PASS** (REAL\*) |
| J1 | Adjust sample start | `pm clear` (sample only), install `adjustDebug` | `am start` | secrets `mmp=1` 200; Adjust initialized (sandbox); no crash | `/ref/secrets` 200 → "Adjust initialization submitted"; Adjust: SANDBOX mode, "Install tracked", "Click tracked", "Attribution found" | `J1_adjust_first_start.full.log` | **PASS** (REAL, Adjust sandbox) |
| J2 | Adjust adapter: failed impression is a failure | Adjust `Tapp`, staging | `appWillOpen(…?adj_t=qaunknown1)` | completion failure, `didFailResolvingUrl`, not processed | deeplink **404** "Deep link does not exist." → completion `failure(AffiliateServiceError)`, 1 failure callback, not processed | `J_failedImpression….instr.log` | **PASS** (REAL) |
| J3 | Adjust adapter: link data | Adjust `Tapp` | `fetchLinkData(…?adj_t=OIEuwQrc)` | 200 parsed | linkData **200**, `error=false` | `J_linkData….instr.log` | **PASS** (REAL) |
| J4 | Adjust adapter: successful or already-counted impression | — | — | — | Not possible without an `adj_t` creative link, and creating one makes `/influencer/add` with `mmp=1` create **Adjust trackers through the Adjust API** (external writes), so it was avoided. Covered by Robolectric (`ImpressionHandlingTest`) | — | **NOT VERIFIED** on device (JVM only) |
| L1 | Uninstall and reinstall (Native) | after J | `adb uninstall`, `install`, launch | State cleared → fresh secrets → organic terminal | secrets 200, referrer without `fpid` → **0** fingerprint → processed | `L1_reinstall_native.tapp.log` | **PASS** (REAL) |
| L2 | Cold relaunch after reinstall | L1 | force-stop, launch | No attempt | "Already initialized" | `L2_relaunch.tapp.log` | **PASS** (REAL) |
| T1 | 401/403 on `/ref/fingerprint` | — | — | — | Cannot be produced safely: the same token authenticates `/ref/secrets` first. Covered by Robolectric | — | **NOT VERIFIED** on device (JVM) |
| T2 | 429 / 5xx / IO / timeout / cancellation, other 422 | — | — | — | Not generated (would need a staging outage or network disruption, which is prohibited). Covered by Robolectric (retry once / no mark / cancellation propagated) | — | **NOT VERIFIED** on device (JVM) |

Evidence files (logcat captures, DevTools captures, UI dumps) were kept locally during testing and are not committed.

**Thread check.** `didReceiveDeferredLink` and `didFailResolvingUrl` are delivered from main-thread code paths: `handleDeferredLinkResponse` posts to the main Looper, and the impression callback uses `withContext(Dispatchers.Main)`. The sample's dialogs rendered without `CalledFromWrongThreadException`. The instrumented tests waited on latches from the instrumentation thread, and no deadlock occurred.

---

## C. Backend API matrix

| Endpoint | Real staging request sent | Succeeded | Initiated by the SDK | Changed staging data | Callbacks and parsing correct |
|---|---|---|---|---|---|
| `/ref/secrets` | Yes (every fresh start: A1, I2–I6, C1, D1, J1, L1) | Yes, 200 (`mmp=3` and `mmp=1`) | Yes | No | Yes: app token parsed (`has_app_token=true`); Native and Adjust initialized |
| `/ref/fingerprint` | Yes (C1, D1, D0, I3–I6) | 200 match (C1, D1, D0). Expected 404/422 outcomes (I3–I6) | Yes | Yes: marked the matched web fingerprint rows (clicks #1–#3) `verified=true`. Misses write only backend log lines | Yes: match → listener → impression flow. 404 no-match and 422 (retrieved) → terminal. 422 (unavailable) and 404 application-not-found → not terminal. **Exactly 1 request each** (no retry on 4xx) |
| `/ref/deeplink` | Yes (C1, D1, D0, J2) | 200 counted (C1); **200 `error:true` already-counted** (D1, D0); 404 (J2) | Yes | Yes: one impression counted for the test link plus one `android_ids` row for the emulator's ANDROID_ID (C1). Already-counted and 404 calls write nothing | **Yes with the patch** (D1 delivers the link). 1.1.5 reproduced as a failure (D0). 404 → failure (J2) |
| `/ref/linkData` | Yes (C2 ×2, D1, J3) | Yes, 200 | Yes (`fetchLinkData`, `fetchOriginalLinkData`, internal) | No | Yes: `error=false`, influencer and `tapp_url` parsed. `deepLink` is a sentinel string (F-06, pre-existing) |
| `/ref/event` | Yes (A6, C3) | Yes, 201 | Yes (`handleTappEvent`) | Yes: 2 events (`tapp_begin_tutorial`, `android_sdk_emulator_qa_20261013`) | Yes: SDK logged success. The unique event was verified server-side through insights |
| `/ref/influencer/add` | Yes (A5) | Yes, 200 | Yes (`url()` from the sample UI) | Yes: 1 influencer `android-sdk-emulator-qa-20261013` plus its link | Yes: `influencer_url` parsed and shown |

### Full inventory of staging writes made by this testing (staging test app only)

| What | Count / detail |
|---|---|
| Influencer created | 1: `android-sdk-emulator-qa-20261013`, link token `OIEuwQrc` |
| Link clicks + web fingerprint rows | 3 (browser clicks #1–#3), all later consumed as `verified` |
| Counted impressions | 1, plus 1 `android_ids` row |
| Already-counted impression calls | 2 (D1, D0); no increment |
| Events | 2 |
| Adjust sandbox sessions | From the `adjustDebug` sample and the Adjust instrumented test runs (Adjust **sandbox** environment, not production). No Adjust events were sent |
| Insights query | 1 (read; consumes one API-usage unit) |
| Backend logs | `*_fp.log` and `*_impressions.log` entries for every call |

The supplied link `3h6mU82h` was **not** clicked. No production endpoint was called.

---

## D. Deep-link and attribution results

- **Supplied link** `https://nta.staging.tapp.so/js?t=3h6mU82h` → brand host `nta` → route `/{influencer}` (`DeepLinkController@deepRedirect`, which **records a click**) → `creative_link` → `/?t=…` → `/fp/{t}?redirect=1`.
  - It belongs to **"Native test app"** (`com.example.tapp`), the sample app's own staging app.
  - It is **not** an Android App Link: `android_deep_linking=false`, and `assetlinks.json` returns 404.
  - Its Android destination is a Play listing for `com.example.tapp`, which is not a public Play app.
- **Fresh link**: `https://nta.staging.tapp.so/android-sdk-emulator-qa-20261013?t=OIEuwQrc` (test id `android-sdk-emulator-qa-20261013`), generated through the SDK's `url()`.
- **Browser flow observed three times:**
  - 302 → 302 → landing page 200 → `POST /fingerprint` 200.
  - The XHR body keys are `fp, uuid, webview{clickId, locale, platform, referrer, screen_density, timezone}`.
  - The response keys are `deepLink, fingerprint, intentLink`, with `intentLink` empty.
  - `deepLink` is `play.google.com/store/apps/details` with params `[id, referrer]`.
- **Raw Play referrer parameter names:** `utm_source`, `utm_medium`, `click_id` (8 chars), `fpid` (36-char UUID). Each click produced a different `fpid`. The SDK's `fpid` check and the backend's `parse_str(...)['fpid']` both match this format.
- **Fingerprint result:** all three real `fpid`s matched on the first request (200 with `deeplink`, `tapp_url`, `influencer`).
- **SDK callbacks:**
  - Counted impression (C1): `didReceiveDeferredLink`, link and link token saved.
  - Already-counted (D1): `didReceiveDeferredLink`, link not saved (identical to 1.1.4 behavior).
  - 1.1.5 control (D0): `didFailResolvingUrl`.

---

## E. Review findings

No P0 or P1 defects were found in the patch. No production code changed during this phase.

| ID | Sev | Area | Finding | Evidence | Action |
|---|---|---|---|---|---|
| E-1 | **P1 (backend/web, not SDK)** | Android attribution journey | For apps with `android_deep_linking=false` (such as the staging test app), the Android landing page **never sends the user to Google Play**. It only follows `intentLink`, which is empty, and ignores the returned `deepLink`. The **Open** button then re-POSTs the fingerprint, creating another unmatched row on every tap. A real user can never reach the install step, so deferred attribution can't begin for such apps. | `resources/js/Pages/Fingerprint/Android.tsx:71-77` (only `intentLink` used; the `location.href` redirect is commented out), `:174-181`; `app/Http/Controllers/FingerprintController.php:226-229` (intent only when `android_deep_linking`). Observed: final location stayed on `/fp/OIEuwQrc` in all 3 runs | Backend task: fall back to `deepLink` when `intentLink` is empty |
| E-2 | P2 (SDK, pre-existing = audit F-12) | Packaging | Tapp-Core and Tapp-Native **don't declare `INTERNET`**. Confirmed on the device: the test APK without it got `SecurityException` on every SDK request, and the SDK only logs a warning (production logging is off). Adjust integrators get the permission transitively from the Adjust AAR; **Native-only integrators don't**. | Device logs from the first I2–I6 attempt (discarded as invalid and rerun); release AAR manifest had no `INTERNET` | **Fixed afterwards**: Tapp-Core now declares `INTERNET`, verified with an isolated Native-only consumer (§I) |
| E-3 | P3 (SDK, pre-existing) | Adjust | `Adjust.initSdk` is called **twice** per process (at `start()` and again in `appWillOpen`). `AffiliateServiceFactory` builds a new service instance on every lookup, so `isEnabled()` is always false. Unchanged since 1.1.5. | `AffiliateServiceFactory.kt:22-25`; J2 log shows "Adjust initialization submitted" ×2 | Follow-up |
| E-4 | P3 (SDK, pre-existing = F-06) | Link data | Real `/ref/linkData` has no `deepLink`, so the SDK returns the sentinel string "deepLink was not returned". | C2, D1, J3 | Follow-up |
| E-5 | P3 (backend) | Information exposure | `GET /fp/{anything}` renders the full `Application` record as public page props: `uid`, `team_id`, store links, `android_sha256_certificates`, app ids. | `FingerprintController.php:48-53`; B1 | Backend task: pass only the display fields |
| E-6 | Info | Test infrastructure | The first device run of I2–I6 passed vacuously: no `INTERNET`, so the SDK never reached the backend. Fixed at the time with a temporary test-only `androidTest` manifest plus a guard that fails the test unless `/ref/secrets` succeeded. All reported results are from the rerun. After the E-2 fix the test-only manifest was removed; the permission now comes from Tapp-Core (§I). | — | Done |

### Attribution edge cases investigated

| Question | Evidence | Conclusion |
|---|---|---|
| Can `404 Data miss-match` be a temporary matching delay? | The web flow inserts the fingerprint row synchronously before it returns the Play link (`AndroidHelper::storeFingerprint`; no queue dispatch in the fingerprint code). `config/database.php` has no read/write split, so reads and writes hit the primary. The real run (C1) matched click #1 well after the click. | **No evidence of delay.** Keep the 404 as terminal. |
| Does the backend record fingerprints asynchronously? | Same as above; no `dispatch`/`ShouldQueue` in the fingerprint path. | No. |
| Should Play `OK` with a null referrer be terminal? | Play returned `OK` with a non-empty organic value for every adb install on this device. A Tapp-driven install always carries the referrer the landing page builds. | Low risk; keep. |
| Does the `fpid` detection handle the supported formats? | All three real referrers were `utm_source=tapp&utm_medium=app&click_id=…&fpid=<uuid>`. The organic real referrer had no `fpid`. The detection is a case-insensitive substring check, so it can only err toward sending a request. | Correct. |

### Test hooks (`TappNative`)

`installReferrerSource`, `lastInitJob` and `resetForTesting()` are at `TappNative.kt:64`, `:69` and `:72`.
- They are Kotlin `internal` and `@VisibleForTesting`, so they appear in the release bytecode under module-mangled names. Minification is off in this project, so they aren't stripped.
- Production code never writes `installReferrerSource` or calls `resetForTesting()`.
- `lastInitJob` is written in production (`:87`). It holds one reference to the launched `Job`, which is negligible and `@Volatile`.

No lifecycle or behavior effect found. Acceptable.

### Regression review against 1.1.5

The production Kotlin diff is unchanged from the reviewed patch (3 files). The later `INTERNET` manifest line is covered in §I.
- **Response validation:** `BackendResponseParser` is untouched, non-2xx and `success:false` are still rejected everywhere, and `rejectLegacyError=false` applies only to `/ref/deeplink` (`TappAffiliateService.kt:139`).
- **Auth headers, all request payloads** (the 21-key fingerprint body was verified on device and in JVM tests) and **log redaction** are unchanged.
- New log lines carry only enum names and booleans.
- Every pre-existing working flow (secrets, events, URL generation, link data, Adjust init) behaved on the device as in 1.1.5.

---

## F. API compatibility decision — **APPROVED**

The patch adds two public declarations to Tapp-Core: `NativeApiService.DeferredLinkFailure` (enum) and `NativeApiService.classifyDeferredLinkFailure(Throwable)`. Both are `@RestrictTo(LIBRARY_GROUP)`.

- **Why a public, typed contract:** Tapp-Native cannot see Tapp-Core's `internal` `BackendApiException`. The only alternative is parsing exception message strings inside Native, which is brittle across versions. A typed enum is the safer contract.
- **Restriction validity:** JitPack publishes both artifacts under the **same group** `com.github.tapp-so.Tapp-Android` (verified in the published POMs). `LIBRARY_GROUP` therefore allows Native→Core use and flags any integrator use through lint (`RestrictedApi`). Project lint reports no `RestrictedApi` hit.
- **Source and binary compatibility:** purely additive. No existing class, method, field or signature changed, so it is binary-compatible for Java and Kotlin consumers.
- **Version coupling:** Native ≥ the next release calls the new Core method. Native's POM declares `Tapp-Core:<same version>` at compile scope, so normal Gradle resolution picks the matching Core. Only a consumer who forcibly downgrades Core below the version Native needs would see `NoSuchMethodError`, and that isn't a supported setup.
- **Caveat:** `@RestrictTo` is enforced by lint only, not by the compiler. Document the two symbols as internal in the release notes.

---

## G. Remaining limitations

1. **Genuine Google Play install (Play-delivered referrer) — not verified.** This needs the patched SDK in an app on a Play testing track, installed from Play after a link click. Every attribution run here used real backend responses and real landing-page referrers, but **injected** the referrer value (REAL\*).
   - The emulator has a Play internal-test opt-in page open, so a track may already exist for some build. It would have to carry this patch to be meaningful.
   - **Backend issue E-1 blocks this journey** for apps with `android_deep_linking=false`.
2. **Adjust:** genuine Adjust deferred deeplinks, and the successful or already-counted Adjust impression (an `adj_t` creative link needs Adjust trackers created through the Adjust API) — JVM tests only.
3. **401/403, other 422, 429, 5xx, IO/timeout and cancellation on `/ref/fingerprint`** — JVM tests only. These weren't safe to produce on shared staging.
4. **Physical device:** not required for anything verified here. A real device on a Play track is the right place to close item 1.

---

## H. Final recommendation — **APPROVE**

- **Ready for committing:** yes. On-device testing required no change to the F-01/F-02/F-03 code. The one follow-up change, the `INTERNET` declaration (E-2), is verified in §I. Leave out `app/.DS_Store`.
- **Release validation complete:** **no.** Before tagging 1.1.6:
  - run the Play-track check in G-1;
  - have the backend team fix E-1, or confirm which production apps have `android_deep_linking=true` (see `ANDROID-BACKEND-FOLLOW-UP-TASKS.md`).

### Validation run before the `INTERNET` fix (final results in §I)

| Command | Result |
|---|---|
| `./gradlew test --console=plain --continue` | BUILD SUCCESSFUL: 108 tests, 0 failures, 0 skipped |
| `./gradlew assembleDebug assembleRelease --console=plain --continue` | BUILD SUCCESSFUL |
| `./gradlew :Tapp-Native:assembleDebugAndroidTest :Tapp-Adjust:assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| `./gradlew lint --console=plain --continue` | BUILD SUCCESSFUL; issues identical to the audit baseline (0 errors) |
| `./gradlew generatePomFileForReleasePublication` | BUILD SUCCESSFUL. POMs unchanged; no Robolectric or androidx.test leakage; release AARs contain no test classes |

---

## I. `INTERNET` permission fix (E-2) and final validation

**Change:** `Tapp-Core/src/main/AndroidManifest.xml` now declares `<uses-permission android:name="android.permission.INTERNET" />`. No other permissions changed.

Before the change, no SDK module declared `INTERNET` (checked in all four release AAR manifests). It didn't arrive through any other Core configuration either. Adjust apps received it only from the Adjust SDK's own AAR.

### Isolated Native-only consumer (outside the repository, not committed)

A minimal app (`com.tapp.qa.consumer`) depending **only** on `com.github.tapp-so:Tapp-Native`, resolved to this checkout through a Gradle composite build. Its own manifest declares **no** permissions.

| Check | Before the fix | After the fix |
|---|---|---|
| Merged manifest contains `INTERNET` | No (only `AD_ID`, the receiver permission and the Play referrer service permission) | **Yes, exactly once** |
| Origin (manifest-merger report) | — | `ADDED from [:Tapp-Android:Tapp-Core]` (the production library manifest) |
| Granted on device | — | `android.permission.INTERNET: granted=true` |
| HTTPS from the SDK on the emulator | **Fails:** `POST request failed locally: SecurityException`, "Failed to initialize referral engine: Permission denied (missing INTERNET permission?)" | **Works:** `/ref/secrets` 200, then the full Native flow (real Play referrer, no `fpid`, 0 fingerprint requests, processed) |
| Needs Adjust to supply the permission | — | No (the consumer has no Adjust dependency) |

### Existing consumers (sample app)

Both flavors build. Each merged manifest has **one** `INTERNET` entry and no merge conflicts:
- **Native:** the app's own manifest plus `[:Tapp-Core]`.
- **Adjust:** the app's own manifest plus `[:Tapp-Core]` plus `[com.adjust.sdk:adjust-android:5.0.0]`.

### Regression checks added

- **`Tapp-Core/src/test/.../CoreManifestTest`** (JVM, runs in every `./gradlew test`) asserts that the production manifest declares `INTERNET`. Tapp-Core's unit-test tasks declare the manifest as an input, so a manifest change can't leave the test skipped as up-to-date. Mutation check: removing the line makes it fail (`CoreManifestTest.kt:24`).
- **`Tapp-Native/src/androidTest/.../SdkPermissionsTest`** (device, no credentials) asserts that the Tapp-Native test APK requests and is granted `INTERNET`. The temporary test-only `androidTest` manifest was **removed**, so the permission can only come from the merged SDK library manifests. Mutation check: with the Core line removed, the device test failed (requested permissions had no `INTERNET`).

### Final validation (after all changes)

| Command / run | Result |
|---|---|
| `./gradlew test --console=plain --continue` | BUILD SUCCESSFUL: **110 tests, 0 failures, 0 skipped**. Every Robolectric class executed: `ImpressionHandlingTest` 13 and `NativeAttributionFlowTest` 19, per variant |
| `./gradlew assembleDebug assembleRelease --console=plain --continue` | BUILD SUCCESSFUL |
| `./gradlew :Tapp-Native:assembleDebugAndroidTest :Tapp-Adjust:assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| `./gradlew lint --console=plain --continue` | BUILD SUCCESSFUL; issues identical to the audit baseline (0 errors) |
| `./gradlew generatePomFileForReleasePublication` | BUILD SUCCESSFUL. POMs unchanged; no test dependencies; only the Tapp-Core release AAR declares `INTERNET` |
| Device: `SdkPermissionsTest` | OK |
| Device: staging scenarios I2–I6, rerun on the final build with the permission coming only from Tapp-Core (REAL\*, non-mutating) | All OK, each with its result line printed (the test body ran). Organic: 0 fingerprint requests, processed. `404 Data miss-match`: 1 request, terminal. `422` with retrieved referrer: 1 request, terminal. `422` with unavailable referrer: 1 request, not terminal. `404 Application not found.`: 1 request, not terminal |
| Device: staging test without credentials | Reported as an assumption skip (`AssumptionViolatedException`, status `-4`). AndroidJUnitRunner still prints "OK", so only the `TappE2E` result lines prove execution |

Scenarios C1/D1/D0 (attribution and already-counted) and J1–J3 (Adjust) weren't rerun after this change. That would mean new staging clicks and Adjust sandbox sessions, and the code they exercise hasn't changed since they passed.

**Emulator state after testing:** only the sample app `com.example.tapp` (Native flavor) remains installed. The test APKs and the QA consumer were uninstalled.
