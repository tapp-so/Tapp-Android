
package com.example.tapp

import android.content.Context
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.services.affiliate.AffiliateServiceFactory
import com.example.tapp.services.affiliate.native.NativeService
import com.example.tapp.services.affiliate.tapp.DeferredLinkDelegate
import com.example.tapp.services.network.NetworkManager
import com.example.tapp.services.network.RequestModels
import com.example.tapp.utils.KeystoreUtils
import com.example.tapp.utils.Logger
import com.example.tapp.utils.TappConfiguration

class Tapp(context: Context) {

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

        AffiliateServiceFactory.register(com.example.tapp.models.Affiliate.TAPP_NATIVE) { deps -> com.example.tapp.services.affiliate.native.NativeAffiliateService(deps) }

    }

    fun start(config: TappConfiguration) = engine.start(config)

    fun shouldProcess(url: String?): Boolean = engine.shouldProcess(url)

    suspend fun fetchLinkData(url: String) = engine.fetchLinkData(url)

    suspend fun fetchOriginalLinkData() = engine.fetchOriginalLinkData()
    fun dummyMethod() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? NativeService)?.dummyNativeMethod()
    }

    fun handleTesListener(test: String) = engine.handleTesListener(test)

    fun simulateTestEvent() {
        Logger.logInfo("tapp native simulateTestEvent")
        engine.simulateTestEvent()
    }

    suspend fun url(influencer: String, adGroup: String?, creative: String?, data: Map<String, String>? = null) =
        engine.url(influencer, adGroup, creative, data)

    fun handleTappEvent(tappEvent: RequestModels.TappEvent) = engine.handleTappEvent(tappEvent)
    var deferredLinkDelegate: DeferredLinkDelegate?
        get() = engine.deferredLinkDelegate
        set(value) {
            engine.deferredLinkDelegate = value
        }

}
