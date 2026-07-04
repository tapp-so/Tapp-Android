package com.example.tapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.example.tapp.services.affiliate.tapp.DeferredLinkDelegate
import com.example.tapp.services.network.RequestModels
import com.example.tapp.services.network.RequestModels.EventAction
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), DeferredLinkDelegate {
//class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MainApplication.tapp.deferredLinkDelegate = this

        val environmentLabel = when (BuildConfig.TAPP_SAMPLE_ENV.trim().uppercase()) {
            "PRODUCTION" -> "Production"
            else -> "Sandbox"
        }
        val statusLabel = if (MainApplication.sdkStarted) {
            getString(R.string.sample_status_started)
        } else {
            getString(R.string.sample_status_missing_configuration)
        }
        findViewById<TextView>(R.id.helloTextView).text = getString(
            R.string.sample_configuration,
            BuildConfig.SAMPLE_PROVIDER_LABEL,
            environmentLabel,
            statusLabel
        )

        val eventButton: Button = findViewById(R.id.eventButton)
        eventButton.isEnabled = MainApplication.sdkStarted
        eventButton.setOnClickListener {
            val event = RequestModels.TappEvent(
                eventName = EventAction.tapp_begin_tutorial,
                metadata = mapOf(
                    "source" to "sample_app",
                    "button" to "track_event"
                )
            )
            MainApplication.tapp.handleTappEvent(event)
            Toast.makeText(this, "Event request submitted", Toast.LENGTH_SHORT).show()
        }

        val adjustEventButton: Button = findViewById(R.id.adjustEventButton)
        adjustEventButton.visibility = if (SampleProviderActions.isAdjustEventAvailable) {
            View.VISIBLE
        } else {
            View.GONE
        }
        adjustEventButton.isEnabled = MainApplication.sdkStarted
        adjustEventButton.setOnClickListener {
            val message = runCatching {
                if (SampleProviderActions.submitAdjustEvent(MainApplication.tapp)) {
                    R.string.adjust_event_submitted
                } else {
                    R.string.adjust_event_unavailable
                }
            }.getOrElse {
                R.string.adjust_event_submission_failed
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        val testEventButton: Button = findViewById(R.id.testEventButton)
        testEventButton.setOnClickListener {
            MainApplication.tapp.simulateTestEvent()
        }

        val tappEventConfig = RequestModels.TappEvent(
            eventName = EventAction.tapp_click_button,
            metadata = mapOf(
                "items" to listOf(1, 2, 3)
            )
        )
        val tappEvent: Button = findViewById(R.id.tappEvent)
        tappEvent.isEnabled = MainApplication.sdkStarted
        tappEvent.setOnClickListener {
            MainApplication.tapp.handleTappEvent(tappEventConfig)
        }

        val usernameEditText: EditText = findViewById(R.id.usernameEditText)
        val generateUrlButton: Button = findViewById(R.id.generateUrlButton)
        val urlTextView: TextView = findViewById(R.id.urlTextView)
        usernameEditText.isEnabled = MainApplication.sdkStarted
        generateUrlButton.isEnabled = MainApplication.sdkStarted

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
