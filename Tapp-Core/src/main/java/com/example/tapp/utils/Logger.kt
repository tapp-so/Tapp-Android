package com.example.tapp.utils

import com.example.tapp.models.Environment

object Logger {
    private var ENABLE_LOGGING = true

    private const val REDACTED = "[REDACTED]"

    private val sensitiveKeyValue = Regex(
        pattern = """(?i)((?<![A-Za-z0-9_])[\"']?(?:authorization|auth(?:_|\s)?token|tapp(?:_|\s)?token|app(?:_|\s)?token|wre_token|secret|android(?:_|\s)?id|device(?:_|\s)?id|advertising(?:_|\s)?id|gaid|link(?:_|\s)?token|deeplink(?:_|\s)?url|deeplink|install(?:_|\s)?referrer|referrer|click(?:_|\s)?id)[\"']?\s*[:=]\s*)(?:Bearer\s+[^\s,}\]]+|\"[^\"]*\"|'[^']*'|[^,\s}\]]+)"""
    )
    private val bearerToken = Regex("""(?i)\bBearer\s+[^\s,}\]]+""")
    private val attributionQueryToken = Regex(
        pattern = """(?i)([?&](?:t|adj_t|af_t|tapp_t)=)[^&#\s]*"""
    )

    fun init(env: Environment) {
        // Disable logging if the environment is SANDBOX
        ENABLE_LOGGING = env == Environment.SANDBOX
    }

    fun logError(error: String) {
        if (ENABLE_LOGGING) {
            println("Tapp-Error: ${redact(error)}")
        }
    }

    fun logInfo(message: String) {
        if (ENABLE_LOGGING) {
            println("Tapp-Info: ${redact(message)}")
        }
    }

    fun logDebug(message: String) {
        if (ENABLE_LOGGING) {
            println("Tapp-Debug: ${redact(message)}")
        }
    }

    fun logWarning(message: String) {
        if (ENABLE_LOGGING) {
            println("Tapp-Warning: ${redact(message)}")
        }
    }

    internal fun redact(message: String): String {
        return message
            .replace(sensitiveKeyValue) { match -> "${match.groupValues[1]}$REDACTED" }
            .replace(bearerToken, REDACTED)
            .replace(attributionQueryToken) { match -> "${match.groupValues[1]}$REDACTED" }
    }
}
