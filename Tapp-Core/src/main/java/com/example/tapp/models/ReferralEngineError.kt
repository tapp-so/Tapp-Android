package com.example.tapp.models

sealed class ReferralEngineError(message: String) : Exception(message) {
    class NetworkError(message: String) : ReferralEngineError(message)
    class ApiError(message: String) : ReferralEngineError(message)
    class InitializationError(message: String) : ReferralEngineError(message)
    // Add other specific error types as needed
}
