
package com.example.tapp

import android.content.Context
import android.net.Uri
import com.adjust.sdk.AdjustAttribution
import com.adjust.sdk.AdjustEvent
import com.adjust.sdk.AdjustPlayStorePurchase
import com.adjust.sdk.AdjustPurchaseVerificationResult
import com.adjust.sdk.OnAmazonAdIdReadListener
import com.adjust.sdk.OnGoogleAdIdReadListener
import com.adjust.sdk.OnGooglePlayInstallReferrerReadListener
import com.adjust.sdk.OnIsEnabledListener
import com.adjust.sdk.OnPurchaseVerificationFinishedListener
import com.adjust.sdk.OnSdkVersionReadListener
import com.example.tapp.services.affiliate.adjust.AdjustAffiliateService
import com.example.tapp.services.affiliate.tapp.DeferredLinkDelegate
import com.example.tapp.services.network.RequestModels
import com.example.tapp.utils.Logger
import com.example.tapp.utils.TappConfiguration
import com.example.tapp.utils.VoidCompletion

/**
 * The main entry point for the Tapp SDK (Adjust version).
 * This class provides all core SDK functionality and Adjust-specific features.
 */
import com.example.tapp.dependencies.Dependencies

import com.example.tapp.services.network.NetworkManager
import com.example.tapp.utils.KeystoreUtils

import com.example.tapp.services.affiliate.AffiliateServiceFactory
import com.example.tapp.services.affiliate.adjust.AdjustService

class Tapp(context: Context) {

    // Internal engine from Tapp-Core that holds all the shared logic.
    // Note: The TappEngine class and its methods must have `internal` visibility.
    private val engine: TappEngine

    init {
        val dependencies = Dependencies(
            context = context,
            keystoreUtils = KeystoreUtils(context),
            networkManager = NetworkManager(),
            affiliateServiceFactory = AffiliateServiceFactory
        )
        engine = TappEngine(dependencies)
        dependencies.tappInstance = engine
    }

    // --- Delegated Properties & Methods from TappEngine ---

    var deferredLinkDelegate: DeferredLinkDelegate?
        get() = engine.deferredLinkDelegate
        set(value) {
            engine.deferredLinkDelegate = value
        }

    fun start(config: TappConfiguration) = engine.start(config)

    fun appWillOpen(url: String?, completion: VoidCompletion?) = engine.appWillOpen(url, completion)

    fun appWillOpenIntent(url: String?) = engine.appWillOpenIntent(url)

    fun appWillOpenInstallReferrerStateListener(url: String?) = engine.appWillOpenInstallReferrerStateListener(url)

    fun logConfig() = engine.logConfig()

    fun shouldProcess(url: String?): Boolean = engine.shouldProcess(url)

    suspend fun url(influencer: String, adGroup: String?, creative: String?, data: Map<String, String>? = null) =
        engine.url(influencer, adGroup, creative, data)

    fun handleEvent(eventToken: String) = engine.handleEvent(eventToken)

    fun handleTappEvent(tappEvent: RequestModels.TappEvent) = engine.handleTappEvent(tappEvent)

    suspend fun fetchLinkData(url: String) = engine.fetchLinkData(url)

    suspend fun fetchOriginalLinkData() = engine.fetchOriginalLinkData()

    fun getConfig() = engine.getConfig()

    fun handleDeferredDeepLink(response: RequestModels.TappLinkDataResponse) = engine.handleDeferredDeepLink(response)

    fun handleDidFailResolvingUrl(response: RequestModels.FailResolvingUrlResponse) = engine.handleDidFailResolvingUrl(response)

    fun handleTesListener(test: String) = engine.handleTesListener(test)

    fun simulateTestEvent() = engine.simulateTestEvent()


    // --- Adjust-specific Methods (previously in Tapp+Adjust.kt) ---

    private val tappContext: Context get() = engine.dependencies.context

    

    fun adjustTrackAdRevenue(source: String, revenue: Double, currency: String) {
        (engine.dependencies.keystoreUtils.getConfig()?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it.affiliate, engine.dependencies)
        } as? AdjustService)?.trackAdRevenue(source, revenue, currency)
    }

    fun adjustVerifyAppStorePurchase(
        transactionId: String,
        productId: String,
        completion: (AdjustPurchaseVerificationResult) -> Unit
    ) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.verifyAppStorePurchase(transactionId, productId, completion)
    }

    fun adjustSetPushToken(token: String) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.setPushToken(token)
    }

    fun adjustGdprForgetMe() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.gdprForgetMe(tappContext)
    }

    fun adjustTrackThirdPartySharing(isEnabled: Boolean) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.trackThirdPartySharing(isEnabled)
    }

    fun getAdjustAttribution(completion: (AdjustAttribution?) -> Unit) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.getAdjustAttribution(completion)
    }

    fun adjustGetAdid(completion: (String?) -> Unit) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.getAdid(completion)
    }

    fun adjustGetIdfa(completion: (String?) -> Unit) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.getAdvertisingId(completion)
    }

    fun adjustTrackPlayStoreSubscription(
        price: Long,
        currency: String,
        sku: String,
        orderId: String,
        signature: String,
        purchaseToken: String,
        purchaseTime: Long? = null
    ) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.trackPlayStoreSubscription(
            price,
            currency,
            sku,
            orderId,
            signature,
            purchaseToken,
            purchaseTime
        )
    }

    fun adjustVerifyAndTrackPlayStorePurchase(
        event: AdjustEvent,
        listener: OnPurchaseVerificationFinishedListener
    ) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.verifyAndTrackPlayStorePurchase(event, listener)
    }

    fun verifyPlayStorePurchase(
        purchase: AdjustPlayStorePurchase,
        listener: OnPurchaseVerificationFinishedListener
    ) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.verifyPlayStorePurchase(purchase, listener)
    }

    fun adjustGetGoogleAdId(listener: OnGoogleAdIdReadListener) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.getGoogleAdId(listener)
    }

    fun adjustGetAmazonAdId(listener: OnAmazonAdIdReadListener) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.getAmazonAdId(listener)
    }

    fun adjustGetGooglePlayInstallReferrer(listener: OnGooglePlayInstallReferrerReadListener) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.getGooglePlayInstallReferrer(listener)
    }

    fun adjustOnResume() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.onResume()
    }

    fun adjustOnPause() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.onPause()
    }

    fun adjustEnable() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.enable()
    }

    fun adjustDisable() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.disable()
    }

    fun adjustIsEnabled(context: Context, listener: OnIsEnabledListener) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.isEnabled(context, listener)
    }

    fun adjustSwitchToOfflineMode() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.switchToOfflineMode()
    }

    fun adjustSwitchBackToOnlineMode() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.switchBackToOnlineMode()
    }

    fun adjustAddGlobalCallbackParameter(key: String, value: String) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.addGlobalCallbackParameter(key, value)
    }

    fun adjustAddGlobalPartnerParameter(key: String, value: String) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.addGlobalPartnerParameter(key, value)
    }

    fun adjustRemoveGlobalCallbackParameter(key: String) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.removeGlobalCallbackParameter(key)
    }

    fun adjustRemoveGlobalPartnerParameter(key: String) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.removeGlobalPartnerParameter(key)
    }

    fun adjustRemoveGlobalCallbackParameters() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.removeGlobalCallbackParameters()
    }

    fun adjustRemoveGlobalPartnerParameters() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.removeGlobalPartnerParameters()
    }

    fun adjustTrackMeasurementConsent(consent: Boolean) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.trackMeasurementConsent(consent)
    }

    fun adjustGetSdkVersion(listener: OnSdkVersionReadListener) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.getSdkVersion(listener)
    }

    fun adjustSetReferrer(referrer: String) {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AdjustService)?.setReferrer(referrer)
    }
}
