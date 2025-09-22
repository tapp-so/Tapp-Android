package com.tapp.tappnative

import android.content.Context
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.services.affiliate.AffiliateServiceFactory
import com.tapp.tappnative.services.affiliate.native.NativeService
import com.example.tapp.services.network.NetworkManager
import com.example.tapp.utils.KeystoreUtils

class Tapp(context: Context) {

    private val engine: TappEngine

    init {
        val dependencies = Dependencies(
            context = context,
            keystoreUtils = KeystoreUtils(context),
            networkManager = NetworkManager(),
            affiliateServiceFactory = AffiliateServiceFactory()
        )
        engine = TappEngine(dependencies)
        dependencies.tappInstance = engine

        AffiliateServiceFactory.register(com.example.tapp.models.Affiliate.TAPP) { deps -> com.example.tapp.services.affiliate.native.NativeAffiliateService(deps) }
    }

    fun dummyMethod() {
        (engine.dependencies.affiliateServiceFactory.getAffiliateService(engine.dependencies.keystoreUtils.getConfig()?.affiliate, engine.dependencies) as? NativeService)?.dummyNativeMethod()
    }
}
