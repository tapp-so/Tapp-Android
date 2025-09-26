
package com.example.tapp

import android.content.Context
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.services.affiliate.AffiliateServiceFactory
import com.example.tapp.services.affiliate.native.NativeService
import com.example.tapp.services.network.NetworkManager
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

    fun dummyMethod() {
        (engine.dependencies.keystoreUtils.getConfig()?.affiliate?.let {
            engine.dependencies.affiliateServiceFactory.getAffiliateService(
                it, engine.dependencies)
        } as? NativeService)?.dummyNativeMethod()
    }
}
