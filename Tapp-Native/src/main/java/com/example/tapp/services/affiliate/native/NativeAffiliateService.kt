package com.example.tapp.services.affiliate.native

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.models.Environment
import com.example.tapp.services.affiliate.AffiliateService
import com.example.tapp.services.network.RequestModels
import com.example.tapp.utils.Logger

internal class NativeAffiliateService(private val dependencies: Dependencies) : AffiliateService, NativeService {

    private var isTappNativeEnabled: Boolean = false

    override fun initialize(): Boolean {
        val context = dependencies.context
        val config = dependencies.keystoreUtils.getConfig()
        if (config == null) {
            Logger.logWarning("Error: Missing configuration")
            return false
        }

        if(config.hasProcessedReferralEngine){
            Logger.logInfo("Already initialized")
            return true
        }

        return try {
            Logger.logInfo("Handling initialize Native")

            val nativeConfig = NativeConfig(context)

            // Register deferred deeplink listener (unchanged behavior)
            nativeConfig.onDeferredDeeplinkResponseListener = OnDeferredDeeplinkResponseListener { deferredLinkResponse ->
                if(deferredLinkResponse?.error == false || deferredLinkResponse?.error == null ){
                    handleDeferredLinkResponse(deferredLinkResponse)
                }else{
                    Logger.logWarning("Fingerprint isn't matched")
                    handleFailedLinkProcessing()
                }
                true
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
        Logger.logInfo("Enable Native service$enabled")
        isTappNativeEnabled = enabled
    }

    override fun isEnabled(): Boolean {
        return isTappNativeEnabled
    }

    override fun dummyNativeMethod() {
        Logger.logInfo("Dummy native method called")
    }

    private fun handleDeferredLinkResponse(deferredLinkResponse: RequestModels.DeferredLinkResponse?) {
        if (deferredLinkResponse?.deeplink != null) {
            Logger.logInfo("Received native DeferredLinkResponse: ${deferredLinkResponse.deeplink}")
            // Ensure SDK callback runs on main thread (prevents UI threading issues downstream)
            val run = {
                dependencies.tappInstance?.appWillOpenIntNative(deferredLinkResponse, null) ?: run {
                    Logger.logError("Tapp instance is not available to handle DeferredLinkResponse.")
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                run()
            } else {
                Handler(Looper.getMainLooper()).post { run() }
            }
        } else {
            Logger.logWarning("Received null tappUrl DeferredLinkResponse from native MMP.")
        }
    }

    private fun handleFailedLinkProcessing(){
        Logger.logWarning("URL is not processable")
        dependencies.tappInstance?.appWillNotProcess() ?: run {
            Logger.logError("Tapp instance is not available to handle DeferredLinkResponse.")
        }
    }
}
