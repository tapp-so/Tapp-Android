package com.example.tapp

import android.app.Application
import android.util.Log
import com.example.tapp.models.Affiliate
import com.example.tapp.models.Environment
import com.example.tapp.utils.TappConfiguration

class MainApplication : Application() {

    companion object {
        private const val TAG = "TappSample"

        lateinit var tapp: Tapp
        var sdkStarted: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()

        tapp = Tapp(this)

        val provider = when (BuildConfig.SAMPLE_PROVIDER) {
            "TAPP_NATIVE" -> Affiliate.TAPP_NATIVE
            "ADJUST" -> Affiliate.ADJUST
            else -> {
                Log.w(TAG, "SDK not started: unsupported sample provider configuration.")
                return
            }
        }

        val environment = when (BuildConfig.TAPP_SAMPLE_ENV.trim().uppercase()) {
            "SANDBOX" -> Environment.SANDBOX
            "PRODUCTION" -> Environment.PRODUCTION
            else -> {
                Log.w(TAG, "Unknown sample environment; falling back to SANDBOX.")
                Environment.SANDBOX
            }
        }

        val authToken = BuildConfig.TAPP_SAMPLE_AUTH_TOKEN.trim()
        val tappToken = BuildConfig.TAPP_SAMPLE_TAPP_TOKEN.trim()
        if (authToken.isBlank() || tappToken.isBlank()) {
            Log.w(
                TAG,
                "SDK not started: add TAPP_SAMPLE_AUTH_TOKEN and TAPP_SAMPLE_TAPP_TOKEN to local.properties."
            )
            return
        }

        val tappConfig = TappConfiguration(
            authToken = authToken,
            env = environment,
            tappToken = tappToken,
            affiliate = provider
        )

        tapp.start(tappConfig)
        sdkStarted = true
    }
}
