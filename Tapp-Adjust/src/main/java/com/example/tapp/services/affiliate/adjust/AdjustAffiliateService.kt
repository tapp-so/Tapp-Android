package com.example.tapp.services.affiliate.adjust

import android.content.Context
import android.net.Uri
import com.adjust.sdk.*
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.models.Environment
import com.example.tapp.services.affiliate.AffiliateService
import com.example.tapp.utils.Logger
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal class AdjustAffiliateService(private val dependencies: Dependencies) : AffiliateService, AdjustService {
    private var isAdjustEnabled: Boolean = false

    override fun initialize(): Boolean {
        val context = dependencies.context
        val config = dependencies.keystoreUtils.getConfig()
        if (config == null) {
            Logger.logWarning("Error: Missing configuration")
            return false
        }

        val appToken = config.appToken
        if (appToken.isNullOrBlank()) {
            Logger.logWarning("Adjust initialization skipped: invalid app token configuration")
            return false
        }

        return try {
            val adjustEnvironment = when (config.env) {
                Environment.PRODUCTION -> AdjustConfig.ENVIRONMENT_PRODUCTION
                Environment.SANDBOX    -> AdjustConfig.ENVIRONMENT_SANDBOX
            }

            val adjustConfig = AdjustConfig(context, appToken, adjustEnvironment)
            adjustConfig.setLogLevel(LogLevel.VERBOSE)

            // Register deferred deeplink listener.
            adjustConfig.setOnDeferredDeeplinkResponseListener { deeplink ->
                handleAdjustDeeplink(deeplink)
                // Returning false means that the deeplink is not consumed here.
                false
            }

            adjustConfig.setOnEventTrackingSucceededListener { response ->
                if (response == null) {
                    Logger.logWarning("Adjust event confirmation callback returned no response")
                    return@setOnEventTrackingSucceededListener
                }
                Logger.logInfo(
                    "Adjust event confirmed: event_id=${response.eventToken}, " +
                            "callback_id=${response.callbackId}, message=${response.message}, " +
                            "timestamp=${response.timestamp}"
                )
            }

            adjustConfig.setOnEventTrackingFailedListener { response ->
                if (response == null) {
                    Logger.logWarning("Adjust event failure callback returned no response")
                    return@setOnEventTrackingFailedListener
                }
                val retryStatus = if (response.willRetry) "retryable" else "not_retryable"
                Logger.logError(
                    "Adjust event failed: event_id=${response.eventToken}, " +
                            "callback_id=${response.callbackId}, message=${response.message}, " +
                            "timestamp=${response.timestamp}, will_retry=${response.willRetry}, " +
                            "retry_status=$retryStatus"
                )
            }

            Adjust.initSdk(adjustConfig)
            Logger.logInfo("Adjust initialization submitted")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logLocalFailure("initialization", e)
            false
        }
    }

    override fun handleCallback(deepLink: Uri) {
        if (deepLink == Uri.EMPTY || deepLink.toString().isBlank()) {
            Logger.logWarning("Adjust processDeeplink skipped: deeplink is empty")
            return
        }

        if (runAdjustCall("processDeeplink") {
                Adjust.processDeeplink(AdjustDeeplink(deepLink), dependencies.context)
            }) {
            Logger.logInfo("Adjust deeplink submitted for processing")
        }
    }

    override fun handleEvent(eventId: String) {
        if (eventId.isBlank()) {
            Logger.logWarning("Adjust trackEvent skipped: event ID is blank")
            return
        }

        val callbackId = UUID.randomUUID().toString()
        if (runAdjustCall("trackEvent") {
                val adjustEvent = AdjustEvent(eventId)
                adjustEvent.setCallbackId(callbackId)
                Adjust.trackEvent(adjustEvent)
            }) {
            Logger.logInfo("Adjust event submitted: event_id=$eventId, callback_id=$callbackId")
        }
    }

    override fun setEnabled(enabled: Boolean) {
        isAdjustEnabled = enabled
    }

    override fun isEnabled(): Boolean {
        return isAdjustEnabled
    }

    // MARK: - Monetization & Purchases

    override fun trackAdRevenue(source: String, revenue: Double, currency: String) {
        if (source.isBlank() || currency.isBlank() || !revenue.isFinite() || revenue < 0.0) {
            Logger.logWarning("Adjust trackAdRevenue skipped: invalid source, revenue, or currency")
            return
        }

        if (runAdjustCall("trackAdRevenue") {
                val adRevenue = AdjustAdRevenue(source).apply {
                    setRevenue(revenue, currency)
                }
                Adjust.trackAdRevenue(adRevenue)
            }) {
            Logger.logInfo("Adjust ad revenue submitted")
        }
    }

    override fun verifyAppStorePurchase(
        transactionId: String,
        productId: String,
        completion: (AdjustPurchaseVerificationResult) -> Unit
    ) {
        if (transactionId.isBlank() || productId.isBlank()) {
            Logger.logWarning("Adjust verifyPlayStorePurchase skipped: required purchase fields are blank")
            return
        }

        if (runAdjustCall("verifyPlayStorePurchase") {
                val purchase = AdjustPlayStorePurchase(transactionId, productId)
                Adjust.verifyPlayStorePurchase(purchase) { result ->
                    Logger.logInfo("Adjust purchase verification callback received")
                    completion(result)
                }
            }) {
            Logger.logInfo("Adjust purchase verification submitted")
        }
    }

    override fun verifyAndTrackPlayStorePurchase(
        event: AdjustEvent,
        listener: OnPurchaseVerificationFinishedListener
    ) {
        if (!event.isValid) {
            Logger.logWarning("Adjust verifyAndTrackPlayStorePurchase skipped: event is invalid")
            return
        }

        if (runAdjustCall("verifyAndTrackPlayStorePurchase") {
                Adjust.verifyAndTrackPlayStorePurchase(event, listener)
            }) {
            Logger.logInfo("Adjust purchase verification and event tracking submitted")
        }
    }

    override fun verifyPlayStorePurchase(
        purchase: AdjustPlayStorePurchase,
        listener: OnPurchaseVerificationFinishedListener
    ) {
        if (purchase.productId.isNullOrBlank()) {
            Logger.logWarning("Adjust verifyPlayStorePurchase skipped: product ID is blank")
            return
        }

        if (runAdjustCall("verifyPlayStorePurchase") {
                Adjust.verifyPlayStorePurchase(purchase, listener)
            }) {
            Logger.logInfo("Adjust purchase verification submitted")
        }
    }

    // MARK: - Subscriptions

    override fun trackPlayStoreSubscription(
        price: Long,
        currency: String,
        sku: String,
        orderId: String,
        signature: String,
        purchaseToken: String,
        purchaseTime: Long?
    ) {
        if (
            price < 0 || currency.isBlank() || sku.isBlank() || orderId.isBlank() ||
            signature.isBlank() || purchaseToken.isBlank() || (purchaseTime != null && purchaseTime < 0)
        ) {
            Logger.logWarning("Adjust trackPlayStoreSubscription skipped: invalid subscription fields")
            return
        }

        if (runAdjustCall("trackPlayStoreSubscription") {
                val playStoreSubscription = AdjustPlayStoreSubscription(
                    price,
                    currency,
                    sku,
                    orderId,
                    signature,
                    purchaseToken
                )
                purchaseTime?.let(playStoreSubscription::setPurchaseTime)
                Adjust.trackPlayStoreSubscription(playStoreSubscription)
            }) {
            Logger.logInfo("Adjust Play Store subscription submitted")
        }
    }

    // MARK: - Push Token

    override fun setPushToken(token: String) {
        if (token.isBlank()) {
            Logger.logWarning("Adjust setPushToken skipped: token is blank")
            return
        }

        if (runAdjustCall("setPushToken") {
                Adjust.setPushToken(token, dependencies.context)
            }) {
            Logger.logInfo("Adjust push token submitted")
        }
    }

    // MARK: - Device IDs & Advertising

    override fun getAdid(completion: (String?) -> Unit) {
        if (runAdjustCall("getAdid") {
                Adjust.getAdid { adid ->
                    if (adid.isNullOrBlank()) {
                        Logger.logWarning("Adjust ADID callback returned no value")
                    } else {
                        Logger.logInfo("Adjust ADID callback received")
                    }
                    completion(adid)
                }
            }) {
            Logger.logInfo("Adjust ADID request submitted")
        }
    }

    override fun getAdvertisingId(completion: (String?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(dependencies.context)
                val adId = adInfo.id
                withContext(Dispatchers.Main) {
                    if (adId.isNullOrBlank()) {
                        Logger.logWarning("Advertising ID request returned no value")
                    } else {
                        Logger.logInfo("Advertising ID callback received")
                    }
                    completion(adId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logLocalFailure("getAdvertisingId", e)
                withContext(Dispatchers.Main) {
                    completion(null)
                }
            }
        }
    }

    override fun getGoogleAdId(listener: OnGoogleAdIdReadListener) {
        if (runAdjustCall("getGoogleAdId") {
                Adjust.getGoogleAdId(dependencies.context, listener)
            }) {
            Logger.logInfo("Adjust Google Ad ID request submitted")
        }
    }

    override fun getAmazonAdId(listener: OnAmazonAdIdReadListener) {
        if (runAdjustCall("getAmazonAdId") {
                Adjust.getAmazonAdId(dependencies.context, listener)
            }) {
            Logger.logInfo("Adjust Amazon Ad ID request submitted")
        }
    }

    override fun getGooglePlayInstallReferrer(listener: OnGooglePlayInstallReferrerReadListener) {
        if (runAdjustCall("getGooglePlayInstallReferrer") {
                Adjust.getGooglePlayInstallReferrer(dependencies.context, listener)
            }) {
            Logger.logInfo("Adjust Google Play install referrer request submitted")
        }
    }

    // MARK: - Attribution & Deeplinks

    override fun getAdjustAttribution(completion: (AdjustAttribution?) -> Unit) {
        if (runAdjustCall("getAttribution") {
                Adjust.getAttribution { attribution ->
                    if (attribution == null) {
                        Logger.logWarning("Adjust attribution callback returned no value")
                    } else {
                        Logger.logInfo("Adjust attribution callback received")
                    }
                    completion(attribution)
                }
            }) {
            Logger.logInfo("Adjust attribution request submitted")
        }
    }

//    fun processAndResolveDeeplink(uri: Uri, listener: OnDeeplinkResolvedListener) {
//        val deeplink = AdjustDeeplink(uri)
//        Adjust.processAndResolveDeeplink(deeplink, dependencies.context, listener)
//        Logger.logInfo("processAndResolveDeeplink called with URI: $uri")
//    }

//    fun getLastDeeplink(listener: OnLastDeeplinkReadListener) {
//        Adjust.getLastDeeplink(dependencies.context, listener)
//        Logger.logInfo("Requested last deeplink")
//    }

    // MARK: - Lifecycle & Mode Switching

    override fun onResume() {
        if (runAdjustCall("onResume", Adjust::onResume)) {
            Logger.logInfo("Adjust onResume completed locally")
        }
    }

    override fun onPause() {
        if (runAdjustCall("onPause", Adjust::onPause)) {
            Logger.logInfo("Adjust onPause completed locally")
        }
    }

    override fun enable() {
        if (runAdjustCall("enable", Adjust::enable)) {
            Logger.logInfo("Adjust enable requested")
        }
    }

    override fun disable() {
        if (runAdjustCall("disable", Adjust::disable)) {
            Logger.logInfo("Adjust disable requested")
        }
    }

    override fun isEnabled(context: Context, listener: OnIsEnabledListener) {
        if (runAdjustCall("isEnabled") { Adjust.isEnabled(context, listener) }) {
            Logger.logInfo("Adjust isEnabled request submitted")
        }
    }

    override fun switchToOfflineMode() {
        if (runAdjustCall("switchToOfflineMode", Adjust::switchToOfflineMode)) {
            Logger.logInfo("Adjust offline mode requested")
        }
    }

    override fun switchBackToOnlineMode() {
        if (runAdjustCall("switchBackToOnlineMode", Adjust::switchBackToOnlineMode)) {
            Logger.logInfo("Adjust online mode requested")
        }
    }

    // MARK: - Global Parameters

    override fun addGlobalCallbackParameter(key: String, value: String) {
        if (key.isBlank() || value.isBlank()) {
            Logger.logWarning("Adjust addGlobalCallbackParameter skipped: key or value is blank")
            return
        }

        if (runAdjustCall("addGlobalCallbackParameter") {
                Adjust.addGlobalCallbackParameter(key, value)
            }) {
            Logger.logInfo("Adjust global callback parameter added locally")
        }
    }

    override fun addGlobalPartnerParameter(key: String, value: String) {
        if (key.isBlank() || value.isBlank()) {
            Logger.logWarning("Adjust addGlobalPartnerParameter skipped: key or value is blank")
            return
        }

        if (runAdjustCall("addGlobalPartnerParameter") {
                Adjust.addGlobalPartnerParameter(key, value)
            }) {
            Logger.logInfo("Adjust global partner parameter added locally")
        }
    }

    override fun removeGlobalCallbackParameter(key: String) {
        if (key.isBlank()) {
            Logger.logWarning("Adjust removeGlobalCallbackParameter skipped: key is blank")
            return
        }

        if (runAdjustCall("removeGlobalCallbackParameter") {
                Adjust.removeGlobalCallbackParameter(key)
            }) {
            Logger.logInfo("Adjust global callback parameter removed locally")
        }
    }

    override fun removeGlobalPartnerParameter(key: String) {
        if (key.isBlank()) {
            Logger.logWarning("Adjust removeGlobalPartnerParameter skipped: key is blank")
            return
        }

        if (runAdjustCall("removeGlobalPartnerParameter") {
                Adjust.removeGlobalPartnerParameter(key)
            }) {
            Logger.logInfo("Adjust global partner parameter removed locally")
        }
    }

    override fun removeGlobalCallbackParameters() {
        if (runAdjustCall("removeGlobalCallbackParameters", Adjust::removeGlobalCallbackParameters)) {
            Logger.logInfo("Adjust global callback parameters removed locally")
        }
    }

    override fun removeGlobalPartnerParameters() {
        if (runAdjustCall("removeGlobalPartnerParameters", Adjust::removeGlobalPartnerParameters)) {
            Logger.logInfo("Adjust global partner parameters removed locally")
        }
    }

    // MARK: - Consent & Test Options

    override fun trackMeasurementConsent(consent: Boolean) {
        if (runAdjustCall("trackMeasurementConsent") {
                Adjust.trackMeasurementConsent(consent)
            }) {
            Logger.logInfo("Adjust measurement consent submitted")
        }
    }

    override fun getSdkVersion(listener: OnSdkVersionReadListener) {
        if (runAdjustCall("getSdkVersion") { Adjust.getSdkVersion(listener) }) {
            Logger.logInfo("Adjust SDK version request submitted")
        }
    }

//    fun setTestOptions(options: AdjustTestOptions) {
//        Adjust.setTestOptions(options)
//        Logger.logInfo("Test options set: $options")
//    }

    // MARK: - Referrer

    override fun setReferrer(referrer: String) {
        if (referrer.isBlank()) {
            Logger.logWarning("Adjust setReferrer skipped: referrer is blank")
            return
        }

        if (runAdjustCall("setReferrer") {
                Adjust.setReferrer(referrer, dependencies.context)
            }) {
            Logger.logInfo("Adjust referrer submitted")
        }
    }

    // MARK: - GDPR and Third-Party Sharing

    override fun gdprForgetMe(context: Context?) {
        if (context == null) {
            Logger.logWarning("Adjust gdprForgetMe skipped: context is missing")
            return
        }

        if (runAdjustCall("gdprForgetMe") { Adjust.gdprForgetMe(context) }) {
            Logger.logInfo("Adjust GDPR forget-me request submitted")
        }
    }

    override fun trackThirdPartySharing(isEnabled: Boolean) {
        if (runAdjustCall("trackThirdPartySharing") {
                Adjust.trackThirdPartySharing(AdjustThirdPartySharing(isEnabled))
            }) {
            Logger.logInfo("Adjust third-party sharing setting submitted")
        }
    }

    // MARK: - Internal Deeplink Handling

    private fun handleAdjustDeeplink(deepLink: Uri?) {
        if (deepLink != null) {
            Logger.logInfo("Adjust deferred deeplink callback received")
            val tappInstance = dependencies.tappInstance
            if (tappInstance == null) {
                Logger.logError("Adjust deferred deeplink handling failed: Tapp instance is unavailable")
                return
            }

            runAdjustCall("deferred deeplink handling") {
                tappInstance.appWillOpenInt(deepLink.toString(), null)
            }
        } else {
            Logger.logWarning("Received null deeplink from Adjust.")
        }
    }

    private inline fun runAdjustCall(methodName: String, call: () -> Unit): Boolean {
        return try {
            call()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logLocalFailure(methodName, e)
            false
        }
    }

    private fun logLocalFailure(methodName: String, error: Exception) {
        val errorType = error::class.java.simpleName.ifBlank { "Exception" }
        Logger.logError("Adjust $methodName failed locally: $errorType")
    }
}
