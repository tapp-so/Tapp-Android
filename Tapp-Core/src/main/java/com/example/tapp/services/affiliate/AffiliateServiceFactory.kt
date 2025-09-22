package com.example.tapp.services.affiliate

import com.example.tapp.dependencies.Dependencies
import com.example.tapp.models.Affiliate
import com.example.tapp.services.affiliate.tapp.TappAffiliateService

object AffiliateServiceFactory {

    private val serviceProviders = mutableMapOf<Affiliate, (Dependencies) -> AffiliateService>()

    init {
        // The Tapp service is a core component, so we can register it directly.
        register(Affiliate.TAPP) { TappAffiliateService(it) }
    }

    fun register(affiliate: Affiliate, provider: (Dependencies) -> AffiliateService) {
        if (!serviceProviders.containsKey(affiliate)) {
            serviceProviders[affiliate] = provider
        }
    }

    fun getAffiliateService(affiliate: Affiliate, dependencies: Dependencies): AffiliateService? {
        val provider = serviceProviders[affiliate]

        return provider?.invoke(dependencies)
    }
}