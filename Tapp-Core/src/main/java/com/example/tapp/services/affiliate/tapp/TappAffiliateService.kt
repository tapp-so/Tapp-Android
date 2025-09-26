package com.example.tapp.services.affiliate.tapp

import android.net.Uri
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.models.AdjustURLParamKey
import com.example.tapp.models.Affiliate
import com.example.tapp.models.AppsflyerURLParamKey
import com.example.tapp.models.TappURLParamKey
import com.example.tapp.models.linkToken
import com.example.tapp.models.param
import com.example.tapp.services.affiliate.AffiliateService
import com.example.tapp.services.network.RequestModels
import com.example.tapp.services.network.TappEndpoint
import com.example.tapp.services.network.TappError
import com.example.tapp.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

internal class TappAffiliateService(private val dependencies: Dependencies) : AffiliateService {
    private var isTapEnabled: Boolean = false

    override fun initialize(): Boolean {
        TODO("TAPP: Not yet implemented")
    }

    override fun handleCallback(deepLink: Uri) {
        TODO("TAPP: Not yet implemented")
    }

    override fun handleEvent(eventId: String) {
        Logger.logWarning("Use the handleTappEvent method to handle Tapp events")
    }

    override fun setEnabled(enabled: Boolean) {
        isTapEnabled = enabled
    }

    override fun isEnabled(): Boolean {
        return isTapEnabled
    }

    suspend fun generateAffiliateUrl(request: RequestModels.AffiliateUrlRequest): RequestModels.AffiliateUrlResponse = withContext(Dispatchers.IO) {
        val config = dependencies.keystoreUtils.getConfig()
            ?: return@withContext RequestModels.AffiliateUrlResponse(
                error = true,
                message = "Missing configuration",
                influencer_url = ""
            )

        // Construct the endpoint using TappEndpoint
        val generateUrlRequest = RequestModels.GenerateAffiliateUrlRequest(
            tappToken = config.tappToken,
            mmp = config.affiliate.toIntValue(),
            influencer = request.influencer,
            adGroup = request.adGroup,
            creative = request.creative,
            data = request.data,
            authToken = config.authToken
        )

        val endpoint = TappEndpoint.generateAffiliateUrl(dependencies,generateUrlRequest)

        try {
            val result = dependencies.networkManager.postRequest(endpoint.url, endpoint.body!!, endpoint.headers)
            result.fold(
                onSuccess = { jsonResponse ->
                    RequestModels.AffiliateUrlResponse(
                        error = jsonResponse.optBoolean("error", true),
                        message = jsonResponse.optString("message", "Unknown error"),
                        influencer_url = jsonResponse.optString("influencer_url", "")
                    )
                },
                onFailure = { exception ->
                    RequestModels.AffiliateUrlResponse(
                        error = true,
                        message = "Exception occurred: ${exception.localizedMessage}",
                        influencer_url = ""
                    )
                }
            )
        } catch (e: Exception) {
            RequestModels.AffiliateUrlResponse(
                error = true,
                message = "Failed to parse response: ${e.localizedMessage}",
                influencer_url = ""
            )
        }
    }

    fun fetchSecrets(completion: (Result<RequestModels.SecretsResponse>) -> Unit) {
        dependencies.keystoreUtils.getConfig()
            ?: return completion(Result.failure(TappError.MissingConfiguration("Missing configuration")))

        val endpoint = TappEndpoint.secrets(dependencies)

        CoroutineScope(Dispatchers.IO).launch {
            val result = dependencies.networkManager.postRequest(endpoint.url, endpoint.body, endpoint.headers)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { jsonResponse ->
                        try {
                            val secretsResponse = parseSecretsResponse(jsonResponse)
                            completion(Result.success(secretsResponse))
                        } catch (e: TappError.InvalidResponse) {
                            completion(Result.failure(e))
                        }
                    },
                    onFailure = { error ->
                        completion(Result.failure(error))
                    }
                )
            }
        }
    }

    fun handleImpression(url: Uri, completion: (Result<RequestModels.TappUrlResponse>) -> Unit) {
        val config = dependencies.keystoreUtils.getConfig()
            ?: return completion(Result.failure(TappError.MissingConfiguration("Missing configuration")))

        val endpoint = TappEndpoint.deeplink(dependencies, deepLink = url)

        CoroutineScope(Dispatchers.IO).launch {
            val networkResult = dependencies.networkManager.postRequest(endpoint.url, endpoint.body, endpoint.headers)
            withContext(Dispatchers.Main) {
                networkResult.fold(
                    onSuccess = { jsonObject ->
                        // jsonObject is already a JSONObject
                        val tappUrlResponse = parseTappUrlResponse(jsonObject)
                        completion(Result.success(tappUrlResponse))
                    },
                    onFailure = { error ->
                        completion(Result.failure(error))
                    }
                )
            }
        }
    }

    suspend fun trackEvent(tappEventRequest: RequestModels.TappEvent): Result<Unit> {
        val endpoint = TappEndpoint.tappEvent(dependencies, tappEventRequest)

        return withContext(Dispatchers.IO) {
            try {
                // Execute the POST request
                val result = dependencies.networkManager.postRequest(endpoint.url, endpoint.body, endpoint.headers)

                // Handle the result
                result.fold(
                    onSuccess = { jsonResponse ->
                        if (!jsonResponse.optBoolean("error", false)) {
                            Logger.logInfo("Event tracking succeeded: ${tappEventRequest.eventName}")
                            return@withContext Result.success(Unit)
                        } else {
                            val message = jsonResponse.optString("message", "Unknown server error")
                            Logger.logError("Event tracking failed: $message")
                            return@withContext Result.failure(TappError.InvalidResponse("Server error: $message"))
                        }
                    },
                    onFailure = { error ->
                        Logger.logError("Network request failed: ${error.localizedMessage}")
                        return@withContext Result.failure(error)
                    }
                )
            } catch (e: Exception) {
                // Log and return failure for unexpected exceptions
                Logger.logError("Unexpected error: ${e.localizedMessage}")
                Result.failure(TappError.InvalidResponse("Unexpected error occurred: ${e.localizedMessage}"))
            }
        }
    }

    fun shouldProcess(url: Uri): Boolean {
        val config = dependencies.keystoreUtils.getConfig() ?: return false

        return when (config.affiliate) {
            Affiliate.ADJUST -> url.param(AdjustURLParamKey.TOKEN.value) != null
            Affiliate.APPSFLYER -> url.param(AppsflyerURLParamKey.TOKEN.value) != null
            Affiliate.TAPP -> url.param(TappURLParamKey.TOKEN.value) != null
            Affiliate.TAPP_NATIVE -> url.param(TappURLParamKey.TOKEN.value) != null
        }
    }

    suspend fun callLinkDataService(url: Uri, isFirstSession: Boolean? = null): RequestModels.TappLinkDataResponse {
        val config = dependencies.keystoreUtils.getConfig()
            ?: return RequestModels.errorTappLinkDataResponse("Missing configuration")

        val linkToken = url.linkToken(config.affiliate)
            ?: return RequestModels.errorTappLinkDataResponse("Link token not found in URL")

        val fetchDataRequest = RequestModels.TappLinkDataRequest(linkToken = linkToken)
        val endpoint = TappEndpoint.fetchLinkData(dependencies, fetchDataRequest)

        return try {
            val result = dependencies.networkManager.postRequest(endpoint.url, endpoint.body, endpoint.headers)
            result.fold(
                onSuccess = { jsonResponse ->
                    val dataMap: Map<String, String> = jsonResponse.optJSONObject("data")?.let { json ->
                        val map = mutableMapOf<String, String>()
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            map[key] = json.optString(key)
                        }
                        map
                    } ?: emptyMap()
                    val finalIsFirstSession = isFirstSession ?: !config.hasProcessedReferralEngine

                    RequestModels.TappLinkDataResponse(
                        error = jsonResponse.optBoolean("error", true),
                        message = jsonResponse.optString("message", "Ok"),
                        tappUrl = jsonResponse.optString("tapp_url", "tapp_url didn't returned"),
                        attrTappUrl = jsonResponse.optString("attr_tapp_url", "attr_tapp_url didn't returned"),
                        influencer = jsonResponse.optString("influencer", "influencer didn't returned"),
                        data = dataMap,
                        isFirstSession = finalIsFirstSession
                    )
                },
                onFailure = { exception ->
                    RequestModels.errorTappLinkDataResponse("Failed to parse response: ${exception.localizedMessage}")
                }
            )
        } catch (e: Exception) {
            RequestModels.errorTappLinkDataResponse("Failed to parse response: ${e.localizedMessage}")
        }
    }

    private fun parseSecretsResponse(response: JSONObject): RequestModels.SecretsResponse {
        // Extract the "secret" field from the JSON response
        val secret = response.optString("secret")
        if (secret.isNullOrEmpty()) {
            throw TappError.InvalidResponse()
        }
        return RequestModels.SecretsResponse(secret)
    }

    private fun parseTappUrlResponse(jsonObject: JSONObject): RequestModels.TappUrlResponse {
        return RequestModels.TappUrlResponse(
            error = jsonObject.optBoolean("error", false),
            message = jsonObject.optString("message", ""),
        )
    }

}
