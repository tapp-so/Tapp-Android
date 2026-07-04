package com.example.tapp.services.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendResponseParserTest {
    @Test
    fun `non 2xx JSON is returned as backend failure`() {
        val result = BackendResponseParser.parseHttpResponse(
            statusCode = 422,
            responseBody = """{"success":false,"message":"Validation failed","error_code":"VALIDATION_ERROR"}"""
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as BackendApiException
        assertEquals(422, error.statusCode)
        assertEquals("Validation failed", error.backendMessage)
        assertEquals("VALIDATION_ERROR", error.errorCode)
    }

    @Test
    fun `non 2xx legacy success envelope is still a failure`() {
        val result = BackendResponseParser.parseHttpResponse(
            statusCode = 400,
            responseBody = """{"error":false,"message":"Bad request","error_code":"BAD_REQUEST"}"""
        )

        assertTrue(result.isFailure)
        assertEquals(400, (result.exceptionOrNull() as BackendApiException).statusCode)
    }

    @Test
    fun `modern success false on 2xx is returned as backend failure`() {
        val result = BackendResponseParser.parseHttpResponse(
            statusCode = 200,
            responseBody = """{"success":false,"message":"Denied","error_code":"DENIED"}"""
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as BackendApiException
        assertEquals(200, error.statusCode)
        assertEquals("Denied", error.backendMessage)
        assertEquals("DENIED", error.errorCode)
    }

    @Test
    fun `legacy error true is rejected by success validator`() {
        val result = BackendResponseParser.requireSuccess(
            JSONObject("""{"error":true,"message":"Already processed","error_code":"DUPLICATE"}""")
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as BackendApiException
        assertEquals(null, error.statusCode)
        assertEquals("Already processed", error.backendMessage)
        assertEquals("DUPLICATE", error.errorCode)
    }

    @Test
    fun `legacy error false remains successful`() {
        val result = BackendResponseParser.requireSuccess(
            JSONObject("""{"error":false,"message":"Ok"}""")
        )

        assertTrue(result.isSuccess)
    }
}
