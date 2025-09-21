package com.example.tapp.services.affiliate

import android.net.Uri

interface AffiliateService {
    fun initialize():Boolean
    fun handleCallback(
        deepLink: Uri,
    )
    fun handleEvent(eventId:String)
    fun isEnabled():Boolean
    fun setEnabled(enabled: Boolean)

}
