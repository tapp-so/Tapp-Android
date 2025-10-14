package com.example.tapp.services.network

import android.net.Uri
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.models.Environment

internal object TappEndpoint {
    private fun getBaseUrl(env: String): String {
        val environment = try {
            Environment.valueOf(env.uppercase())
        } catch (e: IllegalArgumentException) {
            throw TappError.MissingConfiguration("Invalid environment value: $env")
        }

        return when (environment) {
            Environment.PRODUCTION -> "https://api.tapp.so/v1/ref/"
            Environment.SANDBOX -> "https://api.staging.tapp.so/v1/ref/"
        }
    }

    // Endpoint for handling deeplink impressions
    fun deeplink(dependencies: Dependencies, deepLink: Uri): RequestModels.Endpoint {
        val config = dependencies.keystoreUtils.getConfig()
            ?: throw TappError.MissingConfiguration("Missing configuration in keystore")

        val url = "${getBaseUrl(config.env.environmentName())}deeplink"
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${config.authToken}"
        )
        val body = mapOf(
            "tapp_token" to config.tappToken,
            "bundle_id" to (config.bundleID?:false),
            "android_id" to (config.androidId?:false),
            "deeplink" to deepLink
        )

        return RequestModels.Endpoint(url, headers, body)
    }

    // Endpoint for fetching secrets
    fun secrets(dependencies: Dependencies): RequestModels.Endpoint {
        val config = dependencies.keystoreUtils.getConfig()
            ?: throw TappError.MissingConfiguration("Missing configuration in keystore")

        val url = "${getBaseUrl(config.env.environmentName())}secrets"
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${config.authToken}"
        )
        val body = mapOf(
            "tapp_token" to config.tappToken,
            "bundle_id" to (config.bundleID?:""),
            "mmp" to config.affiliate.toIntValue()
        )

        return RequestModels.Endpoint(url, headers, body)
    }


    fun generateAffiliateUrl(dependencies: Dependencies,request: RequestModels.GenerateAffiliateUrlRequest):  RequestModels.Endpoint  {
        val config = dependencies.keystoreUtils.getConfig()
            ?: throw TappError.MissingConfiguration("Configuration is missing")

        val url = "${getBaseUrl(config.env.environmentName())}influencer/add"

        val headers = mapOf(
            "Authorization" to "Bearer ${request.authToken}",
            "Content-Type" to "application/json"
        )

        val body = mapOf(
            "tapp_token" to request.tappToken,
            "bundle_id" to (config.bundleID?:""),
            "mmp" to request.mmp,
            "adgroup" to (request.adGroup ?: ""),
            "creative" to (request.creative ?: ""),
            "influencer" to request.influencer,
            "data" to (request.data ?: emptyMap())
        )

        return RequestModels.Endpoint(url, headers, body)
    }

    fun fetchLinkData(dependencies: Dependencies,request: RequestModels.TappLinkDataRequest):  RequestModels.Endpoint  {
        val config = dependencies.keystoreUtils.getConfig()
            ?: throw TappError.MissingConfiguration("Configuration is missing")

        val url = "${getBaseUrl(config.env.environmentName())}linkData"

        val headers = mapOf(
            "Authorization" to "Bearer ${config.authToken}",
            "Content-Type" to "application/json"
        )

        val body = mapOf(
            "tapp_token" to config.tappToken,
            "bundle_id" to (config.bundleID?:""),
            "link_token" to request.linkToken,
        )

        return RequestModels.Endpoint(url, headers, body)
    }

    fun tappEvent(
        dependencies: Dependencies,
        eventRequest: RequestModels.TappEvent
    ): RequestModels.Endpoint {
        val config = dependencies.keystoreUtils.getConfig()
            ?: throw TappError.MissingConfiguration("Configuration is missing")

        val eventNameString = if (eventRequest.eventName.isCustom) {
            (eventRequest.eventName as RequestModels.EventAction.custom).customValue
        } else {
            eventRequest.eventName.toString()
        }

        val url = "${getBaseUrl(config.env.environmentName())}event"
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${config.authToken}"
        )

        val body = mapOf(
            "tapp_token" to config.tappToken,
            "bundle_id" to (config.bundleID?:""),
            "event_name" to eventNameString,
            "event_url" to (config.deepLinkUrl?:""),
        ).filterValues { it != null }

        return RequestModels.Endpoint(url, headers, body)
    }


}
