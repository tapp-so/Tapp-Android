package com.example.tapp

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tapp.models.Affiliate
import com.example.tapp.models.Environment
import com.example.tapp.services.affiliate.tapp.DeferredLinkDelegate
import com.example.tapp.services.network.RequestModels
import com.example.tapp.utils.KeystoreUtils
import com.example.tapp.utils.TappConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manual on-device checks of the Adjust adapter against the SANDBOX (staging) backend. Skipped
 * unless base64 instrumentation arguments tappAuthToken and tappToken are provided. Adjust runs in
 * its sandbox environment.
 */
@RunWith(AndroidJUnit4::class)
class StagingAdjustE2ETest {

    private val args = InstrumentationRegistry.getArguments()
    private val base: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context: Context
    private lateinit var tapp: Tapp
    private val delegate = RecordingDelegate()

    @Before
    fun setUp() {
        assumeTrue("staging credentials not provided", arg("tappAuthToken") != null && arg("tappToken") != null)
        context = AsPackage(base, "com.example.tapp")
        base.deleteSharedPreferences(PREFS)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tapp = Tapp(context)
            tapp.deferredLinkDelegate = delegate
            tapp.start(TappConfiguration(arg("tappAuthToken")!!, Environment.SANDBOX, arg("tappToken")!!, Affiliate.ADJUST))
        }
        val deadline = System.currentTimeMillis() + 30_000
        while (KeystoreUtils(context).getConfig()?.appToken == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertNotNull("SDK did not fetch secrets from staging", KeystoreUtils(context).getConfig()?.appToken)
    }

    @After
    fun tearDown() {
        if (::context.isInitialized) base.deleteSharedPreferences(PREFS)
    }

    /** /ref/deeplink 404 "Deep link does not exist." must surface as a failure, never as success. */
    @Test
    fun failedImpressionIsReportedAsFailure() {
        val latch = CountDownLatch(1)
        var completion: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tapp.appWillOpen(UNKNOWN_ADJUST_LINK) { completion = it; latch.countDown() }
        }
        assertTrue("no completion", latch.await(30, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertTrue(completion!!.isFailure)
        assertTrue(delegate.received.isEmpty())
        assertTrue(delegate.failed.size == 1)
        val config = KeystoreUtils(context).getConfig()!!
        assertFalse(config.hasProcessedReferralEngine)
        report(
            "adjust_failed_impression",
            "completion=failure(${completion!!.exceptionOrNull()?.javaClass?.simpleName}) failed_callbacks=1 processed=false"
        )
    }

    /** Real /ref/linkData through the Adjust adapter's public API. */
    @Test
    fun linkDataThroughAdjustAdapter() {
        val response = runBlocking { tapp.fetchLinkData(arg("adjustLinkDataUrl") ?: return@runBlocking null) }
        assumeTrue("adjustLinkDataUrl not provided", response != null)
        assertFalse("linkData error: ${response!!.message}", response.error)
        report(
            "adjust_link_data",
            "error=false tapp_url_present=${!response.tappUrl.isNullOrBlank()} influencer_present=${!response.influencer.isNullOrBlank()}"
        )
    }

    private fun arg(name: String): String? =
        args.getString(name)?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }

    private fun report(step: String, result: String) = Log.i("TappE2E", "$step: $result")

    private class AsPackage(base: Context, private val pkg: String) : ContextWrapper(base) {
        override fun getPackageName(): String = pkg
        override fun getApplicationContext(): Context = this
    }

    private class RecordingDelegate : DeferredLinkDelegate {
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

    private companion object {
        const val PREFS = "tapp_keystore_prefs_com.example.tapp"

        /** adj_t value that matches no creative link: backend answers 404 without writing. */
        const val UNKNOWN_ADJUST_LINK = "https://nta.staging.tapp.so/android-sdk-emulator-qa-20261013?adj_t=qaunknown1"
    }
}
