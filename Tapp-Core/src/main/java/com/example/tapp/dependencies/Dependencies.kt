package com.example.tapp.dependencies

import android.content.Context
import com.example.tapp.TappEngine
import com.example.tapp.services.affiliate.AffiliateServiceFactory
import com.example.tapp.services.network.NetworkManager
import com.example.tapp.utils.KeystoreUtils

class Dependencies(
    val context: Context,
    val keystoreUtils: KeystoreUtils,
    val networkManager: NetworkManager,
    val affiliateServiceFactory: AffiliateServiceFactory,
    var tappInstance: TappEngine? = null,
)