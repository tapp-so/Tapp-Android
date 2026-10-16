package com.example.tapp.services.affiliate.tapp

import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.example.tapp.TappEngine
import com.example.tapp.dependencies.Dependencies
import com.example.tapp.models.Affiliate
import com.example.tapp.models.Environment
import com.example.tapp.services.affiliate.AffiliateService
import com.example.tapp.services.affiliate.AffiliateServiceFactory
import com.example.tapp.services.network.BackendApiException
import com.example.tapp.services.network.NetworkManager
import com.example.tapp.services.network.RequestModels
import com.example.tapp.services.network.TappError
import com.example.tapp.testing.FakeAndroidKeyStore
import com.example.tapp.testing.FakeTappBackend
import com.example.tapp.testing.FakeTappBackend.json
import com.example.tapp.testing.MainLooper
import com.example.tapp.testing.RecordingDelegate
import com.example.tapp.utils.InternalConfiguration
import com.example.tapp.utils.KeystoreUtils
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

/**
 * F-01: `/ref/deeplink` answers a repeat impression for the same device with
 * HTTP 200 {"error":true,"message":"Impression already incremented ..."}. That must stay a
 * non-fatal outcome all the way to the deferred-link callback, while real failures stay failures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImpressionHandlingTest {

    private lateinit var context: Context
    private lateinit var keystore: KeystoreUtils
    private lateinit var dependencies: Dependencies
    private lateinit var engine: TappEngine
    private lateinit var delegate: RecordingDelegate

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        FakeTappBackend.install()
        FakeTappBackend.reset()

        context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, ANDROID_ID)
        keystore = KeystoreUtils(context)
        dependencies = Dependencies(context, keystore, NetworkManager(), AffiliateServiceFactory)
        engine = TappEngine(dependencies)
        dependencies.tappInstance = engine
        delegate = RecordingDelegate()
        engine.deferredLinkDelegate = delegate

        // Stands in for Tapp-Adjust's AdjustAffiliateService, which needs the Adjust SDK.
        AffiliateServiceFactory.register(Affiliate.ADJUST) { FakeMmpService() }
    }

    @Test
    fun `tests run against Android's org json implementation`() {
        // Android returns "null" for an explicit JSON null; the reference org.json returns the fallback.
        assertEquals("null", JSONObject("""{"a":null}""").optString("a", "fallback"))
    }

    // ---- handleImpression result mapping -------------------------------------------------------

    @Test
    fun `200 counted impression succeeds`() {
        val result = impression(json(200, """{"error":false,"message":"Impression incremented successfully."}"""))

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().error)
        assertEquals("Impression incremented successfully.", result.getOrThrow().message)
    }

    @Test
    fun `200 already counted impression is a success carrying error true`() {
        val result = impression(json(200, ALREADY_COUNTED))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().error)
        assertEquals("Impression already incremented for this android device.", result.getOrThrow().message)
    }

    @Test
    fun `202 accepted impression succeeds`() {
        val result = impression(
            json(202, """{"error":false,"message":"Impression accepted without tracking because the install tracker limit has been reached."}""")
        )

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().error)
    }

    @Test
    fun `4xx impression stays a failure`() {
        val result = impression(json(404, """{"success":false,"message":"Deep link does not exist.","error_code":"NOT_FOUND"}"""))

        val error = result.exceptionOrNull() as BackendApiException
        assertEquals(404, error.statusCode)
        assertEquals("NOT_FOUND", error.errorCode)
    }

    @Test
    fun `429 impression stays a failure`() {
        val result = impression(json(429, """{"success":false,"message":"You have exceeded your install tracker limit.","error_code":"LIMIT_REACHED"}"""))

        assertEquals(429, (result.exceptionOrNull() as BackendApiException).statusCode)
    }

    @Test
    fun `200 success false impression stays a failure`() {
        val result = impression(json(200, """{"success":false,"message":"Denied","error_code":"DENIED"}"""))

        val error = result.exceptionOrNull() as BackendApiException
        assertEquals("DENIED", error.errorCode)
    }

    @Test
    fun `impression request payload is unchanged`() {
        impression(json(200, """{"error":false,"message":"ok"}"""))

        val request = FakeTappBackend.requests.single()
        assertEquals("POST", request.method)
        assertEquals("api.staging.tapp.so", request.host)
        assertEquals("deeplink", request.endpoint)
        assertEquals("Bearer $AUTH_TOKEN", request.headers["Authorization"])
        val body = JSONObject(request.body)
        assertEquals(setOf("tapp_token", "bundle_id", "android_id", "deeplink", "os"), body.keys().asSequence().toSet())
        assertEquals(TAPP_TOKEN, body.getString("tapp_token"))
        assertEquals(context.packageName, body.getString("bundle_id"))
        assertEquals(ANDROID_ID, body.getString("android_id"))
        assertEquals(ADJUST_LINK, body.getString("deeplink"))
        assertEquals("android", body.getString("os"))
    }

    // ---- Adjust deferred deep link entry point (AdjustAffiliateService -> appWillOpenInt) -------

    @Test
    fun `adjust deferred link with already counted impression is still delivered and processed`() {
        storeConfig(Affiliate.ADJUST)
        FakeTappBackend.enqueue("deeplink", json(200, ALREADY_COUNTED))
        FakeTappBackend.enqueue("linkData", json(200, LINK_DATA))

        val completion = appWillOpenInt(ADJUST_LINK)

        assertTrue(completion.isSuccess)
        assertTrue(delegate.failed.isEmpty())
        val delivered = delegate.received.single()
        assertFalse(delivered.error)
        assertEquals("https://brand.tapp.so/jane", delivered.tappUrl)
        assertEquals("jane", delivered.influencer)
        assertEquals(mapOf("campaign" to "spring"), delivered.data)
        assertEquals(true, delivered.isFirstSession)

        val config = keystore.getConfig()!!
        assertTrue(config.hasProcessedReferralEngine)
        assertEquals(ADJUST_LINK, config.deepLinkUrl)
        assertEquals("abc123", config.linkToken)

        assertEquals(1, FakeTappBackend.count("deeplink"))
        assertEquals(1, FakeTappBackend.count("linkData"))
        assertEquals("abc123", JSONObject(FakeTappBackend.bodiesOf("linkData").single()).getString("link_token"))
        assertEquals(0, FakeTappBackend.count("fingerprint"))
    }

    @Test
    fun `adjust deferred link with first impression is delivered and processed`() {
        storeConfig(Affiliate.ADJUST)
        FakeTappBackend.enqueue("deeplink", json(200, """{"error":false,"message":"Impression incremented successfully."}"""))
        FakeTappBackend.enqueue("linkData", json(200, LINK_DATA))

        val completion = appWillOpenInt(ADJUST_LINK)

        assertTrue(completion.isSuccess)
        assertEquals(1, delegate.received.size)
        assertTrue(delegate.failed.isEmpty())
        assertTrue(keystore.getConfig()!!.hasProcessedReferralEngine)
        assertEquals(ADJUST_LINK, keystore.getConfig()!!.deepLinkUrl)
    }

    @Test
    fun `adjust deferred link is processed once - later links do not count another impression`() {
        storeConfig(Affiliate.ADJUST)
        FakeTappBackend.enqueue("deeplink", json(200, ALREADY_COUNTED))
        FakeTappBackend.enqueue("linkData", json(200, LINK_DATA))
        appWillOpenInt(ADJUST_LINK)

        val second = appWillOpenInt("https://brand.tapp.so/other?adj_t=zzz999")

        assertTrue(second.isSuccess)
        assertEquals(1, FakeTappBackend.count("deeplink"))
        assertEquals(1, FakeTappBackend.count("linkData"))
        assertEquals(1, delegate.received.size)
    }

    @Test
    fun `adjust deferred link with rejected impression reports failure and is not processed`() {
        storeConfig(Affiliate.ADJUST)
        FakeTappBackend.enqueue("deeplink", json(404, """{"success":false,"message":"Deep link does not exist.","error_code":"NOT_FOUND"}"""))

        val completion = appWillOpenInt(ADJUST_LINK)

        val error = completion.exceptionOrNull() as TappError.AffiliateServiceError
        assertEquals(404, (error.underlyingError as BackendApiException).statusCode)
        assertTrue(delegate.received.isEmpty())
        assertEquals(1, delegate.failed.size)
        val config = keystore.getConfig()!!
        assertFalse(config.hasProcessedReferralEngine)
        assertNull(config.deepLinkUrl)
        assertEquals(0, FakeTappBackend.count("linkData"))
    }

    @Test
    fun `adjust deferred link with success false impression reports failure and is not processed`() {
        storeConfig(Affiliate.ADJUST)
        FakeTappBackend.enqueue("deeplink", json(200, """{"success":false,"message":"Denied","error_code":"DENIED"}"""))

        val completion = appWillOpenInt(ADJUST_LINK)

        assertTrue(completion.isFailure)
        assertTrue(delegate.received.isEmpty())
        assertEquals(1, delegate.failed.size)
        assertFalse(keystore.getConfig()!!.hasProcessedReferralEngine)
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun impression(reply: FakeTappBackend.Reply): Result<RequestModels.TappUrlResponse> {
        storeConfig(Affiliate.ADJUST)
        FakeTappBackend.enqueue("deeplink", reply)
        var result: Result<RequestModels.TappUrlResponse>? = null
        TappAffiliateService(dependencies).handleImpression(Uri.parse(ADJUST_LINK)) { result = it }
        MainLooper.awaitUntil("impression callback") { result != null }
        return result!!
    }

    private fun appWillOpenInt(url: String): Result<Unit> {
        var result: Result<Unit>? = null
        engine.appWillOpenInt(url) { result = it }
        MainLooper.awaitUntil("appWillOpenInt completion") { result != null }
        return result!!
    }

    private fun storeConfig(affiliate: Affiliate) {
        keystore.saveConfig(
            InternalConfiguration(
                authToken = AUTH_TOKEN,
                env = Environment.SANDBOX,
                tappToken = TAPP_TOKEN,
                affiliate = affiliate,
                bundleID = context.packageName,
                appToken = "adjust-app-token",
                androidId = ANDROID_ID
            )
        )
    }

    private class FakeMmpService : AffiliateService {
        override fun initialize() = true
        override fun handleCallback(deepLink: Uri) = Unit
        override fun handleEvent(eventId: String) = Unit
        override fun isEnabled() = false
        override fun setEnabled(enabled: Boolean) = Unit
    }

    private companion object {
        const val AUTH_TOKEN = "1|test-auth-token"
        const val TAPP_TOKEN = "tapp-test-token"
        const val ANDROID_ID = "a1b2c3d4e5f60789"
        const val ADJUST_LINK = "https://brand.tapp.so/jane?adj_t=abc123"
        const val ALREADY_COUNTED = """{"error":true,"message":"Impression already incremented for this android device."}"""
        const val LINK_DATA =
            """{"error":false,"tapp_url":"https://brand.tapp.so/jane","attr_tapp_url":"https://brand.tapp.so/jane?campaign=spring","influencer":"jane","data":{"campaign":"spring"}}"""
    }
}
