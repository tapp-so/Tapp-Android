package com.example.tapp.services.network

import com.example.tapp.models.Affiliate

sealed class TappError : Throwable() {
    data class MissingConfiguration(override val message: String = "Configuration is missing") : TappError()
    data class MissingAffiliateService(override val message: String) : TappError() // Updated
    data class AffiliateServiceError(val affiliate: Affiliate, val underlyingError: Throwable) : TappError()
    data class InvalidResponse(override val message: String = "Invalid response") : TappError()
    data class MissingParameters(val details: String) : TappError()
    data class InitializationFailed(val details: String) : TappError()

    companion object {
        fun affiliateErrorResult(error: Throwable, affiliate: Affiliate): AffiliateServiceError {
            return AffiliateServiceError(
                affiliate = affiliate,
                underlyingError = error
            )
        }
    }
}

