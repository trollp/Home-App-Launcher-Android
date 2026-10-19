package com.petertroll.quiethome

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.petertroll.quiethome.domain.AiProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(context: Context) {
    private val storage = context.getSharedPreferences("private_credentials", Context.MODE_PRIVATE)

    fun saveApiKey(provider: AiProvider, value: String) {
        require(provider != AiProvider.NONE)
        saveSecret(apiKeyPrefix(provider), value)
    }

    fun apiKey(provider: AiProvider): String? =
        if (provider == AiProvider.NONE) null else readSecret(apiKeyPrefix(provider))
    fun hasApiKey(provider: AiProvider): Boolean =
        provider != AiProvider.NONE && storage.contains("${apiKeyPrefix(provider)}_data")
    fun clearApiKey(provider: AiProvider) {
        if (provider != AiProvider.NONE) clearSecret(apiKeyPrefix(provider))
    }

    private fun apiKeyPrefix(provider: AiProvider) = "${API_KEY_PREFIX}_${provider.name.lowercase()}"

    fun saveOutlookTokens(accessToken: String, refreshToken: String?, expiresAtEpochSeconds: Long) {
        saveSecret(OUTLOOK_ACCESS_PREFIX, accessToken)
        if (!refreshToken.isNullOrBlank()) saveSecret(OUTLOOK_REFRESH_PREFIX, refreshToken)
        storage.edit().putLong(KEY_OUTLOOK_EXPIRES, expiresAtEpochSeconds).apply()
    }

    fun outlookAccessToken(): String? = readSecret(OUTLOOK_ACCESS_PREFIX)
    fun outlookRefreshToken(): String? = readSecret(OUTLOOK_REFRESH_PREFIX)
    fun outlookExpiresAtEpochSeconds(): Long = storage.getLong(KEY_OUTLOOK_EXPIRES, 0L)
    fun hasOutlookConnection(): Boolean = outlookRefreshToken() != null
    fun clearOutlookTokens() {
        clearSecret(OUTLOOK_ACCESS_PREFIX)
        clearSecret(OUTLOOK_REFRESH_PREFIX)
        storage.edit().remove(KEY_OUTLOOK_EXPIRES).apply()
    }

    private fun saveSecret(prefix: String, value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) return clearSecret(prefix)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        storage.edit()
            .putString("${prefix}_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("${prefix}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun readSecret(prefix: String): String? = runCatching {
        val encrypted = Base64.decode(storage.getString("${prefix}_data", null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(storage.getString("${prefix}_iv", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    private fun clearSecret(prefix: String) {
        storage.edit().remove("${prefix}_data").remove("${prefix}_iv").apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val ALIAS = "quiet_home_byok_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val API_KEY_PREFIX = "api_key"
        const val OUTLOOK_ACCESS_PREFIX = "outlook_access"
        const val OUTLOOK_REFRESH_PREFIX = "outlook_refresh"
        const val KEY_OUTLOOK_EXPIRES = "outlook_expires"
    }
}
