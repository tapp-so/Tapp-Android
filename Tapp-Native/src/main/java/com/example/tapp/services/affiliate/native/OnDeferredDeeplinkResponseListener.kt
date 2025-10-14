package com.example.tapp.services.affiliate.native

import android.net.Uri

fun interface OnDeferredDeeplinkResponseListener {
    fun onDeferredDeeplinkResponse(deeplink: Uri?): Boolean
}
