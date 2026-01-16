package com.example.tapp.services.affiliate.native

import android.content.Context

class NativeConfig(
    val context: Context,
) {
    var onDeferredDeeplinkResponseListener: OnDeferredDeeplinkResponseListener? = null

}
