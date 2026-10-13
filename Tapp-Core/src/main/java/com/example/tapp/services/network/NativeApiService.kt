package com.example.tapp.services.network

import androidx.annotation.RestrictTo
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object NativeApiService {

    /**
     * How Tapp-Native should treat a failed `/ref/fingerprint` call. SDK-internal: it is public only
     * because Tapp-Native cannot see Tapp-Core internals.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    enum class DeferredLinkFailure {
        /** HTTP 404 "Data miss-match": the backend found no attribution for this install. */
        NO_MATCH,

        /**
         * HTTP 422 "Fpid does not exist": the referrer that was sent had no `fpid`. Only definitive
         * when the real Play Install Referrer was sent (not the "null" placeholder).
         */
        MISSING_FPID,

        /** IO failures and timeouts, HTTP 429 and 5xx. */
        RETRYABLE,

        /** Everything else: 401/403, "Application not found.", other 4xx, malformed bodies. */
        NON_RETRYABLE
    }

    // Backend has no dedicated no-match error code yet, so match the exact message as well.
    private const val NO_MATCH_MESSAGE = "Data miss-match"
    private const val MISSING_FPID_MESSAGE = "Fpid does not exist"

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun classifyDeferredLinkFailure(error: Throwable): DeferredLinkFailure {
        return when (error) {
            is BackendApiException -> when {
                error.statusCode == 404 && error.errorCode == "NOT_FOUND" &&
                        error.backendMessage == NO_MATCH_MESSAGE -> DeferredLinkFailure.NO_MATCH
                error.statusCode == 422 && error.errorCode == "VALIDATION_ERROR" &&
                        error.backendMessage == MISSING_FPID_MESSAGE -> DeferredLinkFailure.MISSING_FPID
                error.statusCode == 429 || error.statusCode in 500..599 -> DeferredLinkFailure.RETRYABLE
                else -> DeferredLinkFailure.NON_RETRYABLE
            }
            is IOException -> DeferredLinkFailure.RETRYABLE
            else -> DeferredLinkFailure.NON_RETRYABLE
        }
    }

    suspend fun fetchDeferredLink(
        dependencies: Dependencies,
        request: RequestModels.DeferredLinkRequest
    ): Result<RequestModels.DeferredLinkResponse> {

        val endpoint = TappEndpoint.getDeferredLink(dependencies, request)

        return withContext(Dispatchers.IO) {
            val result = dependencies.networkManager.postRequest(
                url = endpoint.url,
                params = endpoint.body,
                headers = endpoint.headers
            )

            result.fold(
                onSuccess = { jsonResponse ->
                    try {
                        val deeplink = jsonResponse.optString("deeplink", null)

                        val error = jsonResponse.optBoolean("error", false)
                        val fingerprint = jsonResponse.optString("fingerprint", null)
                        val tappUrl = jsonResponse.optString("tapp_url", null)
                        val attrTappUrl = jsonResponse.optString("attr_tapp_url", null)
                        val influencer = jsonResponse.optString("influencer", null)

                        val dataObj = jsonResponse.optJSONObject("data")
                        val data: Map<String, String>? = dataObj?.let {
                            it.keys().asSequence().associateWith { k -> it.optString(k, "") }
                        }

                        Result.success(RequestModels.DeferredLinkResponse(
                            deeplink,
                            fingerprint,
                            error,
                            tappUrl,
                            attrTappUrl,
                            influencer,
                            data))
                    } catch (e: Exception) {
                        Logger.logError("Failed to parse deferred link response: ${e.message}")
                        Result.failure(e)
                    }
                },
                onFailure = { error ->
                    Logger.logError("Failed to fetch deferred link: ${error.message}")
                    Result.failure(error)
                }
            )
        }
    }
}
