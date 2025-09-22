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
            authToken = "22|564Dumifqbsqlwp7070h75iES1G6QtRZiN2jyuitced44ca0             ", // Replace with a real token for testing
            env = Environment.SANDBOX,
            tappToken = "vicQCMpNj9", // Replace with a real token for testing
            affiliate = Affiliate.ADJUST
        )

        // Initialize the Tapp SDK
        tapp = Tapp(this)
        tapp.start(tappConfig)

    }
}