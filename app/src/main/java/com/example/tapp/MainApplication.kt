package com.example.tapp

import android.app.Application
import com.example.tapp.models.Affiliate
import com.example.tapp.models.Environment
import com.example.tapp.utils.TappConfiguration

class MainApplication : Application() {

    companion object {
        lateinit var tapp: Tapp
    }

    override fun onCreate() {
        super.onCreate()

        // Create the Tapp SDK configuration
        val tappConfig = TappConfiguration(
            authToken = "16|UH7onZ80DAC6AuIHLNGGYy80kFAViP5TELjVbWJT11134e3c             ", // Replace with a real token for testing
            env = Environment.SANDBOX,
            tappToken = "A6zwAAy6EW", // Replace with a real token for testing
            affiliate = Affiliate.ADJUST
            // affiliate = Affiliate.TAPP
        )

        // Initialize the Tapp SDK
        tapp = Tapp(this)
        tapp.start(tappConfig)

    }
}