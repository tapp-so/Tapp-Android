package com.example.tapp.services.affiliate.native

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The test APK has no manifest of its own, so its permissions come only from the merged library
 * manifests (Tapp-Native + Tapp-Core). Runs without credentials.
 */
@RunWith(AndroidJUnit4::class)
class SdkPermissionsTest {

    @Test
    fun internetPermissionIsMergedFromTheSdk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()

        assertTrue("requested: $requested", Manifest.permission.INTERNET in requested)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.INTERNET))
    }
}
