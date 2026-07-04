package com.example.tapp.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerTest {
    @Test
    fun `redact removes sensitive key values`() {
        val messages = listOf(
            "Authorization: Bearer abc",
            "\"authToken\":\"abc\"",
            "\"tappToken\":\"abc\"",
            "\"tapp_token\":\"abc\"",
            "\"appToken\":\"abc\"",
            "\"secret\":\"abc\"",
            "\"androidId\":\"abc\"",
            "\"android_id\":\"abc\"",
            "\"advertising_id\":\"abc\"",
            "\"gaid\":\"abc\"",
            "\"linkToken\":\"abc\"",
            "\"link_token\":\"abc\"",
            "install_referrer=abc"
        )

        messages.forEach { message ->
            val redacted = Logger.redact(message)
            assertFalse("Sensitive value remained in: $redacted", redacted.contains("abc"))
            assertTrue("Redaction marker missing from: $redacted", redacted.contains("[REDACTED]"))
        }
    }

    @Test
    fun `redact removes attribution query tokens`() {
        listOf("t", "adj_t", "af_t", "tapp_t").forEach { parameter ->
            val redacted = Logger.redact("https://example.test/path?$parameter=abc&safe=value")

            assertFalse("Query token remained in: $redacted", redacted.contains("abc"))
            assertTrue(redacted.contains("$parameter=[REDACTED]"))
            assertTrue(redacted.contains("safe=value"))
        }
    }

    @Test
    fun `redact preserves safe Adjust event correlation logs`() {
        val message = "Adjust event submitted: event_id=epoccj, callback_id=callback-123"

        assertTrue(Logger.redact(message) == message)
    }

    @Test
    fun `redact preserves safe presence summaries`() {
        val message =
            "has_auth_token=true, has_tapp_token=true, has_app_token=false, " +
                    "has_android_id=true, has_advertising_id=false, " +
                    "has_link_token=false, has_install_referrer=true, has_click_id=true"

        assertTrue(Logger.redact(message) == message)
    }
}
