package com.example.tapp.services.affiliate.tapp

import com.example.tapp.services.network.RequestModels

interface DeferredLinkDelegate {
    fun didReceiveDeferredLink(linkDataResponse: RequestModels.TappLinkDataResponse)

    fun didFailResolvingUrl(response: RequestModels.FailResolvingUrlResponse)

    fun testListener(test: String);
}
