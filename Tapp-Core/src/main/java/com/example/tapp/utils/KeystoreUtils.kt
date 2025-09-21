package com.example.tapp.utils

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreUtils(context: Context) {

    private val keyAlias = "tapp_c"
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore")
    private val sharedPreferences = context.getSharedPreferences("keystore_prefs", Context.MODE_PRIVATE)

    private val json = Json { encodeDefaults = true }

    init {
        try {
            keyStore.load(null)
            if (!keyStore.containsAlias(keyAlias)) {
                createKey()
            }
        } catch (e: Exception) {
            Logger.logError("Keystore initialization failed: ${e.localizedMessage}")
            throw IllegalStateException("Keystore initialization failed.", e)
        }
    }

    private fun createKey() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
                )
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()

                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
            } else {
                throw UnsupportedOperationException("Key generation is not supported on this Android version.")
            }
        } catch (e: Exception) {
            Logger.logError("Failed to create key: ${e.localizedMessage}")
            throw IllegalStateException("Failed to create encryption key.", e)
        }
    }

    @Synchronized
    internal fun saveConfig(config: InternalConfiguration) {
        val jsonConfig = json.encodeToString(config)
        Logger.logInfo("Saving internal configuration: $jsonConfig")
        try {
            val encryptedConfig = encrypt(jsonConfig)
            val success = sharedPreferences.edit().putString("tapp_config", encryptedConfig).commit()
            if (!success) {
                Logger.logError("Failed to commit configuration to SharedPreferences.")
            } else {
                Logger.logInfo("Configuration successfully saved.")
            }
        } catch (e: Exception) {
            Logger.logError("Failed to save configuration: ${e.localizedMessage}")
            throw e
        }
    }

    @Synchronized
    fun getConfig(): InternalConfiguration? {
        val encryptedConfig = sharedPreferences.getString("tapp_config", null)
        if (encryptedConfig == null) {
            Logger.logInfo("No configuration found in SharedPreferences.")
            return null
        } else {
            Logger.logInfo("Encrypted configuration retrieved.")
        }

        return try {
            val decryptedConfig = decrypt(encryptedConfig)
            json.decodeFromString<InternalConfiguration>(decryptedConfig)
        } catch (e: Exception) {
            Logger.logError("Failed to decrypt configuration: ${e.localizedMessage}")
            null
        }
    }


    @Synchronized
    internal fun clearConfig() {
        val success = sharedPreferences.edit().remove("tapp_config").commit()
        if (success) {
            Logger.logInfo("Configuration cleared.")
        } else {
            Logger.logError("Failed to clear configuration.")
        }
    }

    internal fun hasConfig(): Boolean {
        val hasConfig = sharedPreferences.contains("tapp_config")
        Logger.logInfo("Configuration exists: $hasConfig")
        return hasConfig
    }

    private fun encrypt(value: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv

            val encryptedData = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encryptedBase64 = Base64.encodeToString(encryptedData, Base64.NO_WRAP)
            Logger.logInfo("Encrypted data: $encryptedBase64")

            "$ivBase64:$encryptedBase64"
        } catch (e: Exception) {
            Logger.logError("Encryption failed: ${e.localizedMessage}")
            throw IllegalStateException("Failed to encrypt value. Cause: ${e.localizedMessage}", e)
        }
    }

    private fun decrypt(encryptedValue: String): String {
        return try {
            val parts = encryptedValue.split(":")
            if (parts.size != 2) throw IllegalArgumentException("Invalid encrypted value format")

            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encryptedData = Base64.decode(parts[1], Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            val decryptedData = cipher.doFinal(encryptedData)
            val decryptedString = String(decryptedData, Charsets.UTF_8)
            Logger.logInfo("Decrypted data: $decryptedString")

            decryptedString
        } catch (e: Exception) {
            Logger.logError("Decryption failed: ${e.localizedMessage}")
            throw e
        }
    }


    private fun getSecretKey(): SecretKey {
        val secretKeyEntry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (secretKeyEntry == null) {
            Logger.logError("Secret key not found. Recreating key.")
            createKey()
            return (keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
        }
        return secretKeyEntry.secretKey
    }

}
