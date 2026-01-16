package com.example.tapp

import android.net.Uri
import androidx.core.net.toUri
import com.example.tapp.models.Affiliate
import com.example.tapp.models.linkToken
import com.example.tapp.services.affiliate.tapp.TappAffiliateService
import com.example.tapp.services.network.RequestModels
import com.example.tapp.services.network.TappError
import com.example.tapp.utils.Logger
import com.example.tapp.utils.TappConfiguration
import com.example.tapp.utils.VoidCompletion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


internal fun TappEngine.appWillOpen(url: Uri, completion: VoidCompletion?) {
    val config = dependencies.keystoreUtils.getConfig()
    if (config == null) {
        // No config -> cannot proceed
        completion?.invoke(Result.failure(TappError.MissingConfiguration()))
        return
    }

    // Check if we already have secrets and the service is enabled
    val service = dependencies.affiliateServiceFactory
        .getAffiliateService(config.affiliate, dependencies)

    val hasSecrets = config.appToken != null
    val isServiceEnabled = service?.isEnabled() == true

    // If we ALREADY have secrets + the service is enabled,
    // skip re-fetching secrets or re-initializing,
    // and go straight to handleReferralCallback:
    if (hasSecrets && isServiceEnabled) {
        handleReferralCallback(url, completion)
        return
    }

    // Otherwise, secrets or init are still needed:
    fetchSecretsAndInitializeReferralEngineIfNeeded { result ->
        result.fold(
            onSuccess = {
                // Now that secrets & init are done, handle the callback
                handleReferralCallback(url, completion)
            },
            onFailure = { error ->
                completion?.invoke(Result.failure(error))
            }
        )
    }
}

internal fun TappEngine.appWillOpenNative(deferredLinkResponse: RequestModels.DeferredLinkResponse, completion: VoidCompletion?) {
    val config = dependencies.keystoreUtils.getConfig()
    if (config == null) {
        // No config -> cannot proceed
        completion?.invoke(Result.failure(TappError.MissingConfiguration()))
        return
    }

    // Check if we already have secrets and the service is enabled
    val service = dependencies.affiliateServiceFactory
        .getAffiliateService(config.affiliate, dependencies)

    val hasSecrets = config.appToken != null
    val isServiceEnabled = service?.isEnabled() == true

    // If we ALREADY have secrets + the service is enabled,
    // skip re-fetching secrets or re-initializing,
    // and go straight to handleReferralCallback:
    if (hasSecrets && isServiceEnabled) {
        handleReferralCallbackNative(deferredLinkResponse, completion)
        return
    }

    // Otherwise, secrets or init are still needed:
    fetchSecretsAndInitializeReferralEngineIfNeeded { result ->
        result.fold(
            onSuccess = {
                // Now that secrets & init are done, handle the callback
                handleReferralCallbackNative(deferredLinkResponse, completion)
            },
            onFailure = { error ->
                completion?.invoke(Result.failure(error))
            }
        )
    }
}

internal fun TappEngine.appWillNotProcessNative(){
    setProcessedReferralEngine()
}

internal fun TappEngine.handleReferralCallback(
    url: Uri,
    completion: VoidCompletion?
) {
    // Step 1: Get Tapp service instance
    val tappService =
        dependencies.affiliateServiceFactory.getAffiliateService(Affiliate.TAPP, dependencies)
    if (tappService !is TappAffiliateService) {
        completion?.invoke(Result.failure(TappError.MissingAffiliateService("Affiliate service not available for tappService")))
        return
    }

    val config = dependencies.keystoreUtils.getConfig()
    if (config == null) {
        // No config -> cannot proceed
        completion?.invoke(Result.failure(TappError.MissingConfiguration()))
        return
    }

    // Step 2: Handle impression (for attribution purposes)
    tappService.handleImpression(url) { result ->
        result.fold(
            onSuccess = { tappUrlResponse ->
                Logger.logInfo("handleImpression success: $tappUrlResponse")

                val linkToken = url.linkToken(config.affiliate);

                if (linkToken != null) {
                    Logger.logInfo("Extracted linkToken: $linkToken")
                }

                // Save the deep link URL and link token in configuration
                saveDeepLinkUrl(url.toString())
                saveLinkToken(linkToken)
                setProcessedReferralEngine()

                // Step 3: Now fetch deferred link data with isFirstSession = true
                // Launch a coroutine because fetchLinkData is suspendable.
                CoroutineScope(Dispatchers.IO).launch {
                    val linkDataResponse = tappService.callLinkDataService(url, isFirstSession = true)
                    withContext(Dispatchers.Main) {
                        // Notify the delegate with the fetched link data
                        dependencies.tappInstance?.deferredLinkDelegate?.didReceiveDeferredLink(linkDataResponse)
                        // Signal that the processing is complete.
                        completion?.invoke(Result.success(Unit))
                    }
                }
            },
            onFailure = { error ->
                dependencies.tappInstance?.deferredLinkDelegate?.didFailResolvingUrl(
                    response = RequestModels.FailResolvingUrlResponse(
                        error = error.message ?: "Couldn't resolve the deeplink $url",
                        url = url.toString()
                    )
                )

                completion?.invoke(Result.failure(TappError.affiliateErrorResult(error, Affiliate.TAPP)))
            }
        )
    }
}

internal fun TappEngine.handleReferralCallbackNative(
    deferredLinkResponse: RequestModels.DeferredLinkResponse,
    completion: VoidCompletion?
) {
    // 1) Resolve Tapp service
    val tappService = dependencies.affiliateServiceFactory
        .getAffiliateService(Affiliate.TAPP, dependencies)
    if (tappService !is TappAffiliateService) {
        completion?.invoke(Result.failure(TappError.MissingAffiliateService("Affiliate service not available for tappService")))
        return
    }

    // 2) Require config
    val config = dependencies.keystoreUtils.getConfig()
    if (config == null) {
        completion?.invoke(Result.failure(TappError.MissingConfiguration()))
        return
    }

    // 3) Pick URL from response (prefer tappUrl, fallback to deeplink)
    val urlString = deferredLinkResponse.deeplink ?: deferredLinkResponse.tappUrl
    val url: Uri = try {
        val value = urlString?.toUri()
        if (value == null) {
            completion?.invoke(
                Result.failure(
                    TappError.InitializationFailed("Missing tappUrl/deeplink in DeferredLinkResponse")
                )
            )
            return
        }
        value
    } catch (e: Exception) {
        // Mirror the old failure path side-effects
        dependencies.tappInstance?.deferredLinkDelegate?.didFailResolvingUrl(
            response = RequestModels.FailResolvingUrlResponse(
                error = "Couldn't parse deeplink (${e.message})",
                url = urlString ?: ""
            )
        )
        completion?.invoke(
            Result.failure(
                TappError.InitializationFailed("Invalid deeplink format: ${e.message}")
            )
        )
        return
    }

    //check the
    val response = RequestModels.TappLinkDataResponse(
        error = deferredLinkResponse.error == true,
        message = if (deferredLinkResponse.error == true)
            "Something went wrong on deferred deep link"
        else
            "Success on deferred deep link",
        tappUrl = deferredLinkResponse.tappUrl,
        attrTappUrl = deferredLinkResponse.attrTappUrl,
        influencer = deferredLinkResponse.influencer,
        data = deferredLinkResponse.data,
        isFirstSession = true,
        deepLink = deferredLinkResponse.deeplink
    )

    Logger.logInfo("response native: $response")

    response.deepLink
        ?.takeIf { it.isNotEmpty() }
        ?.toUri()
        ?.let { tappUri ->

            tappService.handleImpression(tappUri) { result ->
                result.fold(
                    onSuccess = { tappUrlResponse ->
                        Logger.logInfo("handleImpression Native success: $tappUrlResponse")

                        val linkToken = url.linkToken(config.affiliate)

                        if (linkToken != null) {
                            Logger.logInfo("Extracted linkToken Native: $linkToken")
                        }

                        saveDeepLinkUrl(url.toString())
                        saveLinkToken(linkToken)
                        setProcessedReferralEngine()

                        dependencies.tappInstance?.deferredLinkDelegate?.didReceiveDeferredLink(response)
                    },
                    onFailure = { error ->
                        dependencies.tappInstance?.deferredLinkDelegate
                            ?.didFailResolvingUrl(
                                RequestModels.FailResolvingUrlResponse(
                                    error = error.message
                                        ?: "Couldn't resolve the deeplink Native $url",
                                    url = url.toString()
                                )
                            )

                        completion?.invoke(
                            Result.failure(
                                TappError.affiliateErrorResult(error, Affiliate.TAPP)
                            )
                        )
                    }
                )
            }
        }
}

internal fun TappEngine.fetchSecretsAndInitializeReferralEngineIfNeeded(
    completion: (Result<Unit>) -> Unit
) {
    val config = dependencies.keystoreUtils.getConfig()
    if (config == null) {
        completion(Result.failure(TappError.MissingConfiguration()))
        return
    }

    // If we already have an appToken and service is enabled, skip
    val hasSecrets = config.appToken != null;
    val service = dependencies.affiliateServiceFactory
        .getAffiliateService(config.affiliate, dependencies)
    val isEnabled = service?.isEnabled() == true

    if (hasSecrets && isEnabled) {
        completion(Result.success(Unit))
        return
    }

    // Otherwise fetch secrets
    secrets { secretResult ->
        secretResult.fold(
            onSuccess = {
                // then init
                initializeAffiliateService { initResult ->
                    completion(initResult)
                }
            },
            onFailure = { error ->
                completion(Result.failure(error))
            }
        )
    }
}

internal fun TappEngine.secrets(completion: (Result<Unit>) -> Unit) {
    val storedConfig = dependencies.keystoreUtils.getConfig()
    if (storedConfig == null) {
        completion(Result.failure(TappError.MissingConfiguration()))
        return
    }

    if (storedConfig.appToken != null) {
        completion(Result.success(Unit))
        return
    }

    val tappService =
        dependencies.affiliateServiceFactory.getAffiliateService(Affiliate.TAPP, dependencies)
    if (tappService is TappAffiliateService) {
        tappService.fetchSecrets() { result: Result<RequestModels.SecretsResponse> ->
            result.fold(
                onSuccess = { response ->
                    storedConfig.appToken = response.secret
                    dependencies.keystoreUtils.saveConfig(storedConfig)
                    completion(Result.success(Unit))
                },
                onFailure = { error ->
                    completion(Result.failure(error))
                }
            )
        }
    } else {
        completion(Result.failure(TappError.MissingAffiliateService("Affiliate service not available")))
    }
}


internal fun TappEngine.initializeAffiliateService(completion: VoidCompletion?) {
    val config = dependencies.keystoreUtils.getConfig()
    if (config == null) {
        completion?.invoke(Result.failure(TappError.MissingConfiguration()))
        return
    }

    val affiliateService = dependencies.affiliateServiceFactory.getAffiliateService(
        config.affiliate,
        dependencies
    )

    if (affiliateService == null) {
        completion?.invoke(Result.failure(TappError.MissingAffiliateService("Affiliate service not available")))
        return
    }

    if (affiliateService.isEnabled()) {
        Logger.logInfo("Affiliate service is already enabled. Skipping initialization. new")
        completion?.invoke(Result.success(Unit))
        return
    }

    val success = affiliateService.initialize()

    if (success) {
        affiliateService.setEnabled(true)
        completion?.invoke(Result.success(Unit))
    } else {
        affiliateService.setEnabled(false)
        completion?.invoke(
            Result.failure(
                TappError.InitializationFailed(
                    details = "Affiliate service initialization failed."
                )
            )
        )
    }
}

internal fun TappEngine.saveDeepLinkUrl(deepLinkUrl: String?) {
    if (deepLinkUrl.isNullOrBlank()) {
        Logger.logWarning("Cannot save deep link URL: URL is null or blank")
        return
    }

    Logger.logInfo("Saving deep link URL: $deepLinkUrl")
    val config = dependencies.keystoreUtils.getConfig()
    if (config != null) {
        val updatedConfig = config.copy(deepLinkUrl = deepLinkUrl)
        dependencies.keystoreUtils.saveConfig(updatedConfig)
        Logger.logInfo("Deep link URL saved: $deepLinkUrl")
    } else {
        Logger.logError("Failed to save deep link URL: configuration is null")
    }
}

internal fun TappEngine.saveLinkToken(linkToken: String?) {
    if (linkToken.isNullOrBlank()) {
        Logger.logWarning("Cannot save linkToken: linkToken is null or blank")
        return
    }

    Logger.logInfo("Saving linkToken: $linkToken")
    val config = dependencies.keystoreUtils.getConfig()
    if (config != null) {
        val updatedConfig = config.copy(linkToken = linkToken)
        dependencies.keystoreUtils.saveConfig(updatedConfig)
        Logger.logInfo("linkToken saved: $linkToken")
    } else {
        Logger.logError("Failed to save linkToken: configuration is null")
    }
}



internal fun TappEngine.setProcessedReferralEngine() {
    val config = dependencies.keystoreUtils.getConfig()
    if (config != null) {
        val updatedConfig = config.copy(hasProcessedReferralEngine = true)
        dependencies.keystoreUtils.saveConfig(updatedConfig)
        Logger.logInfo("Updated hasProcessedReferralEngine to true in config: $updatedConfig")
    } else {
        Logger.logWarning("Cannot set hasProcessedReferralEngine to true: config is null")
    }
}


internal fun TappEngine.hasProcessedReferralEngine(): Boolean {
    return dependencies.keystoreUtils.getConfig()?.hasProcessedReferralEngine
        ?: false // Directly access the property
}
