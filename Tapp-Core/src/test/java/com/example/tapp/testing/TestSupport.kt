package com.example.tapp.testing

import android.os.Looper
import com.example.tapp.services.affiliate.tapp.DeferredLinkDelegate
import com.example.tapp.services.network.RequestModels
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

object MainLooper {
    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs main-thread work posted by SDK coroutines until [condition] holds. */
    fun awaitUntil(description: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            idle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for: $description")
            Thread.sleep(5)
        }
    }
}

class RecordingDelegate : DeferredLinkDelegate {
    val received = CopyOnWriteArrayList<RequestModels.TappLinkDataResponse>()
    val failed = CopyOnWriteArrayList<RequestModels.FailResolvingUrlResponse>()

    override fun didReceiveDeferredLink(linkDataResponse: RequestModels.TappLinkDataResponse) {
        received += linkDataResponse
    }

    override fun didFailResolvingUrl(response: RequestModels.FailResolvingUrlResponse) {
        failed += response
    }

    override fun testListener(test: String) = Unit
}
