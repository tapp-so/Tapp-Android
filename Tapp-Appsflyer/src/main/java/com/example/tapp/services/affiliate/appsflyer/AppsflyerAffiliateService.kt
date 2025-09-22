package com.example.tapp.services.affiliate.appsflyer

import android.net.Uri
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.services.affiliate.AffiliateService
import com.example.tapp.utils.Logger

internal class AppsflyerAffiliateService(private val dependencies: Dependencies) : AffiliateService, AppsflyerService {
    override fun initialize(): Boolean {
        Logger.logInfo("AppsFlyer Affiliate Service Initialized")
        return true
    }

    override fun handleCallback(deepLink: Uri) {
        // Handle AppsFlyer callback
    }

    override fun handleEvent(eventId: String) {
        // Handle AppsFlyer event
    }

    override fun setEnabled(enabled: Boolean) {
        // Set AppsFlyer enabled state
    }

    override fun isEnabled(): Boolean {
        return true
    }

    override fun dummyAppsflyerMethod() {
        Logger.logInfo("Dummy AppsFlyer method called")
    }
}
