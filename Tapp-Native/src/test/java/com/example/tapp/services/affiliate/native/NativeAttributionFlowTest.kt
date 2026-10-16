package com.example.tapp.services.affiliate.native

import android.content.Context
import android.provider.Settings
import com.example.tapp.Tapp
import com.example.tapp.models.Affiliate
import com.example.tapp.models.Environment
import com.example.tapp.testing.FakeAndroidKeyStore
import com.example.tapp.testing.FakeTappBackend
import com.example.tapp.testing.FakeTappBackend.json
import com.example.tapp.testing.MainLooper
import com.example.tapp.testing.RecordingDelegate
import com.example.tapp.utils.InternalConfiguration
import com.example.tapp.utils.KeystoreUtils
import com.example.tapp.utils.TappConfiguration
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CancellationException

/**
 * End-to-end (JVM) Native attribution: public Tapp API -> TappEngine -> NativeAffiliateService ->
 * TappNative -> NetworkManager, against recorded backend responses. Only the Play Install
 * Referrer read is substituted (see TappNative.installReferrerSource). A "launch" is a new Tapp
 * instance plus start(), with TappNative's per-process state reset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeAttributionFlowTest {

    private lateinit var context: Context
    private lateinit var delegate: RecordingDelegate

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        FakeTappBackend.install()
        FakeTappBackend.reset()
        context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, ANDROID_ID)
        delegate = RecordingDelegate()
        FakeTappBackend.enqueue("secrets", json(200, SECRETS))
    }

    // ---- attribution (F-01 on the Native path) -------------------------------------------------

    @Test
    fun `first install from a Tapp link is attributed and delivered once`() {
        FakeTappBackend.enqueue("fingerprint", json(200, FINGERPRINT_MATCH))
        FakeTappBackend.enqueue("deeplink", json(200, """{"error":false,"message":"Impression incremented successfully."}"""))

        launch(ATTRIBUTED_REFERRER)
        MainLooper.awaitUntil("deferred link callback") { delegate.received.isNotEmpty() || delegate.failed.isNotEmpty() }

        assertTrue(delegate.failed.isEmpty())
        val delivered = delegate.received.single()
        assertFalse(delivered.error)
        assertEquals(DEEPLINK, delivered.deepLink)
        assertEquals("https://brand.tapp.so/jane?t=tok123", delivered.tappUrl)
        assertEquals("jane", delivered.influencer)
        assertEquals(mapOf("campaign" to "spring"), delivered.data)
        assertEquals(true, delivered.isFirstSession)

        val config = storedConfig()
        assertTrue(config.hasProcessedReferralEngine)
        assertEquals(DEEPLINK, config.deepLinkUrl)
        assertEquals("tok123", config.linkToken)
        assertEquals(1, FakeTappBackend.count("secrets"))
        assertEquals(1, FakeTappBackend.count("fingerprint"))
        assertEquals(1, FakeTappBackend.count("deeplink"))

        launch(ATTRIBUTED_REFERRER)

        assertEquals(3, FakeTappBackend.requests.size)
        assertEquals(1, delegate.received.size)
    }

    @Test
    fun `reinstall with an already counted impression still delivers the deferred link`() {
        FakeTappBackend.enqueue("fingerprint", json(200, FINGERPRINT_MATCH))
        FakeTappBackend.enqueue("deeplink", json(200, ALREADY_COUNTED))

        launch(ATTRIBUTED_REFERRER)
        MainLooper.awaitUntil("deferred link callback") { delegate.received.isNotEmpty() || delegate.failed.isNotEmpty() }

        assertTrue(delegate.failed.isEmpty())
        val delivered = delegate.received.single()
        assertFalse(delivered.error)
        assertEquals(DEEPLINK, delivered.deepLink)
        // Same as 1.1.4: the repeat impression is not saved as the install's link, but processing ends.
        val config = storedConfig()
        assertTrue(config.hasProcessedReferralEngine)
        assertNull(config.deepLinkUrl)
        assertNull(config.linkToken)
        assertEquals(1, FakeTappBackend.count("deeplink"))

        launch(ATTRIBUTED_REFERRER)

        assertEquals(1, FakeTappBackend.count("fingerprint"))
        assertEquals(1, FakeTappBackend.count("deeplink"))
        assertEquals(1, delegate.received.size)
    }

    @Test
    fun `rejected impression still reports failure and is not marked processed`() {
        FakeTappBackend.enqueue("fingerprint", json(200, FINGERPRINT_MATCH))
        FakeTappBackend.enqueue("deeplink", json(404, """{"success":false,"message":"Deep link does not exist.","error_code":"NOT_FOUND"}"""))

        launch(ATTRIBUTED_REFERRER)
        MainLooper.awaitUntil("deferred link callback") { delegate.received.isNotEmpty() || delegate.failed.isNotEmpty() }

        assertTrue(delegate.received.isEmpty())
        assertEquals(DEEPLINK, delegate.failed.single().url)
        assertFalse(storedConfig().hasProcessedReferralEngine)
    }

    // ---- non-attributed installs (F-02) --------------------------------------------------------

    @Test
    fun `organic referrer without fpid sends no fingerprint request and is terminal`() {
        launch(InstallReferrerResult.Retrieved("utm_source=google-play&utm_medium=organic", null))

        assertEquals(0, FakeTappBackend.count("fingerprint"))
        assertTrue(storedConfig().hasProcessedReferralEngine)
        assertNoCallbacks()

        launch(InstallReferrerResult.Retrieved("utm_source=google-play&utm_medium=organic", null))

        assertEquals(0, FakeTappBackend.count("fingerprint"))
        assertNull(TappNative.lastInitJob)
    }

    @Test
    fun `empty or missing referrer value from a successful read is terminal without a request`() {
        launch(InstallReferrerResult.Retrieved("", null))
        assertEquals(0, FakeTappBackend.count("fingerprint"))
        assertTrue(storedConfig().hasProcessedReferralEngine)

        clearStoredState()
        launch(InstallReferrerResult.Retrieved(null, null))
        assertEquals(0, FakeTappBackend.count("fingerprint"))
        assertTrue(storedConfig().hasProcessedReferralEngine)
    }

    @Test
    fun `backend 404 data miss-match is terminal and not retried`() {
        FakeTappBackend.enqueue("fingerprint", json(404, NO_MATCH))

        launch(ATTRIBUTED_REFERRER)

        assertEquals(1, FakeTappBackend.count("fingerprint"))
        assertTrue(storedConfig().hasProcessedReferralEngine)
        assertNoCallbacks()

        launch(ATTRIBUTED_REFERRER)

        assertEquals(1, FakeTappBackend.count("fingerprint"))
    }

    @Test
    fun `backend 422 missing fpid for a retrieved referrer is terminal and not retried`() {
        FakeTappBackend.enqueue("fingerprint", json(422, MISSING_FPID))

        // Contains the text "fpid" (empty value), so the SDK leaves the decision to the backend.
        launch(InstallReferrerResult.Retrieved("utm_source=tapp&utm_medium=app&click_id=ab12cd34&fpid=", "ab12cd34"))

        assertEquals(1, FakeTappBackend.count("fingerprint"))
        assertTrue(storedConfig().hasProcessedReferralEngine)
    }

    @Test
    fun `unavailable referrer is not terminal even when the backend reports missing fpid`() {
        FakeTappBackend.enqueue("fingerprint", json(422, MISSING_FPID))

        launch(InstallReferrerResult.Unavailable)

        assertEquals(1, FakeTappBackend.count("fingerprint"))
        assertEquals("null", fingerprintBody().getString("referrer"))
        assertFalse(storedConfig().hasProcessedReferralEngine)

        launch(InstallReferrerResult.Unavailable)

        assertEquals(2, FakeTappBackend.count("fingerprint"))
    }

    // ---- failures that must neither mark nor retry (F-03) --------------------------------------

    @Test
    fun `application not found is not treated as a no match`() {
        assertStopsWithoutMarking(json(404, """{"success":false,"message":"Application not found.","error_code":"NOT_FOUND"}"""))
    }

    @Test
    fun `401 unauthenticated is not treated as organic`() {
        assertStopsWithoutMarking(json(401, """{"success":false,"message":"Unauthenticated.","error_code":"AUTH_UNAUTHENTICATED"}"""))
    }

    @Test
    fun `403 forbidden is not treated as organic`() {
        assertStopsWithoutMarking(json(403, """{"success":false,"message":"Forbidden.","error_code":"FORBIDDEN"}"""))
    }

    @Test
    fun `other 422 validation failures are not treated as organic`() {
        assertStopsWithoutMarking(json(422, """{"success":false,"message":"Validation failed","error_code":"VALIDATION_ERROR"}"""))
    }

    @Test
    fun `malformed 200 body is not treated as organic`() {
        assertStopsWithoutMarking(json(200, "false"))
    }

    // ---- transient failures (F-03) -------------------------------------------------------------

    @Test
    fun `429 is retried once and not marked`() {
        assertRetriedOnceWithoutMarking(json(429, """{"success":false,"message":"Too many requests.","error_code":"TOO_MANY_REQUESTS"}"""))
    }

    @Test
    fun `5xx is retried once, not marked, and tried again on the next launch`() {
        assertRetriedOnceWithoutMarking(json(503, "<html>Service Unavailable</html>"))

        launch(ATTRIBUTED_REFERRER)

        assertEquals(4, FakeTappBackend.count("fingerprint"))
    }

    @Test
    fun `network failure is retried once and not marked`() {
        assertRetriedOnceWithoutMarking(FakeTappBackend.Reply.Failure(IOException("connection reset")))
    }

    @Test
    fun `transient failure followed by a match is attributed`() {
        FakeTappBackend.enqueue(
            "fingerprint",
            json(500, """{"success":false,"message":"Server error.","error_code":"SERVER_ERROR"}"""),
            json(200, FINGERPRINT_MATCH)
        )
        FakeTappBackend.enqueue("deeplink", json(200, """{"error":false,"message":"Impression incremented successfully."}"""))

        launch(ATTRIBUTED_REFERRER)
        MainLooper.awaitUntil("deferred link callback") { delegate.received.isNotEmpty() || delegate.failed.isNotEmpty() }

        assertEquals(2, FakeTappBackend.count("fingerprint"))
        assertEquals(DEEPLINK, delegate.received.single().deepLink)
        assertTrue(storedConfig().hasProcessedReferralEngine)
    }

    @Test
    fun `cancellation is propagated, not retried and not marked`() {
        FakeTappBackend.enqueue("fingerprint", FakeTappBackend.Reply.Failure(CancellationException("test cancellation")))

        launch(ATTRIBUTED_REFERRER)

        assertEquals(1, FakeTappBackend.count("fingerprint"))
        assertTrue(TappNative.lastInitJob!!.isCancelled)
        assertFalse(storedConfig().hasProcessedReferralEngine)
        assertNoCallbacks()
    }

    // ---- request contract (must not regress like the abandoned 58e1da3 mapper) -----------------

    @Test
    fun `fingerprint request keeps every field the backend matches on`() {
        FakeTappBackend.enqueue("fingerprint", json(404, NO_MATCH))

        launch(ATTRIBUTED_REFERRER)

        val request = FakeTappBackend.requests.single { it.endpoint == "fingerprint" }
        assertEquals("POST", request.method)
        assertEquals("api.staging.tapp.so", request.host)
        assertEquals("Bearer $AUTH_TOKEN", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])

        val body = JSONObject(request.body)
        assertEquals(FINGERPRINT_KEYS_1_1_5, body.keys().asSequence().toSet())
        assertEquals("Android", body.getString("platform"))
        assertEquals(ATTRIBUTED_REFERRER.raw, body.getString("referrer"))
        assertTrue(body.getString("screen_density").toDouble() > 0.0)
        assertEquals(Locale.getDefault().toString(), body.getString("locale"))
        assertEquals(TimeZone.getDefault().id, body.getString("timezone"))
        assertEquals("ab12cd34", body.getString("clickId"))
        assertEquals(ANDROID_ID, body.getString("device_id"))
        assertEquals(TAPP_TOKEN, body.getString("tapp_token"))
        assertEquals(context.packageName, body.getString("bundle_id"))
        assertTrue(body.getBoolean("fp"))
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun launch(referrer: InstallReferrerResult) {
        TappNative.resetForTesting()
        TappNative.installReferrerSource = { referrer }
        val tapp = Tapp(context)
        tapp.deferredLinkDelegate = delegate
        tapp.start(TappConfiguration(AUTH_TOKEN, Environment.SANDBOX, TAPP_TOKEN, Affiliate.TAPP_NATIVE))
        MainLooper.awaitUntil("SDK start to settle") {
            TappNative.lastInitJob != null || KeystoreUtils(context).getConfig()?.hasProcessedReferralEngine == true
        }
        TappNative.lastInitJob?.let { job -> runBlocking { job.join() } }
        MainLooper.idle()
    }

    private fun assertStopsWithoutMarking(reply: FakeTappBackend.Reply) {
        FakeTappBackend.enqueue("fingerprint", reply)

        launch(ATTRIBUTED_REFERRER)

        assertEquals(1, FakeTappBackend.count("fingerprint"))
        assertFalse(storedConfig().hasProcessedReferralEngine)
        assertNoCallbacks()
    }

    private fun assertRetriedOnceWithoutMarking(reply: FakeTappBackend.Reply) {
        FakeTappBackend.enqueue("fingerprint", reply)

        launch(ATTRIBUTED_REFERRER)

        assertEquals(2, FakeTappBackend.count("fingerprint"))
        assertFalse(storedConfig().hasProcessedReferralEngine)
        assertNoCallbacks()
    }

    private fun assertNoCallbacks() {
        assertTrue(delegate.received.isEmpty())
        assertTrue(delegate.failed.isEmpty())
    }

    private fun fingerprintBody() = JSONObject(FakeTappBackend.bodiesOf("fingerprint").last())

    private fun storedConfig(): InternalConfiguration = KeystoreUtils(context).getConfig()!!

    private fun clearStoredState() {
        context.getSharedPreferences("tapp_keystore_prefs_${context.packageName}", Context.MODE_PRIVATE)
            .edit().clear().commit()
        FakeTappBackend.reset()
        FakeTappBackend.enqueue("secrets", json(200, SECRETS))
    }

    private companion object {
        const val AUTH_TOKEN = "1|test-auth-token"
        const val TAPP_TOKEN = "tapp-test-token"
        const val ANDROID_ID = "a1b2c3d4e5f60789"
        const val DEEPLINK = "https://brand.tapp.so/jane?t=tok123"

        val ATTRIBUTED_REFERRER = InstallReferrerResult.Retrieved(
            "utm_source=tapp&utm_medium=app&click_id=ab12cd34&fpid=9544b14f-a1eb-4d02-a0cd-50565c91a83d",
            "ab12cd34"
        )

        const val SECRETS =
            """{"error":false,"secret":"tapp-test-token","message":"Successfully fetched secrets","branded_url":"https://brand.tapp.so/fp/1"}"""
        const val FINGERPRINT_MATCH =
            """{"error":false,"tapp_url":"https://brand.tapp.so/jane?t=tok123","attr_tapp_url":"https://brand.tapp.so/jane?t=tok123&campaign=spring","influencer":"jane","data":{"campaign":"spring"},"deeplink":"https://brand.tapp.so/jane?t=tok123","fingerprint":"0cc175b9c0f1b6a831c399e269772661","method":"fpid"}"""
        const val ALREADY_COUNTED = """{"error":true,"message":"Impression already incremented for this android device."}"""
        const val NO_MATCH = """{"success":false,"message":"Data miss-match","error_code":"NOT_FOUND"}"""
        const val MISSING_FPID = """{"success":false,"message":"Fpid does not exist","error_code":"VALIDATION_ERROR"}"""

        /** Exactly the keys TappEndpoint.getDeferredLink sent in 1.1.5. */
        val FINGERPRINT_KEYS_1_1_5 = setOf(
            "tapp_token", "bundle_id", "advertising_id", "fp", "platform", "os_version", "device_model",
            "device_manufacturer", "screen_resolution", "screen_density", "locale", "timezone", "referrer",
            "clickId", "device_id", "battery_level", "isCharging", "total_ram_bytes", "total_storage_bytes",
            "avail_storage_bytes", "device_uptime_ms"
        )
    }
}
