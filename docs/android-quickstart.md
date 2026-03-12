# Tapp Android SDK — Quickstart Guide

## 3-Minute Integration

You can integrate the Tapp Android SDK in just a few minutes:

1. Add the SDK dependency
2. Initialize the SDK
3. Start tracking events

### 1. Add dependency

```kotlin
dependencies {
    implementation("com.github.tapp-so.Tapp-Android:Tapp-Native:<version>")
}
```

### 2. Initialize the SDK

```kotlin
class MyApplication : Application() {

    companion object {
        lateinit var tapp: Tapp
    }

    override fun onCreate() {
        super.onCreate()

        val config = TappConfiguration(
            authToken = "your-auth-token",
            tappToken = "your-tapp-token",
            env = Environment.SANDBOX,
            affiliate = Affiliate.TAPP_NATIVE
        )

        tapp = Tapp(this)
        tapp.start(config)
    }
}
```

Once initialized, the SDK automatically handles attribution, deep links, and event tracking.

---

## 1. Overview

The Tapp Android SDK lets you add **attribution**, **influencer tracking**, **deep linking**, and **event tracking** to your Android app.

You install one adapter module for your preferred attribution provider (Adjust or Tapp Native; AppsFlyer coming soon) and access all features through a single `Tapp` class.

---

## 2. Installation

Add the JitPack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add **one** adapter dependency to your app-level `build.gradle.kts`:

```kotlin
dependencies {
    // Choose ONE adapter module
    implementation("com.github.tapp-so.Tapp-Android:Tapp-Adjust:<version>")     // Adjust
    // implementation("com.github.tapp-so.Tapp-Android:Tapp-Appsflyer:<version>")  // AppsFlyer (coming soon)
    implementation("com.github.tapp-so.Tapp-Android:Tapp-Native:<version>")     // Tapp Native (no third-party MMP)
}
```

> Each adapter includes the core SDK automatically — no extra dependency needed.

### Adapter ↔ Affiliate mapping

| Adapter module | `Affiliate` value | Status |
|----------------|-------------------|--------|
| `Tapp-Adjust` | `Affiliate.ADJUST` | Available |
| `Tapp-Native` | `Affiliate.TAPP_NATIVE` | Available |
| `Tapp-Appsflyer` | `Affiliate.APPSFLYER` | 🚧 Coming soon |

### Requirements

| Requirement | Version |
|-------------|----------|
| Android | 7.0+ (API 24) |
| Kotlin | 1.9+ |
| Gradle | 8+ |

---

## 3. Initialize the SDK

The `Tapp` instance should be created **once** and reused throughout your app. The recommended approach is to store it in your `Application` class using a `companion object` — this acts as the **global SDK instance** that all Activities and services access via `MyApplication.tapp`.

```kotlin
import android.app.Application
import com.example.tapp.Tapp
import com.example.tapp.models.Affiliate
import com.example.tapp.models.Environment
import com.example.tapp.utils.TappConfiguration

class MyApplication : Application() {

    companion object {
        lateinit var tapp: Tapp
    }

    override fun onCreate() {
        super.onCreate()

        val config = TappConfiguration(
            authToken = "your-auth-token",        // From Tapp dashboard
            env = Environment.SANDBOX,             // Use PRODUCTION for release builds
            tappToken = "your-tapp-token",         // Your project token
            affiliate = Affiliate.TAPP_NATIVE      // Must match the adapter you installed
        )

        tapp = Tapp(this)
        tapp.start(config)
    }
}
```

Register your `Application` class in `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApplication"
    ... >
```

**That's it.** The SDK fetches secrets and initializes the adapter automatically in the background.

---

## 4. Track Events

> **Note:** `handleTappEvent(...)` works with all adapters. If you use the Adjust adapter, you can also send Adjust-native event tokens using `handleEvent(token)`. Tapp-Native users should only use `handleTappEvent(...)`.

### Predefined events

```kotlin
import com.example.tapp.services.network.RequestModels
import com.example.tapp.services.network.RequestModels.EventAction

val event = RequestModels.TappEvent(
    eventName = EventAction.tapp_purchase,
    metadata = mapOf(
        "value" to 9.99,
        "currency" to "EUR"
    )
)

MyApplication.tapp.handleTappEvent(event)
```

### Custom events

```kotlin
val event = RequestModels.TappEvent(
    eventName = EventAction.custom("my_custom_event"),
    metadata = mapOf("key" to "value")
)

MyApplication.tapp.handleTappEvent(event)
```

### Common predefined events

| Event | When to use |
|-------|-------------|
| `tapp_purchase` | Purchase completed |
| `tapp_complete_registration` | User signed up |
| `tapp_login` | User logged in |
| `tapp_add_to_cart` | Item added to cart |
| `tapp_initiate_checkout` | Checkout started |
| `tapp_subscribe` | Subscription created |
| `tapp_start_trial` | Trial started |
| `tapp_view_content` | Content viewed |

> **Metadata** accepts `String`, `Boolean`, and `Number` values only.

---

## 5. Handle Deep Links

### Deferred deep links

Deferred deep links allow your app to receive attribution data even if the user installs the app **after** clicking a tracking link. The SDK resolves these automatically during initialization.

To receive the resolved link data, implement `DeferredLinkDelegate`:

```kotlin
import com.example.tapp.services.affiliate.tapp.DeferredLinkDelegate
import com.example.tapp.services.network.RequestModels

class MainActivity : AppCompatActivity(), DeferredLinkDelegate {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MyApplication.tapp.deferredLinkDelegate = this
    }

    override fun didReceiveDeferredLink(linkDataResponse: RequestModels.TappLinkDataResponse) {
        val influencer = linkDataResponse.influencer
        val customData = linkDataResponse.data         // Map<String, String>?
        val isFirstOpen = linkDataResponse.isFirstSession

        // Route the user based on link data
    }

    override fun didFailResolvingUrl(response: RequestModels.FailResolvingUrlResponse) {
        // Optional: log or handle the error
    }

    override fun testListener(test: String) {
        // Optional: used for SDK testing only
    }
}
```

### Process standard deep links

When your app opens via a deep link, retrieve the URL from the Activity's `Intent` and pass it to the SDK:

```kotlin
// In your Activity's onCreate or onNewIntent
val url = intent?.data?.toString()

MyApplication.tapp.appWillOpen(url) { result ->
    result.fold(
        onSuccess = { /* deep link processed for attribution */ },
        onFailure = { error -> /* handle error */ }
    )
}
```

> The `url` comes from `intent.data` when Android opens your Activity via a deep link (e.g., from an app link or a custom URI scheme).

### Adjust adapter lifecycle (Adjust only)

When using the **Adjust adapter**, you must forward Activity lifecycle events to the SDK. This is required by the Adjust SDK for accurate session tracking.

```kotlin
override fun onResume() {
    super.onResume()
    MyApplication.tapp.adjustOnResume()
}

override fun onPause() {
    MyApplication.tapp.adjustOnPause()
    super.onPause()
}
```

> This step is **only required** when using `Tapp-Adjust`. Skip it for Tapp Native or AppsFlyer.

---

## 6. Generate Affiliate Links

Create tracking URLs for influencers:

```kotlin
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

lifecycleScope.launch {
    val response = MyApplication.tapp.url(
        influencer = "influencer-username",
        adGroup = null,       // optional
        creative = null,      // optional
        data = null           // optional Map<String, String>
    )

    if (!response.error) {
        val trackingUrl = response.influencer_url
        // Share this URL with the influencer
    }
}
```

---

## 7. Troubleshooting

### `TappError.MissingConfiguration`

**Cause:** `tapp.start(config)` was not called before using SDK methods.

**Fix:** Call `start()` in `Application.onCreate()`.

---

### `TappError.MissingAffiliateService`

**Cause:** The `affiliate` value in your config does not match the installed adapter module.

**Fix:** Make sure they match:

| Config value | Gradle dependency | Status |
|--------------|-------------------|--------|
| `Affiliate.ADJUST` | `Tapp-Adjust` | Available |
| `Affiliate.TAPP_NATIVE` | `Tapp-Native` | Available |
| `Affiliate.APPSFLYER` | `Tapp-Appsflyer` | 🚧 Coming soon |

---

### No deferred deep link callback

**Cause:** The delegate was set after the link was already resolved, or the referral engine already processed a link for this install.

**Fix:** Set `deferredLinkDelegate` as early as possible in your launcher Activity's `onCreate()`.

---

### No log output

**Cause:** Logging is disabled in `PRODUCTION` mode.

**Fix:** Use `Environment.SANDBOX` during development.

---

## Next Steps

- See the full [SDK Documentation](android-sdk.md) for advanced features, Adjust-specific methods, architecture details, and the complete event list.
- Switch `env` to `Environment.PRODUCTION` before releasing your app.
