package com.example.tapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import com.example.tapp.services.affiliate.tapp.DeferredLinkDelegate
import com.example.tapp.services.network.RequestModels

class MainActivity : AppCompatActivity(), DeferredLinkDelegate {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MainApplication.tapp.deferredLinkDelegate = this

        val eventButton: Button = findViewById(R.id.eventButton)
        eventButton.setOnClickListener {
            val customEvent = RequestModels.TappEvent(
                eventName = RequestModels.EventAction.tapp_begin_tutorial
            )
            MainApplication.tapp.handleTappEvent(customEvent)
        }

        val testEventButton: Button = findViewById(R.id.testEventButton)
        testEventButton.setOnClickListener {
            MainApplication.tapp.simulateTestEvent()
        }

        // val dummyButton: Button = findViewById(R.id.dummyButton)
        // dummyButton.setOnClickListener {
        //     MainApplication.tapp.dummyMethod()
        // }
    }

    override fun testListener(test: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Test Listener Fired")
                .setMessage(test)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun didReceiveDeferredLink(response: RequestModels.TappLinkDataResponse) {
        // Handle deferred link
    }

    override fun didFailResolvingUrl(response: RequestModels.FailResolvingUrlResponse) {
        // Handle failed link resolution
    }
}
