package com.example.tapp.services.affiliate.native

import com.example.tapp.services.network.NativeApiService.DeferredLinkFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-02/F-03 decision table for a failed `/ref/fingerprint` attempt. */
class FingerprintStepTest {

    @Test
    fun `decision table`() {
        val expected = mapOf(
            (DeferredLinkFailure.NO_MATCH to true) to FingerprintStep.MARK_NOT_ATTRIBUTED,
            (DeferredLinkFailure.NO_MATCH to false) to FingerprintStep.MARK_NOT_ATTRIBUTED,
            (DeferredLinkFailure.MISSING_FPID to true) to FingerprintStep.MARK_NOT_ATTRIBUTED,
            (DeferredLinkFailure.MISSING_FPID to false) to FingerprintStep.STOP,
            (DeferredLinkFailure.RETRYABLE to true) to FingerprintStep.RETRY,
            (DeferredLinkFailure.RETRYABLE to false) to FingerprintStep.RETRY,
            (DeferredLinkFailure.NON_RETRYABLE to true) to FingerprintStep.STOP,
            (DeferredLinkFailure.NON_RETRYABLE to false) to FingerprintStep.STOP
        )

        expected.forEach { (input, step) ->
            assertEquals("$input", step, nextFingerprintStep(input.first, referrerRetrieved = input.second))
        }
    }

    @Test
    fun `fpid detection is conservative`() {
        assertTrue(retrieved("utm_source=tapp&utm_medium=app&click_id=ab12cd34&fpid=9544b14f").mayContainFingerprintId())
        assertTrue(retrieved("fpid=").mayContainFingerprintId())
        assertTrue(retrieved("utm_source=tapp&FPID=x").mayContainFingerprintId())
        assertTrue(retrieved("utm_source=tapp&amp;fpid=x").mayContainFingerprintId())

        assertFalse(retrieved("utm_source=google-play&utm_medium=organic").mayContainFingerprintId())
        assertFalse(retrieved("").mayContainFingerprintId())
        assertFalse(retrieved(null).mayContainFingerprintId())
    }

    private fun retrieved(raw: String?) = InstallReferrerResult.Retrieved(raw, null)
}
