# Tapp Android SDK

![Android](https://img.shields.io/badge/Android-API%2024+-green)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-purple)
![License](https://img.shields.io/badge/license-MIT-blue)

The Tapp Android SDK provides **mobile attribution, influencer tracking, deep linking, and event analytics** for Android applications.

The SDK supports modular integrations with **Adjust**, the built-in **Tapp Native** attribution engine, or **AppsFlyer** (coming soon).

---

## Features

- **Mobile attribution** — attribute installs and opens to campaigns and influencers
- **Influencer tracking** — generate and manage affiliate tracking URLs
- **Deferred deep linking** — resolve deep links even on first install
- **Event tracking** — 40+ predefined events and custom event support
- **Modular MMP adapters** — Adjust and Tapp Native adapters available (AppsFlyer coming soon)

---

## Architecture

The SDK exposes a single public entry point (`Tapp`) while attribution providers are implemented through modular adapter modules.

```
Your App
   │
   ▼
Tapp (public SDK API)
   │
   ▼
Tapp Core
   │
   ├── Tapp-Adjust
   ├── Tapp-Native
   └── Tapp-Appsflyer (coming soon)
```

---

## Installation

Add the JitPack repository to `settings.gradle.kts`:

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
    // Choose one:
    implementation("com.github.tapp-so.Tapp-Android:Tapp-Native:<version>")     // Tapp Native
    implementation("com.github.tapp-so.Tapp-Android:Tapp-Adjust:<version>")     // Adjust
    // implementation("com.github.tapp-so.Tapp-Android:Tapp-Appsflyer:<version>")  // AppsFlyer (coming soon)
}
```

Each adapter includes the core SDK automatically.

---

## Quick Start

Initialize the SDK in your `Application` class:

```kotlin
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

> Match the `affiliate` value to the adapter module you installed.

---

## Track Events

`handleTappEvent(...)` is the common event API used by all adapters. When using the Adjust adapter, you may also send Adjust-native event tokens with `handleEvent(token)`.

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

Use `EventAction.custom("my_event")` for custom event names.

---

## Handle Deep Links

Pass incoming deep links to the SDK for attribution:

```kotlin
tapp.appWillOpen(url) { result ->
    result.fold(
        onSuccess = { /* link processed */ },
        onFailure = { error -> /* handle error */ }
    )
}
```

Receive deferred deep link data by implementing `DeferredLinkDelegate`:

```kotlin
class MainActivity : AppCompatActivity(), DeferredLinkDelegate {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MyApplication.tapp.deferredLinkDelegate = this
    }

    override fun didReceiveDeferredLink(linkDataResponse: RequestModels.TappLinkDataResponse) {
        val influencer = linkDataResponse.influencer
        val data = linkDataResponse.data
    }

    override fun didFailResolvingUrl(response: RequestModels.FailResolvingUrlResponse) { }
    override fun testListener(test: String) { }
}
```

---

## Generate Affiliate Links

```kotlin
lifecycleScope.launch {
    val response = MyApplication.tapp.url(
        influencer = "username",
        adGroup = null,
        creative = null
    )

    if (!response.error) {
        val trackingUrl = response.influencer_url
    }
}
```

---

## Example App

A working integration example is available in:

`example-app/`

The example demonstrates:

* SDK initialization
* event tracking
* deep link handling
* affiliate link generation

---

## Documentation

For complete SDK documentation, see:

- [Quickstart Guide](docs/android-quickstart.md) — step-by-step integration guide
- [Full SDK Reference](docs/android-sdk.md) — architecture, all APIs, adapters, threading, privacy, and troubleshooting

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Android | 7.0+ (API 24) |
| Kotlin | 1.9+ |
| Gradle Plugin | 8.7+ |
| Java | 11 |

---

## License

MIT License
