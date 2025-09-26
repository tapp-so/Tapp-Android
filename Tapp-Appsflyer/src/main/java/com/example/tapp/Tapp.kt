
package com.example.tapp

import android.content.Context
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.services.affiliate.AffiliateServiceFactory
import com.example.tapp.services.affiliate.appsflyer.AppsflyerService
import com.example.tapp.services.network.NetworkManager
import com.example.tapp.utils.KeystoreUtils
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

        // Register the affiliate service directly
        AffiliateServiceFactory.register(com.example.tapp.models.Affiliate.APPSFLYER) { deps ->
            com.example.tapp.services.affiliate.appsflyer.AppsflyerAffiliateService(deps)
        }
    }

    fun start(config: TappConfiguration) = engine.start(config)

    fun dummyMethod() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? AppsflyerService)?.dummyAppsflyerMethod()
    }
}
