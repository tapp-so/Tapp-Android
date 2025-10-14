package com.example.tapp.services.affiliate.native

import android.net.Uri
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.services.network.NativeApiService
import com.example.tapp.services.network.RequestModels
import com.example.tapp.utils.Logger
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object TappNative {
    fun init(dependencies: Dependencies, config: NativeConfig) {
        Logger.logInfo("TappNative: Initializing and checking for a deferred link...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Step 1: Get the Google Advertising ID (GAID).
                val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(config.context)
                val advertisingId = adInfo.id ?: ""

                if (advertisingId.isEmpty()) {
                    Logger.logWarning("Could not retrieve Advertising ID.")
                    return@launch
                }

                // Step 2: Create the request and call the service.
                // val displayMetrics = config.context.resources.displayMetrics
                // val resolution = "${displayMetrics.heightPixels}x${displayMetrics.widthPixels}"
                // val density = displayMetrics.density.toString()

                val request = RequestModels.DeferredLinkRequest(
                    advertisingId = advertisingId
                    // osName = "Android",
                    // osVersion = android.os.Build.VERSION.RELEASE,
                    // deviceModel = android.os.Build.MODEL,
                    // deviceManufacturer = android.os.Build.MANUFACTURER,
                    // screenResolution = resolution,
                    // screenDensity = density,
                    // locale = java.util.Locale.getDefault().toString(),
                    // timezone = java.util.TimeZone.getDefault().id,

                    // // More advanced and stable properties
                    // buildFingerprint = android.os.Build.FINGERPRINT,
                    // androidId = android.provider.Settings.Secure.getString(config.context.contentResolver, android.provider.Settings.Secure.ANDROID_ID),
                    // deviceUptime = android.os.SystemClock.elapsedRealtime(),
                    // totalRam = (config.context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).let { val memInfo = android.app.ActivityManager.MemoryInfo(); it.getMemoryInfo(memInfo); memInfo.totalMem },
                    // totalStorage = android.os.Environment.getDataDirectory().totalSpace,
                    // carrierName = (config.context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).networkOperatorName
                )
                val result = NativeApiService.fetchDeferredLink(dependencies, request)

                // Step 3: Handle the response.
                result.fold(
                    onSuccess = { response ->
                        if (response.url != null) {
                            // Step 4: If a link is found, parse it.
                            val deepLinkUri = Uri.parse(response.url)
                            Logger.logInfo("Found deferred link: $deepLinkUri")

                            // Step 5: Trigger the listener.
                            config.onDeferredDeeplinkResponseListener?.onDeferredDeeplinkResponse(deepLinkUri)
                        } else {
                            Logger.logInfo("No deferred link found for this user.")
                        }
                    },
                    onFailure = { error ->
                        Logger.logError("Error fetching deferred link: ${error.message}")
                    }
                )

            } catch (e: Exception) {
                Logger.logError("An exception occurred while fetching deferred link: ${e.message}")
            }
        }
    }
}