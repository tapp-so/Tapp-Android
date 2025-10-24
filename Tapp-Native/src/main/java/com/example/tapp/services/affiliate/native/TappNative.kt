package com.example.tapp.services.affiliate.native

import android.annotation.SuppressLint
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
                val totalRamBytes = getTotalRamBytes(config.context)
                val (totalStorageBytes, availStorageBytes) = getInternalStorageBytes();
                // milliseconds since boot including deep sleep
                val deviceUptimeMs: Long = android.os.SystemClock.elapsedRealtime()

                // human-friendly seconds (for logs / backend)
                val deviceUptimeSeconds: Long = deviceUptimeMs / 1000L
                // ---- C) Build the DeferredLinkRequest
                val dm = config.context.resources.displayMetrics
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
                    osName = "Android",
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
                suspend fun callOnce(): Uri? = withTimeoutOrNull(4000L) {
                    Logger.logInfo("Calling backend for deferred link resolution…")
                    val result = NativeApiService.fetchDeferredLink(dependencies, request)
                    result.getOrNull()?.deeplink?.let {
                        Logger.logInfo("Backend returned deeplink: $it")
                        Uri.parse(it)
                    }
                }

                val deepLinkUri = callOnce() ?: run {
                    Logger.logWarning("First backend call returned null, retrying once after 200ms...")
                    delay(200L)
                    callOnce()
                }

                // ---- E) Notify listener
                if (deepLinkUri != null) {
                    Logger.logInfo("✅ Deferred link resolved: $deepLinkUri")
                    dispatchToMain {
                        config.onDeferredDeeplinkResponseListener?.onDeferredDeeplinkResponse(deepLinkUri)
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
    private suspend fun getInstallReferrerSafe(config: NativeConfig): Pair<String?, String?> =
        suspendCancellableCoroutine { cont ->
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
                                    val raw = resp.installReferrer
                                    Logger.logInfo("Install Referrer response received: $raw")
                                    val parsed = Uri.parse("app://dummy?$raw")
                                    val clickId = parsed.getQueryParameter("click_id")
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
                        // Don't resume here; we'll have already resumed above or hit timeout.
                    }
                })
            } catch (e: Exception) {
                Logger.logError("Failed to initialize Install Referrer client: ${e.message}")
                cont.resume(Pair(null, null))
            }
        }

}
