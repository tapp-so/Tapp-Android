package com.example.tapp.services.affiliate.native

import android.net.Uri
import com.example.tapp.services.network.RequestModels

fun interface OnDeferredDeeplinkResponseListener {
    fun onDeferredDeeplinkResponse(deferredLinkResponse: RequestModels.DeferredLinkResponse?): Boolean
}
