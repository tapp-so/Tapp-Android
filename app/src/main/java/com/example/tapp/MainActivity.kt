package com.example.tapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.tapp.services.affiliate.tapp.DeferredLinkDelegate
import com.example.tapp.services.network.RequestModels
import com.example.tapp.services.network.RequestModels.EventAction
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class MainActivity : AppCompatActivity(), DeferredLinkDelegate {
//class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MainApplication.tapp.deferredLinkDelegate = this

//        val eventButton: Button = findViewById(R.id.eventButton)
//        eventButton.setOnClickListener {
//            val customEvent = RequestModels.TappEvent(
//                eventName = RequestModels.EventAction.tapp_begin_tutorial
//            )
//            MainApplication.tapp.handleTappEvent(customEvent)
//        }

        val testEventButton: Button = findViewById(R.id.testEventButton)
        testEventButton.setOnClickListener {
            MainApplication.tapp.simulateTestEvent()
        }

         val dummyButton: Button = findViewById(R.id.dummyButton)
         dummyButton.setOnClickListener {
             MainApplication.tapp.dummyMethod()
         }

        val tappEventConfig = RequestModels.TappEvent(
            eventName = EventAction.tapp_click_button,
            metadata = mapOf(
                "items" to listOf(1, 2, 3)
            )
        )
        val tappEvent: Button = findViewById(R.id.tappEvent)
            tappEvent.setOnClickListener {
                MainApplication.tapp.handleTappEvent(tappEventConfig)
            }

        val usernameEditText: EditText = findViewById(R.id.usernameEditText)
        val generateUrlButton: Button = findViewById(R.id.generateUrlButton)
        val urlTextView: TextView = findViewById(R.id.urlTextView)

        generateUrlButton.setOnClickListener {
            val username = usernameEditText.text.toString()
            if (username.isNotEmpty()) {
                lifecycleScope.launch {
                    val response = MainApplication.tapp.url(username, null, null, null)
                    urlTextView.text = response.influencer_url
                }
            }
        }
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
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Differed deep link received")
                .setMessage(response.toString())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun didFailResolvingUrl(response: RequestModels.FailResolvingUrlResponse) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Fail on the differed deep link listener")
                .setMessage(response.url)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
