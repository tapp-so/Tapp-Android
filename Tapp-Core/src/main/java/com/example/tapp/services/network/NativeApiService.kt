package com.example.tapp.services.network

import com.example.tapp.dependencies.Dependencies
import com.example.tapp.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NativeApiService {

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
