package com.example.tapp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every SDK call is HTTPS. Tapp-Core must declare INTERNET itself so it is merged into every
 * consuming app; Native-only apps get no other source for it (Adjust's comes from the Adjust SDK).
 */
class CoreManifestTest {

    @Test
    fun `production manifest declares INTERNET`() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("run from the Tapp-Core module directory: ${manifest.absolutePath}", manifest.isFile)

        val permissions = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
            .getElementsByTagName("uses-permission")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("android:name").nodeValue } }

        assertTrue("permissions: $permissions", "android.permission.INTERNET" in permissions)
    }
}
