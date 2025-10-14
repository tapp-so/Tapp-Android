package com.example.tapp.services.affiliate.native

import android.net.Uri
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.models.Environment
import com.example.tapp.services.affiliate.AffiliateService
import com.example.tapp.utils.Logger

internal class NativeAffiliateService(private val dependencies: Dependencies) : AffiliateService, NativeService {
    override fun initialize(): Boolean {
        val context = dependencies.context
        val config = dependencies.keystoreUtils.getConfig()
        if (config == null) {
            Logger.logWarning("Error: Missing configuration")
            return false
        }

        return try {
            Logger.logInfo("Handling initialize Native")

            val nativeConfig = NativeConfig(context)

            // Register deferred deeplink listener.
            nativeConfig.onDeferredDeeplinkResponseListener = OnDeferredDeeplinkResponseListener { deeplink ->
                handleNativeDeeplink(deeplink)
                // Returning false means that the deeplink is not consumed here.
                //TODO:: maybe turn it to true
                false
            }

            TappNative.init(dependencies, nativeConfig)
            Logger.logInfo("Tapp native initialized and deeplink listener registered")
            true
        } catch (e: Exception) {
            Logger.logWarning("Error during native initialization: ${e.message}")
            false
        }
    }

    override fun handleCallback(deepLink: Uri) {
        // Handle native callback
    }

    override fun handleEvent(eventId: String) {
        // Handle native event
    }

    override fun setEnabled(enabled: Boolean) {
        // Set native enabled state
    }

    override fun isEnabled(): Boolean {
        return true
    }

    override fun dummyNativeMethod() {
        Logger.logInfo("Dummy native method called")
    }

    private fun handleNativeDeeplink(deepLink: Uri?) {
        if (deepLink != null) {
            Logger.logInfo("Received native deeplink: $deepLink")
            dependencies.tappInstance?.appWillOpenInt(deepLink.toString(), null) ?: run {
                Logger.logError("Tapp instance is not available to handle deeplink.")
            }
        } else {
            Logger.logWarning("Received null deeplink from native MMP.")
        }
    }
}
