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
                        //TODO::replace with jsonResponse.optString("deeplink", null)
                        val deeplink = "https://nta.staging.tapp.so?t=native_test_user";
                            //jsonResponse.optString("deeplink", null)
                        val error = jsonResponse.optBoolean("error", true)
                        val fingerprint = jsonResponse.optString("fingerprint", null)
                        Result.success(RequestModels.DeferredLinkResponse(deeplink,fingerprint, error))
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
