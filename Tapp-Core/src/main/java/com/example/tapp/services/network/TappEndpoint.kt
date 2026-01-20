package com.example.tapp.services.network

import android.net.Uri
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.models.Environment
import com.example.tapp.utils.Logger

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

        val eventNameString = when (val e = eventRequest.eventName) {
            is RequestModels.EventAction.custom -> e.customValue
            else -> e.toString()
        }

        val url = "${getBaseUrl(config.env.environmentName())}event"
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${config.authToken}"
        )

        val rawMetadata: Map<String, Any> = eventRequest.metadata ?: emptyMap()

        val metadata = sanitizeMetadata(rawMetadata) { msg ->
            Logger.logWarning(msg)
        }

        val body: Map<String, Any> = mapOf(
            "tapp_token" to config.tappToken,
            "bundle_id" to config.bundleID,
            "event_name" to eventNameString,
            "event_url" to (config.deepLinkUrl ?: ""),
            "linkToken" to (config.linkToken ?: ""),
            "os" to "android",
            "metadata" to metadata
        )

        return RequestModels.Endpoint(url, headers, body)
    }

    private fun sanitizeMetadata(
        metadata: Map<String, Any>,
        logger: (String) -> Unit
    ): Map<String, Any> {
        val cleaned = LinkedHashMap<String, Any>(metadata.size)

        metadata.forEach { (k, v) ->
            val ok =
                v is String ||
                        v is Boolean ||
                        v is Number

            // Extra JSON-safety: reject NaN/Infinity
            val finite = when (v) {
                is Double -> v.isFinite()
                is Float -> v.isFinite()
                else -> true
            }

            if (ok && finite) {
                cleaned[k] = v
            } else {
                logger("Dropping invalid metadata key='$k' type='${v::class.qualifiedName}' value='$v'")
            }
        }

        return cleaned
    }


    fun getDeferredLink(dependencies: Dependencies, request: RequestModels.DeferredLinkRequest): RequestModels.Endpoint {
        val config = dependencies.keystoreUtils.getConfig()
            ?: throw TappError.MissingConfiguration("Configuration is missing")

        val url = "${getBaseUrl(config.env.environmentName())}fingerprint"

        val headers = mapOf(
            "Authorization" to "Bearer ${config.authToken}",
            "Content-Type" to "application/json"
        )

        val body = mapOf(
            "tapp_token" to config.tappToken,
            "bundle_id" to config.bundleID,
            "advertising_id" to request.advertisingId,
            "fp" to request.fp,
            "platform" to request.platform,
            "os_version" to request.osVersion,
            "device_model" to request.deviceModel,
            "device_manufacturer" to request.deviceManufacturer,
            "screen_resolution" to request.screenResolution,
            "screen_density" to request.screenDensity,
            "locale" to request.locale,
            "timezone" to request.timezone,
            "referrer" to (request.installReferrer ?: "null"),
            "clickId" to (request.clickId ?: "null"),
            "device_id" to (request.androidId ?: "null"),
            "battery_level" to (request.batteryLevel ?: -1),
            "isCharging" to (request.isCharging ?: "null"),
            "total_ram_bytes" to (request.totalRamBytes ?: -1),
            "total_storage_bytes" to (request.totalStorageBytes ?: -1),
            "avail_storage_bytes" to (request.availStorageBytes ?: -1),
            "device_uptime_ms" to (request.deviceUptimeMs ?: -1)
        )

        return RequestModels.Endpoint(url, headers, body)
    }


}
