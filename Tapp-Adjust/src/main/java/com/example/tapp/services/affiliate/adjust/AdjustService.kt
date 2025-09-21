
package com.example.tapp.services.affiliate.adjust

import android.content.Context
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

interface AdjustService {
    fun trackAdRevenue(source: String, revenue: Double, currency: String)
    fun verifyAppStorePurchase(
        transactionId: String,
        productId: String,
        completion: (AdjustPurchaseVerificationResult) -> Unit
    )
    fun setPushToken(token: String)
    fun gdprForgetMe(context: Context?)
    fun trackThirdPartySharing(isEnabled: Boolean)
    fun getAdjustAttribution(completion: (AdjustAttribution?) -> Unit)
    fun getAdid(completion: (String?) -> Unit)
    fun getAdvertisingId(completion: (String?) -> Unit)
    fun trackPlayStoreSubscription(
        price: Long,
        currency: String,
        sku: String,
        orderId: String,
        signature: String,
        purchaseToken: String,
        purchaseTime: Long? = null
    )
    fun verifyAndTrackPlayStorePurchase(
        event: AdjustEvent,
        listener: OnPurchaseVerificationFinishedListener
    )
    fun verifyPlayStorePurchase(
        purchase: AdjustPlayStorePurchase,
        listener: OnPurchaseVerificationFinishedListener
    )
    fun getGoogleAdId(listener: OnGoogleAdIdReadListener)
    fun getAmazonAdId(listener: OnAmazonAdIdReadListener)
    fun getGooglePlayInstallReferrer(listener: OnGooglePlayInstallReferrerReadListener)
    fun onResume()
    fun onPause()
    fun enable()
    fun disable()
    fun isEnabled(context: Context, listener: OnIsEnabledListener)
    fun switchToOfflineMode()
    fun switchBackToOnlineMode()
    fun addGlobalCallbackParameter(key: String, value: String)
    fun addGlobalPartnerParameter(key: String, value: String)
    fun removeGlobalCallbackParameter(key: String)
    fun removeGlobalPartnerParameter(key: String)
    fun removeGlobalCallbackParameters()
    fun removeGlobalPartnerParameters()
    fun trackMeasurementConsent(consent: Boolean)
    fun getSdkVersion(listener: OnSdkVersionReadListener)
    fun setReferrer(referrer: String)
}
