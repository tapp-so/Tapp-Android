package com.example.tapp.services.network

import org.json.JSONObject

internal class BackendApiException(
    val statusCode: Int?,
    val backendMessage: String,
    val errorCode: String?,
    cause: Throwable? = null
) : Exception(
    buildString {
        append(backendMessage)
        statusCode?.let { append(" (HTTP $it)") }
        errorCode?.let { append(" [$it]") }
    },
    cause
)

internal object BackendResponseParser {
    fun parseHttpResponse(statusCode: Int, responseBody: String): Result<JSONObject> {
        val json = try {
            JSONObject(responseBody)
        } catch (error: Exception) {
            if (statusCode !in 200..299) {
                return Result.failure(
                    BackendApiException(
                        statusCode = statusCode,
                        backendMessage = "HTTP $statusCode",
                        errorCode = null,
                        cause = error
                    )
                )
            }
            return Result.failure(error)
        }

        if (statusCode !in 200..299) {
            return Result.failure(json.toBackendException(statusCode))
        }

        return requireSuccess(json, statusCode, rejectLegacyError = false)
    }

    fun requireSuccess(
        json: JSONObject,
        statusCode: Int? = null,
        rejectLegacyError: Boolean = true
    ): Result<JSONObject> {
        if (json.booleanOrNull("success") == false) {
            return Result.failure(json.toBackendException(statusCode))
        }

        if (rejectLegacyError && json.booleanOrNull("error") == true) {
            return Result.failure(json.toBackendException(statusCode))
        }

        return Result.success(json)
    }

    private fun JSONObject.toBackendException(statusCode: Int?): BackendApiException {
        return BackendApiException(
            statusCode = statusCode,
            backendMessage = stringOrNull("message")
                ?: statusCode?.let { "HTTP $it" }
                ?: "Backend reported an error",
            errorCode = stringOrNull("error_code")
        )
    }

    private fun JSONObject.booleanOrNull(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return opt(key) as? Boolean
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return (opt(key) as? String)?.takeIf { it.isNotBlank() }
    }
}

internal fun Result<JSONObject>.requireBackendSuccess(
    rejectLegacyError: Boolean = true
): Result<JSONObject> = mapCatching { json ->
    BackendResponseParser.requireSuccess(
        json = json,
        rejectLegacyError = rejectLegacyError
    ).getOrThrow()
}
