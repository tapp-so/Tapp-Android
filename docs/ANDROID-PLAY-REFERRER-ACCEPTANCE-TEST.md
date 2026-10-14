# Android SDK — Genuine Google Play Install-Referrer Acceptance Test (1.1.6 candidate)

**Purpose.** Prove end to end that a build containing the patched SDK, **installed from Google Play**, receives the Play Install Referrer produced by a staging Tapp link, matches it on the backend, delivers the deferred deep link and persists the result.

Every attribution test so far used real staging responses but an **injected** referrer value (see `ANDROID-EMULATOR-INTEGRATION-TEST-REPORT.md`). An injected referrer does **not** prove Play attribution, and nothing in this document should be read as already verified.

**Status:** not executed yet. The blockers in §1 must be cleared first. Nothing has been uploaded to Google Play.

---

## 1. Current blockers (verified 2026-10-14)

| # | Blocker | Evidence | What clears it |
|---|---|---|---|
| P-1 | The sample's application ID is **`com.example.tapp`**, and Google Play does not accept `com.example` package names. The sample can't be on any Play track, and the staging test app's store link (`details?id=com.example.tapp`) can't resolve to a Play listing. | `app/build.gradle.kts` (`applicationId = "com.example.tapp"`); staging app "Native test app" is registered with `android_bundle_id=com.example.tapp` | A dedicated QA package owned in Play Console (§2), registered on staging |
| P-2 | **Backend task 1**: when `android_deep_linking=false`, the Android landing page never navigates to Google Play. It only follows `intentLink`, which is empty, and ignores the returned `deepLink`. | `ANDROID-BACKEND-FOLLOW-UP-TASKS.md`, Task 1; reproduced 3× on staging | Backend fix, **or** enable Android deep linking on the staging QA app (then the intent's `browser_fallback_url` is the Play link), **or** the documented manual workaround in step 3 |
| P-3 | No upload signing is configured for the sample. Release outputs are unsigned, and the repo has no keystore. | `app/build.gradle.kts` has no `signingConfigs`; release APK is `*-unsigned.apk` | Use the Play Console app's upload key, kept outside the repository (§2.4) |
| P-4 | `versionCode` is fixed at `1` in the repo. | `app/build.gradle.kts` | Out-of-repo override (§2.3); every upload needs a higher value |
| P-5 | Tester eligibility is unknown. The emulator's Chrome has an open internal-test opt-in page, but **which app it belongs to could not be determined** without reloading that tab, and it can't be the `com.example.tapp` sample (P-1). | Chrome tab `play.google.com/apps/internaltest/<id>/join` (title only `play.google.com`) | Add the test Google account to the QA app's internal-testing list in Play Console and opt in through **that** app's link |
| P-6 | The sample has no App Link for the staging host. Its manifest only declares the `example://` scheme, and `nta.staging.tapp.so/.well-known/assetlinks.json` returns 404. | `app/src/main/AndroidManifest.xml`; staging | Not needed for referrer attribution; only relevant if P-2 is solved by enabling deep linking (see step 3) |

---

## 2. Preparing the QA build (no repository changes)

### 2.1 Choose and register the QA identity

1. Create a Play Console app with a **real, Tapp-owned package name** (for example `so.tapp.sdk.qa.nativeapp`; the team should pick it). Use an **internal testing** track. Don't use internal app sharing, which isn't a normal Play install for this purpose.
2. On staging, register an application in the **test tenant** with:
   - `android_bundle_id` = the QA package;
   - `android_store_link` = `https://play.google.com/store/apps/details?id=<QA package>`;
   - Tapp (Native) as the MMP.

   Either reuse the sample's SANDBOX credentials, if that tenant is used, or issue dedicated QA credentials.
3. Add the test Google account(s) to the internal-testing list and opt in through the QA app's own testing link.

### 2.2 Make sure the build uses the patched local SDK, not JitPack 1.1.5

The sample depends on the SDK modules **as Gradle projects** (`nativeImplementation(project(":Tapp-Native"))`). Verified on HEAD `f109946`:
- `./gradlew :app:dependencies --configuration nativeReleaseRuntimeClasspath` shows `project :Tapp-Native` and `project :Tapp-Core`, with **no** `com.github.tapp-so` artifacts.
- The release APK's dex contains the patched classes (`InstallReferrerResult`, `NativeApiService$DeferredLinkFailure`, `FingerprintStep`).
- The merged manifest requests `INTERNET`.

Build from the exact commit you want to test, and record its hash in the evidence.

### 2.3 Rebrand the Native sample without editing the repository

Keep this Gradle init script **outside** the repository (verified: it produces an AAB with the overridden package and versionCode and leaves the working tree untouched):

```groovy
// qa-play.init.gradle — QA only; do not commit.
allprojects {
    pluginManager.withPlugin('com.android.application') {
        def qaAppId = findProperty('qaAppId')
        def qaVersionCode = findProperty('qaVersionCode')
        if (!qaAppId || !qaVersionCode) throw new GradleException('qaAppId and qaVersionCode are required')
        androidComponents {
            onVariants(selector().withFlavor('provider', 'native')) { variant ->
                variant.applicationId.set(qaAppId as String)
                variant.outputs.each { it.versionCode.set((qaVersionCode as String).toInteger()) }
            }
        }
    }
}
```

```bash
./gradlew -I /path/outside/repo/qa-play.init.gradle \
  -PqaAppId=<QA package> -PqaVersionCode=<n higher than the last upload> \
  :app:bundleNativeRelease
```

The build must use the SANDBOX credentials (`TAPP_SAMPLE_ENV=SANDBOX` in the git-ignored `local.properties`), because the SDK only logs in SANDBOX and the test must stay on staging. The SANDBOX token is embedded in the uploaded test build, so **rotate it after testing**.

### 2.4 Sign with the upload key (outside the repository)

Sign `app/build/outputs/bundle/nativeRelease/app-native-release.aab` with the QA app's **upload key**, for example with `jarsigner`, using a keystore and passwords stored outside the repository. Play App Signing then re-signs it with the app signing key.

Don't add keystores, passwords or signing configuration to the repository.

---

## 3. Acceptance procedure

Use a device or emulator image **with Google Play**, signed in with the opted-in tester account. Capture logcat filtered to the app's PID and keep only `Tapp-*` lines. The SDK redacts tokens, IDs and referrer values in SANDBOX logs.

| Step | Action | Expected / evidence (sanitized) |
|---|---|---|
| 1 | **Fresh link.** Generate a new influencer link for the QA staging app with a recognizable id (for example `android-sdk-play-qa-<date>`), either through the app's **Generate URL** button (SDK `url()`) or the dashboard. | `/ref/influencer/add` 200; record the link (it is public). |
| 2 | **Landing redirect.** Open the link in Chrome on the test device. | Redirect chain `/<influencer>?t=` → `/?t=` → `/fp/<t>?redirect=1` → `POST /fingerprint` 200. Chrome then opens the **Play listing of the QA package**. If P-2 is unresolved, the page stays put: use the manual workaround below. |
| 3 | **Play URL and referrer.** Inspect the Play URL, either from Chrome DevTools (`POST /fingerprint` response `deepLink`) or from the intent fallback URL. | `play.google.com/store/apps/details?id=<QA package>&referrer=<URL-encoded>`. The decoded referrer has exactly `utm_source`, `utm_medium`, `click_id` and `fpid` (36-char UUID), optionally `tappin`. Record parameter **names**, the `click_id` length and a **SHA-256 prefix** (first 8 hex chars) of the `fpid`, never the raw value. **Manual workaround for P-2:** open that captured Play URL directly in Chrome on the same device and account (it is still the backend-generated URL with the real `fpid`). Note in the evidence that the workaround was used. |
| 4 | **Install from Play.** Install from the Play listing opened in step 3. **Not through ADB.** | `adb shell dumpsys package <QA package> \| grep installerPackageName` shows `com.android.vending`. |
| 5 | **First launch.** Open the app. | SDK logs: `/ref/secrets` 200 → "Install Referrer response received: has_value=true" → "Install Referrer fetched: has_click_id=true". The "has no fpid" skip line must **not** appear. |
| 6 | **Referrer matches the click.** Compare the received referrer with step 3. | The SDK deliberately doesn't log referrer values. Either (a) have the backend team confirm the `SDK_VERIFICATION_RESULT` entry with `method=fpid` in the staging `*_android_fp.log` for this time window, or (b) use a QA-only build that logs the received parameter **names** and the same SHA-256 prefix of `fpid`. Option (b) needs a small sample-only change, not yet made. The prefixes must match. |
| 7 | **Fingerprint and deferred link.** | `POST /v1/ref/fingerprint` **200** (exactly 1 request) → "Deferred link backend response received: has_tapp_url=true, has_deep_link=true" → `POST /v1/ref/deeplink` 200 → `didReceiveDeferredLink` (the sample shows the "Differed deep link received" dialog), with `error=false` and `isFirstSession=true`. |
| 8 | **Persistence.** Force-stop and relaunch (twice). | "Already initialized"; **no** Install Referrer read and **no** `/ref/fingerprint` request. Also send one event (**Track Event**); `/ref/event` 201. |
| 9 | **Organic install (separate).** On a second account or device that never clicked a link, install the same build from the Play listing opened **without** `referrer`. | Real referrer is organic (no `fpid`) → "Install Referrer has no fpid … Skipping fingerprint request" → **0** `/ref/fingerprint` requests → processed; relaunch makes no attempt. |
| 10 | **Same-device reinstall (F-01).** On the step 4–8 device: uninstall, generate a **fresh** link (new click), repeat steps 2–5 within 90 days. | `/ref/fingerprint` 200 → `/ref/deeplink` **HTTP 200 with the already-counted outcome** (ANDROID_ID is stable across reinstalls for the same signing key) → `didReceiveDeferredLink` is **still delivered** and the install is processed. The SDK doesn't save the link in this case. Record whether the backend actually returned already-counted (the backend team can confirm in `*_impressions.log`); if it counted a new impression instead, note it. |

### Sanitization rules for evidence

- Never record auth tokens, the tapp token, `secret`, ANDROID_ID, GAID, raw `fpid` or raw referrer values.
- Use parameter names, lengths, HTTP statuses and SHA-256 prefixes.
- Screenshots of the sample's deferred-link dialog show the full response object. Crop or redact any URL query values before sharing.

### Pass criteria

All of steps 4–8 and 10 pass with `installerPackageName=com.android.vending`, and step 9 shows zero fingerprint requests. Record the commit hash, QA package, versionCode, device and Play Store versions, link ids and timestamps.

---

## 4. What this test does **not** cover

- Adjust's own deferred deep links (Adjust attribution environment).
- Production backend behavior. Staging only.
- Fingerprint-endpoint failures (401/403, 429, 5xx, network loss, cancellation). These are covered by JVM tests only.
