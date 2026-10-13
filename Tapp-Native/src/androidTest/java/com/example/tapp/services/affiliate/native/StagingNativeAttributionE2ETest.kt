package com.example.tapp.services.affiliate.native

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.example.tapp.Tapp
import com.example.tapp.models.Affiliate
import com.example.tapp.models.Environment
import com.example.tapp.services.affiliate.tapp.DeferredLinkDelegate
import com.example.tapp.services.network.RequestModels
import com.example.tapp.utils.KeystoreUtils
import com.example.tapp.utils.TappConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manual on-device checks against the SANDBOX (staging) backend. Skipped unless credentials are
 * passed as base64 instrumentation arguments (tappAuthToken, tappToken), so CI never calls staging.
 *
 * Everything is real (SDK, AndroidKeyStore, HTTPS, backend) except the Play Install Referrer value,
 * which a sideloaded app cannot receive; it is injected through TappNative.installReferrerSource.
 * The SDK sees the bundle id of the staging test app via [AsPackage].
 */
@RunWith(AndroidJUnit4::class)
class StagingNativeAttributionE2ETest {

    private val args = InstrumentationRegistry.getArguments()
    private val base: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context: Context
    private val delegate = LatchDelegate()

    @Before
    fun setUp() {
        assumeTrue("staging credentials not provided", arg("tappAuthToken") != null && arg("tappToken") != null)
        context = AsPackage(base, arg("bundleId") ?: SAMPLE_BUNDLE_ID)
        clearSdkState()
    }

    @After
    fun tearDown() {
        if (::context.isInitialized) clearSdkState()
        TappNative.resetForTesting()
    }

    @Test
    fun realInstallReferrerOfThisInstall() {
        val latch = CountDownLatch(1)
        var summary = "no response"
        val client = InstallReferrerClient.newBuilder(base).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(code: Int) {
                summary = if (code == InstallReferrerClient.InstallReferrerResponse.OK) {
                    val raw = client.installReferrer.installReferrer.orEmpty()
                    "OK param_names=${paramNames(raw)} contains_fpid=${raw.contains("fpid", true)}"
                } else {
                    "code=$code"
                }
                client.endConnection()
                latch.countDown()
            }

            override fun onInstallReferrerServiceDisconnected() = Unit
        })
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        report("real_install_referrer", summary)
    }

    /** Args: tappReferrer (Play referrer with fpid from a real landing-page visit), expectImpression. */
    @Test
    fun attributedInstall() {
        val referrer = requireNotNull(arg("tappReferrer")) { "tappReferrer argument required" }
        launch(InstallReferrerResult.Retrieved(referrer, Regex("click_id=([^&]*)").find(referrer)?.groupValues?.get(1)))

        assertTrue("no deferred link callback", delegate.await(30))
        assertTrue("didFailResolvingUrl: ${delegate.failed.map { it.error }}", delegate.failed.isEmpty())
        val delivered = delegate.received.single()
        assertFalse(delivered.error)
        assertTrue(delivered.deepLink.orEmpty().contains("t="))
        assertEquals(true, delivered.isFirstSession)

        val config = KeystoreUtils(context).getConfig()!!
        assertTrue(config.hasProcessedReferralEngine)
        val impression = if (config.deepLinkUrl != null) "counted" else "already_counted"
        report(
            "attributed_install",
            "delivered=true influencer_present=${!delivered.influencer.isNullOrBlank()} " +
                    "tapp_url_present=${!delivered.tappUrl.isNullOrBlank()} data_keys=${delivered.data?.keys} " +
                    "processed=true impression=$impression link_token_saved=${config.linkToken != null}"
        )
        arg("expectImpression")?.let { assertEquals(it, impression) }

        // Link data for the delivered link (real /ref/linkData through the public API).
        val linkData = runBlocking { tapp.fetchLinkData(delivered.deepLink!!) }!!
        assertFalse("linkData error: ${linkData.message}", linkData.error)
        report(
            "link_data",
            "error=false tapp_url_present=${!linkData.tappUrl.isNullOrBlank()} influencer_present=${!linkData.influencer.isNullOrBlank()} " +
                    "deepLink_field=${if (linkData.deepLink == "deepLink was not returned") "sentinel" else "value"} isFirstSession=${linkData.isFirstSession}"
        )
        if (impression == "counted") {
            val original = runBlocking { tapp.fetchOriginalLinkData() }!!
            assertFalse("originalLinkData error: ${original.message}", original.error)
            report("original_link_data", "error=false influencer_present=${!original.influencer.isNullOrBlank()}")
        }

        arg("eventName")?.let { name ->
            tapp.handleTappEvent(
                RequestModels.TappEvent(
                    RequestModels.EventAction.custom(name),
                    mapOf("qa_run" to "android-sdk-emulator-qa-20261013", "qa_step" to 1)
                )
            )
            Thread.sleep(5_000) // result is only logged by the SDK; checked from logcat
            report("event_submitted", "name=$name")
        }

        // Next cold start: attribution must not run again.
        launch(InstallReferrerResult.Retrieved(referrer, null))
        assertNull("fingerprinting ran again", TappNative.lastInitJob)
        assertEquals(1, delegate.received.size)
        report("relaunch_after_attribution", "init_job=null deferred_callbacks=${delegate.received.size}")
    }

    @Test
    fun organicReferrerIsTerminalWithoutRequest() {
        launch(InstallReferrerResult.Retrieved("utm_source=google-play&utm_medium=organic", null))
        assertTrue(processed())
        assertNoCallbacks()
        launch(InstallReferrerResult.Retrieved("utm_source=google-play&utm_medium=organic", null))
        assertNull(TappNative.lastInitJob)
        report("organic_referrer", "processed=true relaunch_init_job=null")
    }

    @Test
    fun backendNoMatchIsTerminal() {
        launch(InstallReferrerResult.Retrieved(NO_MATCH_REFERRER, "qa000000"))
        assertTrue("404 Data miss-match must be terminal", processed())
        assertNoCallbacks()
        launch(InstallReferrerResult.Retrieved(NO_MATCH_REFERRER, "qa000000"))
        assertNull(TappNative.lastInitJob)
        report("backend_no_match", "processed=true relaunch_init_job=null")
    }

    @Test
    fun retrievedReferrerWithoutFpidKeyIsTerminalAfterBackendCheck() {
        // Contains the text "fpid" but no fpid parameter: sent, backend answers 422 Fpid does not exist.
        launch(InstallReferrerResult.Retrieved("utm_source=tapp&utm_medium=app&utm_content=nofpid", null))
        assertTrue(processed())
        assertNoCallbacks()
        report("retrieved_missing_fpid_422", "processed=true")
    }

    @Test
    fun unavailableReferrerIsNotTerminal() {
        launch(InstallReferrerResult.Unavailable)
        assertFalse("unavailable referrer must not be terminal", processed())
        assertNoCallbacks()
        report("unavailable_referrer_422", "processed=false")
    }

    /** Run with bundleId=<a bundle id not registered in the tenant>. */
    @Test
    fun applicationNotFoundIsNotTerminal() {
        assumeTrue(context.packageName != SAMPLE_BUNDLE_ID)
        launch(InstallReferrerResult.Retrieved(NO_MATCH_REFERRER, "qa000000"))
        assertFalse("Application not found must not be terminal", processed())
        assertNoCallbacks()
        report("application_not_found_404", "processed=false")
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private lateinit var tapp: Tapp

    private fun launch(referrer: InstallReferrerResult) {
        TappNative.resetForTesting()
        TappNative.installReferrerSource = { referrer }
        val authToken = arg("tappAuthToken")!!
        val tappToken = arg("tappToken")!!
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tapp = Tapp(context)
            tapp.deferredLinkDelegate = delegate
            tapp.start(TappConfiguration(authToken, Environment.SANDBOX, tappToken, Affiliate.TAPP_NATIVE))
        }
        val deadline = System.currentTimeMillis() + 30_000
        while (TappNative.lastInitJob == null && !processed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (TappNative.lastInitJob == null) Thread.sleep(500) // processed: start() finishes on main
        TappNative.lastInitJob?.let { runBlocking { it.join() } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Guard against vacuous passes: the SDK must have reached the backend (secrets fetched).
        assertNotNull("SDK did not fetch secrets from staging", KeystoreUtils(context).getConfig()?.appToken)
    }

    private fun processed() = KeystoreUtils(context).getConfig()?.hasProcessedReferralEngine == true

    private fun assertNoCallbacks() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(delegate.received.isEmpty())
        assertTrue(delegate.failed.isEmpty())
    }

    private fun clearSdkState() {
        base.deleteSharedPreferences("tapp_keystore_prefs_${context.packageName}")
    }

    private fun arg(name: String): String? =
        args.getString(name)?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }

    private fun paramNames(raw: String) = raw.split("&").filter { it.isNotBlank() }.map { it.substringBefore("=") }

    private fun report(step: String, result: String) {
        Log.i("TappE2E", "$step: $result")
        assertNotNull(step)
    }

    private class AsPackage(base: Context, private val packageNameForSdk: String) : ContextWrapper(base) {
        override fun getPackageName(): String = packageNameForSdk
        override fun getApplicationContext(): Context = this
    }

    private class LatchDelegate : DeferredLinkDelegate {
        val received = CopyOnWriteArrayList<RequestModels.TappLinkDataResponse>()
        val failed = CopyOnWriteArrayList<RequestModels.FailResolvingUrlResponse>()
        private val latch = CountDownLatch(1)

        fun await(seconds: Long) = latch.await(seconds, TimeUnit.SECONDS)

        override fun didReceiveDeferredLink(linkDataResponse: RequestModels.TappLinkDataResponse) {
            received += linkDataResponse
            latch.countDown()
        }

        override fun didFailResolvingUrl(response: RequestModels.FailResolvingUrlResponse) {
            failed += response
            latch.countDown()
        }

        override fun testListener(test: String) = Unit
    }

    private companion object {
        const val SAMPLE_BUNDLE_ID = "com.example.tapp"

        /** Valid shape, unknown fpid and click_id: the backend answers 404 Data miss-match. */
        const val NO_MATCH_REFERRER =
            "utm_source=tapp&utm_medium=app&click_id=qa000000&fpid=00000000-0000-4000-8000-000000000000"
    }
}
