package com.example.tapp.services.affiliate.native

import android.annotation.SuppressLint
import android.content.res.Resources
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.services.network.NativeApiService
import com.example.tapp.services.network.RequestModels
import com.example.tapp.utils.Logger
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object TappNative {
    @Volatile private var initialized = false

    @SuppressLint("HardwareIds")
    fun init(dependencies: Dependencies, config: NativeConfig) {
        if (initialized) {
            Logger.logInfo("TappNative: already initialized; skipping.")
            return
        }
        initialized = true
        Logger.logInfo("TappNative: Initializing and checking for a deferred link...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ---- A) Try to read Play Install Referrer
                Logger.logInfo("Attempting to read Install Referrer…")
                val (installReferrerRaw, clickIdFromReferrer) = withTimeoutOrNull(2000L) {
                    getInstallReferrerSafe(config)
                } ?: Pair(null, null)
                if (installReferrerRaw != null)
                    Logger.logInfo("Install Referrer fetched: $installReferrerRaw (click_id=$clickIdFromReferrer)")
                else
                    Logger.logInfo("No Install Referrer available or timed out.")

                // ---- B) Try to get GAID
                Logger.logInfo("Fetching GAID (Advertising ID)…")
                val adInfo = withTimeoutOrNull(2500L) {
                    runCatching { AdvertisingIdClient.getAdvertisingIdInfo(config.context) }.getOrNull()
                }
                val isLatEnabled = adInfo?.isLimitAdTrackingEnabled == true
                val advertisingId = if (!isLatEnabled) (adInfo?.id ?: "") else ""
                when {
                    isLatEnabled -> Logger.logInfo("GAID retrieved but LAT enabled; skipping Advertising ID.")
                    advertisingId.isEmpty() -> Logger.logWarning("GAID not available (timeout or empty). Proceeding without it.")
                    else -> Logger.logInfo("GAID retrieved successfully: $advertisingId")
                }

                val androidId = try {
                    android.provider.Settings.Secure.getString(
                        config.context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    )
                } catch (e: Exception) {
                    Logger.logError("Failed to read ANDROID_ID: ${e.message}")
                    ""
                }

                val batteryLevel = getBatteryLevelPercent(config.context)
                val isCharging = getBatteryStatus(config.context)

                val totalRamBytes = getTotalRamBytes(config.context)
                val (totalStorageBytes, availStorageBytes) = getInternalStorageBytes();
                // milliseconds since boot including deep sleep
                val deviceUptimeMs: Long = android.os.SystemClock.elapsedRealtime()

                // human-friendly seconds (for logs / backend)
                val deviceUptimeSeconds: Long = deviceUptimeMs / 1000L
                // ---- C) Build the DeferredLinkRequest
                val dm = Resources.getSystem().displayMetrics
                val resolution = "${dm.widthPixels}x${dm.heightPixels}"
                val density = dm.density.toString()
                val locale = java.util.Locale.getDefault().toString()
                val timezone = java.util.TimeZone.getDefault().id

                // NEW: placeholders to force fields to appear in payload
                val installReferrerForPayload = installReferrerRaw ?: "null"
                val clickIdForPayload = clickIdFromReferrer ?: "null"

                Logger.logInfo("Building DeferredLinkRequest with fp=true, resolution=$resolution, density=$density")
                Logger.logInfo("DEBUG placeholders -> installReferrer='$installReferrerForPayload', clickId='$clickIdForPayload'")

                val request = RequestModels.DeferredLinkRequest(
                    fp = true,
                    advertisingId = advertisingId,
                    platform = "Android",
                    osVersion = android.os.Build.VERSION.RELEASE,
                    deviceModel = android.os.Build.MODEL,
                    deviceManufacturer = android.os.Build.MANUFACTURER,
                    screenResolution = resolution,
                    screenDensity = density,
                    locale = locale,
                    timezone = timezone,
                    installReferrer = installReferrerForPayload,
                    clickId = clickIdForPayload,
                    androidId = androidId,
                    batteryLevel = batteryLevel,
                    isCharging = isCharging ,
                    totalRamBytes = totalRamBytes,
                    totalStorageBytes = totalStorageBytes,
                    availStorageBytes = availStorageBytes,
                    deviceUptimeMs = deviceUptimeMs
                )

                // Log a concise summary of what will be sent
                Logger.logInfo(
                    "DeferredLinkRequest summary: " +
                            "advertisingId=${advertisingId.ifEmpty { "none" }}, " +
                            "clickId=$clickIdForPayload, " +
                            "installReferrerLen=${installReferrerForPayload.length}"
                )

                // ---- D) Call your backend
                suspend fun callOnce(): RequestModels.DeferredLinkResponse? = withTimeoutOrNull(4000L) {
                    Logger.logInfo("Calling backend for deferred link resolution…")
                    val result = NativeApiService.fetchDeferredLink(dependencies, request)
                    val resp = result.getOrNull()
                    if (resp != null) {
                        val chosenUrl = resp.tappUrl ?: resp.deeplink
                        Logger.logInfo("Backend returned: tappUrl=${resp.tappUrl}, deeplink=${resp.deeplink}, chosen=${chosenUrl}")
                    } else {
                        Logger.logWarning("Backend returned null (Result.getOrNull() == null)")
                    }
                    resp
                }

                val response = callOnce() ?: run {
                    Logger.logWarning("First backend call returned null, retrying once after 200ms...")
                    delay(200L)
                    callOnce()
                }

                // ---- E) Notify listener with the FULL response object
                if (response != null) {
                    Logger.logInfo("✅ Deferred link resolved (object): $response")
                    dispatchToMain {
                        // Listener now expects DeferredLinkResponse?
                        config.onDeferredDeeplinkResponseListener?.onDeferredDeeplinkResponse(response)
                    }
                } else {
                    Logger.logInfo("No deferred link found for this user (response was null).")
                }
            } catch (e: Exception) {
                Logger.logError("❌ Exception while fetching deferred link: ${e.message}")
            }
        }
    }

    private fun dispatchToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    private fun getBatteryLevelPercent(context: android.content.Context): Int? {
        return try {
            // Prefer BatteryManager on L+
            val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct != null && pct in 0..100) pct
            else {
                // Fallback to sticky broadcast (works without permissions)
                val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                val status = context.registerReceiver(null, ifilter)
                val level = status?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = status?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else null
            }
        } catch (_: Exception) { null }
    }

    private fun getBatteryStatus(context: android.content.Context): Boolean? {
        return try {
            // Prefer BatteryManager (no permission needed)
            val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val status = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)

            // Some vendors don't fill that property, so fallback to broadcast
            val charging = when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING,
                android.os.BatteryManager.BATTERY_STATUS_FULL -> true
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING,
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> {
                    val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                    val intent = context.registerReceiver(null, ifilter)
                    val state = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                    state == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                            state == android.os.BatteryManager.BATTERY_STATUS_FULL
                }
            }
            charging
        } catch (_: Exception) {
            null
        }
    }


    private fun getTotalRamBytes(context: android.content.Context): Long? {
        return try {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            mi.totalMem // bytes
        } catch (_: Exception) { null }
    }

    private fun getInternalStorageBytes(): Pair<Long?, Long?> {
        return try {
            val path = android.os.Environment.getDataDirectory()
            val stat = android.os.StatFs(path.path)
            val total = stat.totalBytes   // API 18+
            val avail = stat.availableBytes
            total to avail
        } catch (_: Exception) { null to null }
    }

    /**
     * Safely reads the Play Install Referrer once.
     * Returns Pair(rawReferrerString, clickId) or (null, null).
     */
    private suspend fun getInstallReferrerSafe(
        config: NativeConfig,
        timeoutMs: Long = 2_500L
    ): Pair<String?, String?> {
        // Timeout is important because on some devices/Play Services states,
        // onInstallReferrerSetupFinished() may never be called and the coroutine would hang forever.
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Pair<String?, String?>> { cont ->
                try {
                    val client = InstallReferrerClient.newBuilder(config.context).build()
                    Logger.logInfo("Connecting to Install Referrer service…")

                    cont.invokeOnCancellation { runCatching { client.endConnection() } }

                    client.startConnection(object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(code: Int) {
                            try {
                                when (code) {
                                    InstallReferrerClient.InstallReferrerResponse.OK -> {
                                        val resp = client.installReferrer

                                        // ✅ PRODUCTION: use Play Store value
                                        val raw = resp.installReferrer

                                        // 🧪 TESTING (local override):
                                        // Use this when you want to simulate a known referrer payload without reinstalling:
                                        // val raw = "utm_source=tapp&utm_medium=app&click_id=UJQd1qQL&fpid=9544b14f-a1eb-4d02-a0cd-50565c91a83d"
                                        //
                                        // Example Play redirect that produces the above raw (after Play decoding):
                                        // https://play.google.com/store/apps/details?id=com.example.app&referrer=utm_source%3Dtapp%26utm_medium%3Dapp%26click_id%3DUJQd1qQL%26fpid%3D9544b14f-a1eb-4d02-a0cd-50565c91a83d

                                        Logger.logInfo("Install Referrer response received: ${raw ?: "<null>"}")

                                        val clickId = raw
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let {
                                                val parsed = Uri.parse("app://dummy?$it")
                                                parsed.getQueryParameter("click_id")
                                            }

                                        Logger.logInfo("Parsed click_id from referrer: ${clickId ?: "none"}")
                                        cont.resume(Pair(raw, clickId))
                                    }

                                    InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                                        Logger.logWarning("Install Referrer not supported on this device.")
                                        cont.resume(Pair(null, null))
                                    }

                                    InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                                        Logger.logWarning("Install Referrer service unavailable.")
                                        cont.resume(Pair(null, null))
                                    }

                                    else -> {
                                        Logger.logWarning("Install Referrer returned unknown code: $code")
                                        cont.resume(Pair(null, null))
                                    }
                                }
                            } catch (e: Exception) {
                                Logger.logError("Error parsing Install Referrer: ${e.message}")
                                cont.resume(Pair(null, null))
                            } finally {
                                runCatching { client.endConnection() }
                            }
                        }

                        override fun onInstallReferrerServiceDisconnected() {
                            Logger.logWarning("Install Referrer service disconnected.")
                            // Don't resume here; onInstallReferrerSetupFinished usually handles resume.
                        }
                    })
                } catch (e: Exception) {
                    Logger.logError("Failed to initialize Install Referrer client: ${e.message}")
                    cont.resume(Pair(null, null))
                }
            }
        }

        if (result == null) {
            Logger.logWarning("Install Referrer timed out after ${timeoutMs}ms")
            return Pair(null, null)
        }

        return result
    }
}
