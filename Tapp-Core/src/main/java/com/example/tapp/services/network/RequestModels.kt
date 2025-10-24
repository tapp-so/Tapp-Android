package com.example.tapp.services.network

import com.example.tapp.models.Affiliate
import com.example.tapp.models.Environment
import com.example.tapp.utils.InternalConfiguration
import com.example.tapp.utils.TappConfiguration
import java.net.URL

class RequestModels {

    data class ImpressionRequest(
        val tappToken: String,
        val bundleID: String,
        val deepLink: String
    )

    data class SecretsRequest(
        val tappToken: String,
        val bundleID: String,
        val mmp: Int
    )

    data class SecretsResponse(val secret: String)

    data class Endpoint(
        val url: String,
        val headers: Map<String, String>,
        val body: Map<String, Any>
    )

    data class GenerateAffiliateUrlRequest(
        val tappToken: String,
        val mmp: Int,
        val influencer: String,
        val adGroup: String?,
        val creative: String?,
        val data: Map<String, Any>?,
        val authToken: String
    )

    data class AffiliateUrlRequest(
        val influencer: String,
        val adGroup: String?,
        val creative: String?,
        val data: Map<String, String>? = null
    ) {
        fun toRequestBody(config: TappConfiguration): Map<String, Any> {
            return mapOf(
                "wre_token" to config.tappToken,
                "mmp" to config.affiliate.toIntValue(),
                "influencer" to influencer,
                "adgroup" to (adGroup ?: ""),
                "creative" to (creative ?: ""),
                "data" to (data ?: emptyMap())
            )
        }
    }
    data class AffiliateUrlResponse(
        val error: Boolean,
        val message: String,
        val influencer_url: String
    )

    data class TappEventRequest(
        val eventName: String,        // Name of the event
        val eventAction: Int,         // 1: Click, 2: Impression, 3: Count, -1: Custom
        val eventCustomAction: Any    // false or a String if eventAction is -1
    )
    data class TappEvent(
        val eventName: EventAction,
    )

    data class TappEventResponse(
        val error: Boolean,
        val message: String,
    )

    data class TappUrlResponse(
        val error: Boolean,
        val message: String,
    )

    sealed class EventAction(val rawValue: Int) {
        data object tapp_add_payment_info : EventAction(1)
        data object tapp_add_to_cart : EventAction(2)
        data object tapp_add_to_wishlist : EventAction(3)
        data object tapp_complete_registration : EventAction(4)
        data object tapp_contact : EventAction(5)
        data object tapp_customize_product : EventAction(6)
        data object tapp_donate : EventAction(7)
        data object tapp_find_location : EventAction(8)
        data object tapp_initiate_checkout : EventAction(9)
        data object tapp_generate_lead : EventAction(10)
        data object tapp_purchase : EventAction(11)
        data object tapp_schedule : EventAction(12)
        data object tapp_search : EventAction(13)
        data object tapp_start_trial : EventAction(14)
        data object tapp_submit_application : EventAction(15)
        data object tapp_subscribe : EventAction(16)
        data object tapp_view_content : EventAction(17)
        data object tapp_click_button : EventAction(18)
        data object tapp_download_file : EventAction(19)
        data object tapp_join_group : EventAction(20)
        data object tapp_achieve_level : EventAction(21)
        data object tapp_create_group : EventAction(22)
        data object tapp_create_role : EventAction(23)
        data object tapp_link_click : EventAction(24)
        data object tapp_link_impression : EventAction(25)
        data object tapp_apply_for_loan : EventAction(26)
        data object tapp_loan_approval : EventAction(27)
        data object tapp_loan_disbursal : EventAction(28)
        data object tapp_login : EventAction(29)
        data object tapp_rate : EventAction(30)
        data object tapp_spend_credits : EventAction(31)
        data object tapp_unlock_achievement : EventAction(32)
        data object tapp_add_shipping_info : EventAction(33)
        data object tapp_earn_virtual_currency : EventAction(34)
        data object tapp_start_level : EventAction(35)
        data object tapp_complete_level : EventAction(36)
        data object tapp_post_score : EventAction(37)
        data object tapp_select_content : EventAction(38)
        data object tapp_begin_tutorial : EventAction(39)
        data object tapp_complete_tutorial : EventAction(40)
        data class custom(val customValue: String) : EventAction(0) {
            override fun toString(): String = customValue
        }

        // Determine if the action is custom
        val isCustom: Boolean
            get() = this is custom

        // Check if the action is valid
        val isValid: Boolean
            get() = when (this) {
                is custom -> customValue.isNotEmpty()
                else -> true
            }

        // Return the custom action string or default "false" for non-custom actions
        val eventCustomAction: String
            get() = when (this) {
                is custom -> customValue.ifEmpty { "false" }
                else -> "false"
            }
    }


    data class TappLinkDataRequest(
        val linkToken: String,
    )

    data class TappLinkDataResponse(
        val error: Boolean,
        val message: String?,
        val tappUrl:String?,
        val attrTappUrl:String?,
        val influencer:String?,
        val data: Map<String, String>?,
        val isFirstSession: Boolean?
    )

    data class ExternalConfig(
        val authToken: String,
        val env: Environment,
        val tappToken: String,
        val affiliate: Affiliate,
        val bundleID: String,
        var appToken: String? = null,
        var deepLinkUrl: String? = null,
        var linkToken: String? = null
    )

    data class ConfigResponse(
        val error: Boolean,
        val message: String?,
        val config: ExternalConfig?,
    )

    data class FailResolvingUrlResponse(
        val error: String,
        val url: String
    )

    data class DeferredLinkRequest(
        val fp: Boolean,
        val advertisingId: String,
        val osName: String,
        val osVersion: String,
        val deviceModel: String,
        val deviceManufacturer: String,
        val screenResolution: String,
        val screenDensity: String,
        val locale: String,
        val timezone: String,
        // val deviceUptime: Long,
        // val totalRam: Long,
        // val totalStorage: Long,
        // val carrierName: String
        val installReferrer: String? = null,
        val clickId: String? = null,
        val androidId: String? = null,
        val batteryLevel: Int?,
        val totalRamBytes: Long? = null,
        val totalStorageBytes: Long? = null,
        val availStorageBytes: Long? = null,
        val deviceUptimeMs: Long? = null
    )

    data class DeferredLinkResponse(
        val deeplink: String?,
        val fingerprint: String?,
        val error: Boolean?
    )

    companion object {
        fun errorTappLinkDataResponse(message: String): TappLinkDataResponse {
            return TappLinkDataResponse(
                error = true,
                message = message,
                tappUrl = null,
                attrTappUrl = null,
                influencer = null,
                data = null,
                isFirstSession = false
            )
        }
    }


//    "tapp_url": "https://w2wv.nkmhub.com/1cef4f5c-05ec-4dde-ae77-6cddb996fb3e",
//    "attr_tapp_url": "https://w2wv.nkmhub.com/1cef4f5c-05ec-4dde-ae77-6cddb996fb3e?last_suggestion=47%20Ronin&",
//    "influencer": "1cef4f5c-05ec-4dde-ae77-6cddb996fb3e",
//    "data": {
//        "last_suggestion": "47 Ronin",
//        "test": null
//    }
}