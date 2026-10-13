package com.example.tapp.services.network

import com.example.tapp.services.network.NativeApiService.DeferredLinkFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/** F-02/F-03: classification of `/ref/fingerprint` failures, using the backend's real error bodies. */
class DeferredLinkFailureClassificationTest {

    @Test
    fun `404 data miss-match is a definitive no match`() {
        assertEquals(
            DeferredLinkFailure.NO_MATCH,
            classifyHttp(404, """{"success":false,"message":"Data miss-match","error_code":"NOT_FOUND"}""")
        )
    }

    @Test
    fun `422 fpid does not exist is a missing fpid`() {
        assertEquals(
            DeferredLinkFailure.MISSING_FPID,
            classifyHttp(422, """{"success":false,"message":"Fpid does not exist","error_code":"VALIDATION_ERROR"}""")
        )
    }

    @Test
    fun `404 application not found is not a no match`() {
        assertEquals(
            DeferredLinkFailure.NON_RETRYABLE,
            classifyHttp(404, """{"success":false,"message":"Application not found.","error_code":"NOT_FOUND"}""")
        )
    }

    @Test
    fun `other 422 responses are not treated as organic`() {
        listOf(
            """{"success":false,"message":"Validation failed","error_code":"VALIDATION_ERROR"}""",
            """{"success":false,"message":"Validation failed.","error_code":"VALIDATION_ERROR","errors":{}}""",
            """{"success":false,"message":"Invalid referrer","error_code":"VALIDATION_ERROR"}""",
            """{"success":false,"message":"Invalid parameters","error_code":"VALIDATION_ERROR"}"""
        ).forEach { body ->
            assertEquals(body, DeferredLinkFailure.NON_RETRYABLE, classifyHttp(422, body))
        }
    }

    @Test
    fun `no match message with an unexpected status or code is not trusted`() {
        assertEquals(
            DeferredLinkFailure.NON_RETRYABLE,
            classifyHttp(400, """{"success":false,"message":"Data miss-match","error_code":"DATA_MISS_MATCH"}""")
        )
        assertEquals(
            DeferredLinkFailure.NON_RETRYABLE,
            classifyHttp(404, """{"success":false,"message":"Data miss-match","error_code":"SOMETHING_ELSE"}""")
        )
    }

    @Test
    fun `authentication and authorization failures are not retried or terminal`() {
        assertEquals(
            DeferredLinkFailure.NON_RETRYABLE,
            classifyHttp(401, """{"success":false,"message":"Unauthenticated.","error_code":"AUTH_UNAUTHENTICATED"}""")
        )
        assertEquals(
            DeferredLinkFailure.NON_RETRYABLE,
            classifyHttp(403, """{"success":false,"message":"Forbidden.","error_code":"FORBIDDEN"}""")
        )
    }

    @Test
    fun `429 and 5xx are retryable, including non JSON bodies`() {
        assertEquals(
            DeferredLinkFailure.RETRYABLE,
            classifyHttp(429, """{"success":false,"message":"Too many requests.","error_code":"TOO_MANY_REQUESTS"}""")
        )
        assertEquals(
            DeferredLinkFailure.RETRYABLE,
            classifyHttp(500, """{"success":false,"message":"Server error.","error_code":"SERVER_ERROR"}""")
        )
        assertEquals(DeferredLinkFailure.RETRYABLE, classifyHttp(502, "<html>Bad Gateway</html>"))
        assertEquals(DeferredLinkFailure.RETRYABLE, classifyHttp(503, ""))
    }

    @Test
    fun `network failures are retryable`() {
        assertEquals(DeferredLinkFailure.RETRYABLE, NativeApiService.classifyDeferredLinkFailure(IOException("reset")))
        assertEquals(DeferredLinkFailure.RETRYABLE, NativeApiService.classifyDeferredLinkFailure(SocketTimeoutException()))
        assertEquals(DeferredLinkFailure.RETRYABLE, NativeApiService.classifyDeferredLinkFailure(UnknownHostException()))
    }

    @Test
    fun `malformed 2xx bodies and other errors are not retried`() {
        val malformed = BackendResponseParser.parseHttpResponse(200, "false").exceptionOrNull()!!
        assertEquals(DeferredLinkFailure.NON_RETRYABLE, NativeApiService.classifyDeferredLinkFailure(malformed))
        assertEquals(DeferredLinkFailure.NON_RETRYABLE, NativeApiService.classifyDeferredLinkFailure(SecurityException()))
        // Tapp-Native rethrows cancellation before classifying; it must never be classified retryable.
        assertEquals(DeferredLinkFailure.NON_RETRYABLE, NativeApiService.classifyDeferredLinkFailure(CancellationException()))
    }

    private fun classifyHttp(status: Int, body: String): DeferredLinkFailure {
        val error = BackendResponseParser.parseHttpResponse(status, body).exceptionOrNull()
            ?: throw AssertionError("HTTP $status should be a failure")
        return NativeApiService.classifyDeferredLinkFailure(error)
    }
}
