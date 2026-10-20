package com.petertroll.quiethome

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed OpenAI credential store. Legacy Microsoft/Groq material is removed on first access. */
class CredentialStore(context: Context) {
    private val storage = context.getSharedPreferences("private_credentials", Context.MODE_PRIVATE)
    init { migrateRemovedConnections() }

    fun saveOpenAiKey(value: String) = saveSecret(OPENAI, value)
    fun openAiKey(): String? = readSecret(OPENAI)
    fun hasOpenAiKey(): Boolean = storage.contains("${OPENAI}_data")
    fun clearOpenAiKey() = clearSecret(OPENAI)

    private fun migrateRemovedConnections() {
        if (storage.getBoolean(MIGRATED, false)) return
        val editor = storage.edit()
        listOf("outlook_access", "outlook_refresh", "api_key_groq").forEach { prefix -> editor.remove("${prefix}_data").remove("${prefix}_iv") }
        editor.remove("outlook_expires").putBoolean(MIGRATED, true).apply()
    }
    private fun saveSecret(prefix: String, value: String) {
        val clean = value.trim(); if (clean.isEmpty()) return clearSecret(prefix)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(clean.toByteArray())
        storage.edit().putString("${prefix}_data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).putString("${prefix}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }
    private fun readSecret(prefix: String): String? = runCatching {
        val data = Base64.decode(storage.getString("${prefix}_data", null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(storage.getString("${prefix}_iv", null) ?: return null, Base64.NO_WRAP)
        String(Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv)) }.doFinal(data))
    }.getOrNull()
    private fun clearSecret(prefix: String) { storage.edit().remove("${prefix}_data").remove("${prefix}_iv").apply() }
    private fun secretKey(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
    }
    private companion object { const val ALIAS = "quiet_home_byok_v1"; const val TRANSFORMATION = "AES/GCM/NoPadding"; const val OPENAI = "api_key_openai"; const val MIGRATED = "phase1_connections_migrated" }
}
