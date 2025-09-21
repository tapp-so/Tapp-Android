package com.example.tapp.utils

import com.example.tapp.models.Environment

object Logger {
    private var ENABLE_LOGGING = true

    fun init(env: Environment) {
        // Disable logging if the environment is SANDBOX
        ENABLE_LOGGING = env == Environment.SANDBOX
    }

    fun logError(error: String) {
        if (ENABLE_LOGGING) {
            println("Tapp-Error: $error")
        }
    }

    fun logInfo(message: String) {
        if (ENABLE_LOGGING) {
            println("Tapp-Info: $message")
        }
    }

    fun logDebug(message: String) {
        if (ENABLE_LOGGING) {
            println("Tapp-Debug: $message")
        }
    }

    fun logWarning(message: String) {
        if (ENABLE_LOGGING) {
            println("Tapp-Warning: $message")
        }
    }
}
