package com.tapp.tappnative.services.affiliate.native

import android.net.Uri
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.services.affiliate.AffiliateService
import com.example.tapp.utils.Logger

internal class NativeAffiliateService(private val dependencies: Dependencies) : AffiliateService, NativeService {
    override fun initialize(): Boolean {
        Logger.logInfo("Native Affiliate Service Initialized")
        return true
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
}
