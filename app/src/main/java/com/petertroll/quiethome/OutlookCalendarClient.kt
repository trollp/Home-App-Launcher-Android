package com.petertroll.quiethome

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.petertroll.quiethome.domain.OutlookSupport
import com.petertroll.quiethome.domain.UpcomingEvent
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.json.JSONObject

class OutlookCalendarClient(private val context: Context) {
    private val pending = context.getSharedPreferences("outlook_oauth_pending", Context.MODE_PRIVATE)
    private val credentials = CredentialStore(context)

    fun authorizationIntent(clientId: String): Intent {
        require(clientId.isNotBlank()) { "Microsoft client ID is missing" }
        val verifier = randomUrlToken(64)
        val state = randomUrlToken(32)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        pending.edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .putString(KEY_CLIENT_ID, clientId)
            .putLong(KEY_CREATED_AT, Instant.now().epochSecond)
            .commit()
        val url = OutlookSupport.authorizationUrl(clientId, OutlookSupport.REDIRECT_URI, state, challenge)
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    fun completeAuthorization(uri: Uri, clientId: String) {
        val expectedState = pending.getString(KEY_STATE, null)
        val verifier = pending.getString(KEY_VERIFIER, null)
        val expectedClientId = pending.getString(KEY_CLIENT_ID, null)
        val createdAt = pending.getLong(KEY_CREATED_AT, 0L)
        val returnedState = uri.getQueryParameter("state")
        require(
            expectedState != null && verifier != null && returnedState == expectedState &&
                expectedClientId == clientId && Instant.now().epochSecond - createdAt in 0..OAUTH_TTL_SECONDS
        ) {
            "Microsoft sign-in state did not match"
        }
        pending.edit().clear().commit()
        uri.getQueryParameter("error")?.let { error ->
            val detail = uri.getQueryParameter("error_description")?.take(160)
            error("Microsoft sign-in failed: ${detail ?: error}")
        }
        val code = requireNotNull(uri.getQueryParameter("code")) { "Microsoft sign-in returned no code" }
        val response = tokenRequest(mapOf(
            "client_id" to clientId,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to OutlookSupport.REDIRECT_URI,
            "code_verifier" to verifier,
            "scope" to OutlookSupport.SCOPES,
        ))
        saveTokens(response)

    }

    fun nextEvent(clientId: String, now: Instant = Instant.now()): UpcomingEvent? {
        val token = validAccessToken(clientId, now)
        val end = now.plus(1, ChronoUnit.DAYS)
        val query = linkedMapOf(
            "startDateTime" to now.toString(),
            "endDateTime" to end.toString(),
            "\$select" to "subject,start,isCancelled",
            "\$orderby" to "start/dateTime",
            "\$top" to "10",
        ).entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val connection = (URL("https://graph.microsoft.com/v1.0/me/calendarView?$query").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Prefer", "outlook.timezone=\"UTC\"")
        }
        try {
            require(connection.responseCode in 200..299) { "Microsoft Graph returned ${connection.responseCode}" }
            require(connection.contentType?.contains("application/json", true) == true) { "Microsoft Graph returned non-JSON data" }
            val raw = connection.inputStream.use { BoundedNetworkReader.readUtf8(it, MAX_RESPONSE_CHARS) }
            return OutlookSupport.parseNextEvent(raw, now)
        } finally {
            connection.disconnect()
        }
    }

    private fun validAccessToken(clientId: String, now: Instant): String {
        val access = credentials.outlookAccessToken()
        if (access != null && credentials.outlookExpiresAtEpochSeconds() > now.epochSecond + 60) return access
        val refresh = requireNotNull(credentials.outlookRefreshToken()) { "Outlook is not connected" }
        val response = tokenRequest(mapOf(
            "client_id" to clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "redirect_uri" to OutlookSupport.REDIRECT_URI,
            "scope" to OutlookSupport.SCOPES,
        ))
        saveTokens(response)
        return requireNotNull(credentials.outlookAccessToken())
    }

    private fun tokenRequest(fields: Map<String, String>): JSONObject {
        val body = fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val connection = (URL(TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { BoundedNetworkReader.readUtf8(it, MAX_RESPONSE_CHARS) }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            require(connection.responseCode in 200..299) {
                json.optString("error_description").take(180).ifBlank { "Microsoft token endpoint returned ${connection.responseCode}" }
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun saveTokens(json: JSONObject) {
        val access = json.getString("access_token")
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
        val expires = Instant.now().epochSecond + json.optLong("expires_in", 3600) - 60
        credentials.saveOutlookTokens(access, refresh, expires)
    }

    private fun randomUrlToken(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes).let(::base64Url)
    private fun base64Url(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        const val KEY_VERIFIER = "verifier"
        const val KEY_STATE = "state"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CREATED_AT = "created_at"
        const val OAUTH_TTL_SECONDS = 600L
        const val MAX_RESPONSE_CHARS = 256_000
    }
}
