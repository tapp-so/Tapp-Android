# Tapp Android SDK

## 1. Overview

The **Tapp Android SDK** provides mobile attribution, deep linking, event tracking, and modular MMP (Mobile Measurement Partner) integrations for Android applications.

**Key capabilities:**

- **Attribution** — Attribute app installs and deep link opens to campaigns and influencers via the Tapp referral engine.
- **Deep linking** — Handle both standard and deferred deep links with automatic URL resolution and link data retrieval.
- **Event tracking** — Track predefined and custom in-app events with optional metadata.
- **Modular MMP adapters** — Integrate with your preferred attribution provider (Adjust and Tapp Native available; AppsFlyer coming soon) by including only the adapter module you need.

The SDK is **modular by design**. The core module contains all shared logic and networking, while each MMP adapter module registers itself as an `AffiliateService` implementation. Your app never interacts with the core directly — instead, you use the `Tapp` facade class provided by the adapter module you install.

---

## 2. SDK Entry Point

The `Tapp` class is the **single entry point** for all SDK functionality. Every adapter module (Adjust, Tapp Native; AppsFlyer upcoming) ships its own `Tapp` class that wraps the internal `TappEngine` and exposes all public methods.

All SDK operations — initialization, event tracking, deep link handling, and configuration access — are performed through this class:

```kotlin
import com.example.tapp.Tapp

// Create the instance once
val tapp = Tapp(context)

// Initialize
tapp.start(config)

// Use SDK features
tapp.handleTappEvent(event)
tapp.appWillOpen(url, completion)
tapp.deferredLinkDelegate = myDelegate
```

> **Important:** Only one `Tapp` instance should exist per application. Create and store it in your `Application` class.

---

## 3. Architecture

```
Your App
 └── Tapp (facade class from adapter module)
       └── TappEngine (core logic)
             ├── NetworkManager (HTTP client)
             ├── KeystoreUtils (encrypted config storage)
             ├── AffiliateServiceFactory (adapter registry)
             │     └── AffiliateService (adapter interface)
             │           ├── AdjustAffiliateService
             │           ├── NativeAffiliateService
             │           └── AppsflyerAffiliateService (coming soon)
             └── TappAffiliateService (built-in Tapp service)
```

### Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| **Tapp-Core** | `Tapp-Core` | Core engine, networking, config storage, and the `AffiliateService` interface. You never depend on this directly. |
| **Tapp-Adjust** | `Tapp-Adjust` | Adjust SDK adapter. Provides the `Tapp` facade with full Adjust-specific methods. Depends on Tapp-Core. |
| **Tapp-Appsflyer** | `Tapp-Appsflyer` | AppsFlyer SDK adapter. (Coming soon) |
| **Tapp-Native** | `Tapp-Native` | Tapp Native adapter with built-in fingerprint-based deferred deep linking. No third-party MMP required. Depends on Tapp-Core. |

### How it works

- The **Core** module defines the `AffiliateService` interface and the `AffiliateServiceFactory` registry. The core has **zero compile-time dependencies** on any third-party MMP SDK — it depends only on the `AffiliateService` abstraction.
- Each **adapter module** contains its own `Tapp` class that creates a `TappEngine`, instantiates `Dependencies`, and registers its adapter via `AffiliateServiceFactory.register(...)`. Registration happens automatically in the `Tapp` class `init` block — developers do not need to register adapters manually.
- At runtime, `AffiliateServiceFactory` **dynamically resolves** the correct adapter based on the `Affiliate` enum value in the configuration. The factory maintains a map of `Affiliate → (Dependencies) → AffiliateService` providers and instantiates the adapter on demand.
- This design means the Core is fully **decoupled** from adapter implementations: you can swap MMP providers by changing only the Gradle dependency and the `affiliate` config value.

---

## 4. Installation

The SDK is distributed via [JitPack](https://jitpack.io). Add the JitPack repository to your project-level `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the adapter module you need to your app-level `build.gradle.kts`:

```kotlin
dependencies {
    // Choose ONE adapter module:

    // Option A: Adjust adapter
    implementation("com.github.tapp-so.Tapp-Android:Tapp-Adjust:<version>")

    // Option B: AppsFlyer adapter (coming soon)
    // implementation("com.github.tapp-so.Tapp-Android:Tapp-Appsflyer:<version>")

    // Option C: Tapp Native adapter (no third-party MMP)
    implementation("com.github.tapp-so.Tapp-Android:Tapp-Native:<version>")
}
```

> **Note:** Each adapter module transitively includes `Tapp-Core`. You do **not** need to add `Tapp-Core` separately.

### Requirements

| Requirement | Value |
|-------------|-------|
| Minimum Android SDK | 24 (Android 7.0) |
| Target / Compile SDK | 34 |
| Kotlin | 1.9.0+ |
| Java compatibility | 11 |
| Gradle Plugin | 8.7.3+ |
| Serialization Plugin | `org.jetbrains.kotlin.plugin.serialization` 1.8.0+ |

---

## 5. Initialization

Initialize the SDK **once** in your `Application.onCreate()` method.

### Step 1: Create a configuration

```kotlin
import com.example.tapp.models.Affiliate
import com.example.tapp.models.Environment
import com.example.tapp.utils.TappConfiguration

val tappConfig = TappConfiguration(
    authToken = "your-auth-token",       // API authentication token from Tapp dashboard
    env = Environment.PRODUCTION,        // or Environment.SANDBOX for testing
    tappToken = "your-tapp-token",       // Your Tapp project token
    affiliate = Affiliate.ADJUST         // Must match the adapter module you installed
)
```

#### `TappConfiguration` parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `authToken` | `String` | Bearer token for authenticating API requests. Obtain from the Tapp dashboard. |
| `env` | `Environment` | `PRODUCTION` or `SANDBOX`. Sandbox enables SDK logging and uses the staging API. |
| `tappToken` | `String` | Your project identifier in the Tapp platform. |
| `affiliate` | `Affiliate` | The MMP provider: `ADJUST`, `TAPP_NATIVE`, or `APPSFLYER` (coming soon). |

### Step 2: Initialize the SDK

```kotlin
import com.example.tapp.Tapp

class MyApplication : Application() {

    companion object {
        lateinit var tapp: Tapp
    }

    override fun onCreate() {
        super.onCreate()

        tapp = Tapp(this)    // Pass the Application context
        tapp.start(tappConfig)
    }
}
```

The `start()` method:
1. Stores the configuration securely using AES/GCM encryption in the Android Keystore.
2. Fetches MMP secrets from the Tapp API.
3. Initializes the selected MMP adapter.

---

## 6. SDK Lifecycle

The SDK follows an asynchronous initialization flow. Understanding the lifecycle helps you know when SDK features become available.

```
Application.onCreate()
      │
      ▼
Tapp(context)                         ← Creates TappEngine and Dependencies
      │
      ▼
Tapp.start(configuration)
      │
      ├─▶ Configuration encrypted and stored in Android Keystore
      │
      ├─▶ SDK fetches secrets from Tapp API (async, background thread)
      │
      ├─▶ Selected MMP adapter initializes
      │     ├── Adjust: calls Adjust.initSdk()
      │     ├── AppsFlyer: initializes AppsFlyer SDK (coming soon)
      │     └── Native: collects device fingerprint and resolves deferred link
      │
      ▼
SDK ready for event tracking and deep link handling
```

**Key points:**

- Secret fetching and adapter initialization happen **asynchronously** on a background thread. `start()` returns immediately.
- The SDK queues deep link processing until secrets are available. You do not need to wait for initialization to complete before calling `appWillOpen()`.
- If the SDK has already fetched secrets from a previous session (persisted in encrypted storage), it skips the network call and initializes the adapter directly.
- The referral engine processes deep links **once per install**. After the first successful processing, subsequent calls to `appWillOpen()` return immediately without re-processing.

---

## 7. Threading

The SDK manages its own threading internally. Here is what you need to know:

| Context | Thread |
|---------|--------|
| All public API calls (`start()`, `handleTappEvent()`, `appWillOpen()`, etc.) | **Safe to call from the main thread** |
| Network requests (secret fetching, impression tracking, event sending) | Run on **background threads** via `Dispatchers.IO` |
| `DeferredLinkDelegate` callbacks (`didReceiveDeferredLink`, `didFailResolvingUrl`) | Delivered on the **main thread** |
| Suspend functions (`url()`, `fetchLinkData()`, `fetchOriginalLinkData()`) | Must be called from a coroutine; network I/O runs on `Dispatchers.IO` internally |
| Configuration storage (`KeystoreUtils`) | Synchronized and thread-safe |

**Practical guidance:**

- You can call `handleTappEvent()` from a button click handler, a background service, or a coroutine — the SDK dispatches the network call to a background thread automatically.
- Always call suspend functions from a `CoroutineScope` (e.g., `lifecycleScope.launch { ... }`).
- Deferred link delegate methods are guaranteed to run on the main thread, so you can safely update UI from within them.

---

## 8. Deep Link Handling

### Standard deep links

When your app receives a deep link, call `appWillOpen()` to let the SDK process it for attribution:

```kotlin
tapp.appWillOpen(url) { result ->
    result.fold(
        onSuccess = {
            // Deep link processed successfully
        },
        onFailure = { error ->
            // Handle error
        }
    )
}
```

### Deferred deep links

Deferred deep links are handled automatically by the SDK during initialization. To receive deferred link data, implement the `DeferredLinkDelegate` interface:

```kotlin
import com.example.tapp.services.affiliate.tapp.DeferredLinkDelegate
import com.example.tapp.services.network.RequestModels

class MainActivity : AppCompatActivity(), DeferredLinkDelegate {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the delegate to receive deferred link callbacks
        MyApplication.tapp.deferredLinkDelegate = this
    }

    override fun didReceiveDeferredLink(linkDataResponse: RequestModels.TappLinkDataResponse) {
        // Handle the deferred link data
        val tappUrl = linkDataResponse.tappUrl
        val influencer = linkDataResponse.influencer
        val data = linkDataResponse.data              // Map<String, String>
        val isFirstSession = linkDataResponse.isFirstSession
    }

    override fun didFailResolvingUrl(response: RequestModels.FailResolvingUrlResponse) {
        // Handle resolution failure
        val error = response.error
        val url = response.url
    }

    override fun testListener(test: String) {
        // Optional: used for SDK testing
    }
}
```

#### `TappLinkDataResponse` fields

| Field | Type | Description |
|-------|------|-------------|
| `error` | `Boolean` | Whether the response indicates an error. |
| `message` | `String?` | Human-readable message. |
| `tappUrl` | `String?` | The Tapp tracking URL. |
| `attrTappUrl` | `String?` | The attributed Tapp URL with query parameters. |
| `influencer` | `String?` | The influencer identifier associated with the link. |
| `data` | `Map<String, String>?` | Custom data attached to the link. |
| `isFirstSession` | `Boolean?` | `true` if this is the user's first session from this link. |
| `deepLink` | `String?` | The original deep link URL. |

### Fetch link data manually

You can also fetch link data programmatically:

```kotlin
// By URL
lifecycleScope.launch {
    val linkData = tapp.fetchLinkData("https://your-tapp-link.com/abc?t=token123")
}

// Or fetch the original link data from the initial deep link
lifecycleScope.launch {
    val linkData = tapp.fetchOriginalLinkData()
}
```

### Check if a URL should be processed

```kotlin
val shouldProcess = tapp.shouldProcess("https://your-tapp-link.com/abc?t=token123")
```

---

## 9. Event Tracking

### Event API model

The SDK provides two ways to track events depending on the adapter you use:

- `handleTappEvent(...)` is the **recommended unified event API**. It works with **all adapters**.
- When using **Tapp-Adjust**, you may also send Adjust-native event tokens using `handleEvent(token)`.
- When using **Tapp-Native**, you must use `handleTappEvent(...)` only.

### Tapp events (recommended)

Track events using the Tapp event system with predefined event actions:

```kotlin
import com.example.tapp.services.network.RequestModels
import com.example.tapp.services.network.RequestModels.EventAction

val event = RequestModels.TappEvent(
    eventName = EventAction.tapp_purchase,
    metadata = mapOf(
        "value" to 9.99,
        "currency" to "EUR",
        "item_id" to "SKU_123"
    )
)

tapp.handleTappEvent(event)
```

#### Predefined event actions

| Event | Description |
|-------|-------------|
| `tapp_add_payment_info` | User added payment information |
| `tapp_add_to_cart` | Item added to cart |
| `tapp_add_to_wishlist` | Item added to wishlist |
| `tapp_complete_registration` | User completed registration |
| `tapp_contact` | User initiated contact |
| `tapp_customize_product` | User customized a product |
| `tapp_donate` | User made a donation |
| `tapp_find_location` | User searched for a location |
| `tapp_initiate_checkout` | User initiated checkout |
| `tapp_generate_lead` | Lead generated |
| `tapp_purchase` | Purchase completed |
| `tapp_schedule` | User scheduled an appointment |
| `tapp_search` | User performed a search |
| `tapp_start_trial` | User started a trial |
| `tapp_submit_application` | Application submitted |
| `tapp_subscribe` | User subscribed |
| `tapp_view_content` | User viewed content |
| `tapp_click_button` | Button click tracked |
| `tapp_download_file` | File downloaded |
| `tapp_join_group` | User joined a group |
| `tapp_achieve_level` | User achieved a level |
| `tapp_create_group` | User created a group |
| `tapp_create_role` | User created a role |
| `tapp_link_click` | Link click tracked |
| `tapp_link_impression` | Link impression tracked |
| `tapp_apply_for_loan` | Loan application submitted |
| `tapp_loan_approval` | Loan approved |
| `tapp_loan_disbursal` | Loan disbursed |
| `tapp_login` | User logged in |
| `tapp_rate` | User submitted a rating |
| `tapp_spend_credits` | Credits spent |
| `tapp_unlock_achievement` | Achievement unlocked |
| `tapp_add_shipping_info` | Shipping information added |
| `tapp_earn_virtual_currency` | Virtual currency earned |
| `tapp_start_level` | Level started |
| `tapp_complete_level` | Level completed |
| `tapp_post_score` | Score posted |
| `tapp_select_content` | Content selected |
| `tapp_begin_tutorial` | Tutorial started |
| `tapp_complete_tutorial` | Tutorial completed |

#### Custom events

```kotlin
val customEvent = RequestModels.TappEvent(
    eventName = EventAction.custom("my_custom_event"),
    metadata = mapOf("key" to "value")
)

tapp.handleTappEvent(customEvent)
```

#### Metadata guidelines

Metadata values must be one of: `String`, `Boolean`, or `Number`. Other types (including lists and nested maps) will be silently dropped. `NaN` and `Infinity` numeric values are also rejected.

### MMP-native events (Adjust only)

If you use the **Adjust adapter**, you can also track events using Adjust event tokens.

> **Note:** This API exists **only for the Adjust adapter** and is **not available** when using Tapp-Native.

```kotlin
tapp.handleEvent("abc123")  // Adjust event token
```

---

## 10. Affiliate URL Generation

Generate tracking URLs for influencers programmatically:

```kotlin
lifecycleScope.launch {
    val response = tapp.url(
        influencer = "influencer-id",
        adGroup = "campaign-group",    // optional
        creative = "creative-name",    // optional
        data = mapOf("promo" to "summer2025")  // optional custom data
    )

    if (!response.error) {
        val trackingUrl = response.influencer_url
    }
}
```

---

## 11. MMP Adapters

### The `AffiliateService` interface

All adapters implement the `AffiliateService` interface defined in Tapp-Core:

```kotlin
interface AffiliateService {
    fun initialize(): Boolean
    fun handleCallback(deepLink: Uri)
    fun handleEvent(eventId: String)
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}
```

Each adapter module also defines an MMP-specific interface (e.g., `AdjustService`, `AppsflyerService`, `NativeService`) with provider-specific methods.

### Adapter registration

Adapters are registered automatically when you create the `Tapp` instance. The adapter's `init` block calls:

```kotlin
AffiliateServiceFactory.register(Affiliate.ADJUST) { deps ->
    AdjustAffiliateService(deps)
}
```

### Adjust adapter (`Tapp-Adjust`)

The Adjust adapter wraps the Adjust SDK v5.0.0 and provides additional methods:

| Method | Description |
|--------|-------------|
| `adjustTrackAdRevenue(source, revenue, currency)` | Track ad revenue |
| `adjustVerifyAppStorePurchase(...)` | Verify a Play Store purchase |
| `adjustTrackPlayStoreSubscription(...)` | Track a Play Store subscription |
| `adjustSetPushToken(token)` | Set the push notification token |
| `adjustGdprForgetMe()` | Send a GDPR forget-me request |
| `adjustTrackThirdPartySharing(isEnabled)` | Control third-party sharing |
| `getAdjustAttribution(completion)` | Retrieve attribution data |
| `adjustGetAdid(completion)` | Get the Adjust device ID |
| `adjustGetIdfa(completion)` | Get the advertising ID |
| `adjustOnResume()` / `adjustOnPause()` | Lifecycle callbacks |
| `adjustEnable()` / `adjustDisable()` | Enable or disable the Adjust SDK |
| `adjustSwitchToOfflineMode()` / `adjustSwitchBackToOnlineMode()` | Toggle offline mode |
| `adjustAddGlobalCallbackParameter(key, value)` | Add a global callback parameter |
| `adjustAddGlobalPartnerParameter(key, value)` | Add a global partner parameter |
| `adjustTrackMeasurementConsent(consent)` | Track measurement consent |
| `adjustGetSdkVersion(listener)` | Get the Adjust SDK version |
| `adjustSetReferrer(referrer)` | Set a custom referrer |

### AppsFlyer adapter (`Tapp-Appsflyer`)

The AppsFlyer adapter wraps the AppsFlyer SDK v6.12.1. This adapter is currently in early development (coming soon).

### Tapp Native adapter (`Tapp-Native`)

The Native adapter uses Tapp's built-in fingerprint-based deferred deep linking without requiring a third-party MMP SDK. It:

- Collects device fingerprint data (advertising ID, screen resolution, locale, timezone, etc.)
- Reads the Play Store install referrer for `click_id` extraction
- Calls the Tapp fingerprint API to resolve deferred deep links
- Supports automatic retry on failed backend calls

---

## 12. SDK Configuration

### Retrieve current configuration

```kotlin
val configResponse = tapp.getConfig()

if (!configResponse.error) {
    val config = configResponse.config
    println("Environment: ${config?.env}")
    println("Affiliate: ${config?.affiliate}")
    println("Deep Link URL: ${config?.deepLinkUrl}")
}
```

### Log configuration (debug)

In Sandbox mode, call `logConfig()` to print the current configuration to the console:

```kotlin
tapp.logConfig()
```

> **Note:** Logging is automatically enabled only in `SANDBOX` mode and disabled in `PRODUCTION`.

---

## 13. Example Integration

A complete minimal integration using the Tapp Native adapter:

```kotlin
// 1. Application class — initialize the SDK
class MyApplication : Application() {

    companion object {
        lateinit var tapp: Tapp
    }

    override fun onCreate() {
        super.onCreate()

        val config = TappConfiguration(
            authToken = "your-auth-token",
            env = Environment.SANDBOX,
            tappToken = "your-tapp-token",
            affiliate = Affiliate.TAPP_NATIVE
        )

        tapp = Tapp(this)
        tapp.start(config)
    }
}
```

```kotlin
// 2. Activity — set up delegate and track events
class MainActivity : AppCompatActivity(), DeferredLinkDelegate {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Listen for deferred deep links
        MyApplication.tapp.deferredLinkDelegate = this

        // Track an event
        val event = RequestModels.TappEvent(
            eventName = EventAction.tapp_click_button,
            metadata = mapOf("screen" to "home")
        )
        MyApplication.tapp.handleTappEvent(event)

        // Generate an influencer URL
        lifecycleScope.launch {
            val response = MyApplication.tapp.url("influencer-id", null, null)
            println("Tracking URL: ${response.influencer_url}")
        }
    }

    override fun didReceiveDeferredLink(linkDataResponse: RequestModels.TappLinkDataResponse) {
        // Route the user based on link data
        val influencer = linkDataResponse.influencer
        val customData = linkDataResponse.data
    }

    override fun didFailResolvingUrl(response: RequestModels.FailResolvingUrlResponse) {
        Log.w("Tapp", "Failed to resolve: ${response.error}")
    }

    override fun testListener(test: String) {
        // Optional test callback
    }
}
```

---

## 14. Best Practices

- **Initialize in `Application.onCreate()`** — The SDK should be started as early as possible in the app lifecycle to ensure deferred deep links and attribution are captured.
- **Call `start()` only once** — The SDK persists its configuration. Calling `start()` multiple times will overwrite the stored config.
- **Set `deferredLinkDelegate` before the first Activity renders** — Deferred link resolution happens asynchronously after `start()`. Set the delegate early to avoid missing callbacks.
- **Use `SANDBOX` during development** — Sandbox mode enables console logging and routes API calls to the staging server.
- **Track meaningful lifecycle events** — Use predefined `EventAction` values for common events (purchase, registration, login) to maintain consistency.
- **Keep metadata simple** — Only `String`, `Boolean`, and `Number` values are supported in event metadata. Complex types will be silently dropped.
- **Forward lifecycle events (Adjust only)** — If using the Adjust adapter, call `adjustOnResume()` and `adjustOnPause()` in your Activity lifecycle.

---

## 15. Troubleshooting

### SDK not initialized

**Symptom:** Methods throw `TappError.MissingConfiguration`.

**Cause:** `tapp.start(config)` was not called, or was called after attempting to use SDK methods.

**Fix:** Ensure `start()` is called in `Application.onCreate()` before any other SDK calls.

---

### Missing adapter dependency

**Symptom:** The SDK initializes but the MMP adapter fails with `TappError.MissingAffiliateService`.

**Cause:** The `affiliate` value in `TappConfiguration` does not match the installed adapter module.

**Fix:** Ensure consistency:

| `affiliate` value | Required module |
|--------------------|-----------------|
| `Affiliate.ADJUST` | `Tapp-Adjust` |
| `Affiliate.TAPP_NATIVE` | `Tapp-Native` |
| `Affiliate.APPSFLYER` | `Tapp-Appsflyer` (coming soon) |

---

### Incorrect Gradle configuration

**Symptom:** Build errors or `ClassNotFoundException` at runtime.

**Cause:** Missing JitPack repository or incorrect artifact coordinates.

**Fix:** Verify that `maven { url = uri("https://jitpack.io") }` is in your `settings.gradle.kts` and that the dependency uses the correct format:

```kotlin
implementation("com.github.tapp-so.Tapp-Android:Tapp-Adjust:<version>")
```

---

### No deferred deep link callback

**Symptom:** `didReceiveDeferredLink` is never called.

**Cause:** The `deferredLinkDelegate` was set after the deferred link was already resolved, or the referral engine has already processed a link (it processes only once per install).

**Fix:** Set the delegate as early as possible, ideally in `onCreate()` of your launcher Activity. The SDK tracks whether the referral engine has already processed a link via the `hasProcessedReferralEngine` flag — this is by design to prevent duplicate attribution.

---

### Logging not visible

**Symptom:** No `Tapp-Info` / `Tapp-Error` messages in Logcat.

**Cause:** The SDK disables logging in `PRODUCTION` mode.

**Fix:** Set `env = Environment.SANDBOX` in your `TappConfiguration` during development.

---

### Encrypted config errors

**Symptom:** `Decryption failed` errors in logs after app update or data restore.

**Cause:** The Android Keystore key was invalidated. This can happen after a factory reset, backup restore, or certain OS updates.

**Fix:** The SDK will recreate the key automatically, but the previous configuration will be lost. The SDK will re-fetch secrets on the next `start()` call.

---

## 16. ProGuard / R8

If you enable code shrinking or obfuscation, add the following rules to your `proguard-rules.pro`:

```proguard
# Tapp SDK — keep all public API classes
-keep class com.example.tapp.** { *; }

# Keep serialization metadata (used for encrypted config storage)
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
```

**Adapter-specific rules:**

If you use an adapter that depends on a third-party SDK, you must also include their ProGuard rules:

| Adapter | Additional rules |
|---------|------------------|
| Tapp-Adjust | Include [Adjust ProGuard rules](https://github.com/adjust/android_sdk#proguard-settings) |
| Tapp-Appsflyer | Include [AppsFlyer ProGuard rules](https://dev.appsflyer.com/hc/docs/install-android-sdk#proguard-rules) |
| Tapp-Native | No additional rules required |

> **Note:** Each adapter module includes a `consumer-rules.pro` file that is automatically applied to your app build. The rules above are only needed if the consumer rules are insufficient for your obfuscation setup.

---

## 17. Privacy and Data Collection

The Tapp SDK may collect device and usage data for attribution and analytics purposes. The exact data collected depends on the adapter module used.

### Data collected by Tapp-Core (all adapters)

| Data | Purpose |
|------|---------|
| Device identifiers | Used for attribution and fraud prevention |
| App bundle ID | Identifies the integrating application |
| Event metadata | Custom key-value pairs provided by the developer via `handleTappEvent()` |
| Deep link URLs | Processed for referral attribution |

### Additional data collected by Tapp-Native

| Data | Purpose |
|------|---------|
| Google Advertising ID (GAID) | Probabilistic attribution and fingerprinting |
| Play Store install referrer | Deterministic attribution via `click_id` |
| Screen resolution and density | Device fingerprinting |
| Locale and timezone | Device fingerprinting |
| OS version, device model, manufacturer | Device fingerprinting |
| Battery level and charging status | Device fingerprinting |
| RAM, storage, and device uptime | Device fingerprinting |

### Additional data collected by Tapp-Adjust

The Adjust SDK collects its own data as documented in the [Adjust privacy policy](https://www.adjust.com/terms/privacy-policy/). Refer to Adjust's documentation for full details.

### Additional data collected by Tapp-Appsflyer

The AppsFlyer SDK collects its own data as documented in the [AppsFlyer privacy policy](https://www.appsflyer.com/privacy-policy/). Refer to AppsFlyer's documentation for full details.

### Developer responsibilities

- **Disclose data collection** in your app's privacy policy.
- **Respect user consent** — if the user has opted out of ad tracking (Limited Ad Tracking), the Tapp Native adapter will skip the Advertising ID.
- **GDPR compliance** — the Adjust adapter provides `adjustGdprForgetMe()` and `adjustTrackThirdPartySharing()` methods for handling data subject requests.
