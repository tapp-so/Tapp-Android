package com.example.tapp.testing

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * In-memory "AndroidKeyStore" KeyStore so the real KeystoreUtils runs on the JVM. The SDK's key
 * alias is pre-created, so KeystoreUtils never needs the AndroidKeyStore KeyGenerator; encryption
 * itself uses the platform's regular AES/GCM implementation.
 */
object FakeAndroidKeyStore {
    private const val PROVIDER_NAME = "AndroidKeyStore"
    private const val SDK_KEY_ALIAS = "tapp_c"

    internal val keys = ConcurrentHashMap<String, SecretKey>()

    fun install() {
        keys.computeIfAbsent(SDK_KEY_ALIAS) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        if (Security.getProvider(PROVIDER_NAME) !is FakeProvider) {
            Security.removeProvider(PROVIDER_NAME)
            Security.insertProviderAt(FakeProvider(), 1)
        }
    }

    private class FakeProvider : Provider(PROVIDER_NAME, 1.0, "In-memory AndroidKeyStore for JVM tests") {
        init {
            putService(object : Service(this, "KeyStore", PROVIDER_NAME, FakeKeyStoreSpi::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = FakeKeyStoreSpi()
            })
        }
    }

    private class FakeKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? =
            keys[alias]?.let { KeyStore.SecretKeyEntry(it) }

        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date()
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }

        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()

        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) =
            throw UnsupportedOperationException()

        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }
}
