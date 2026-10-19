package com.petertroll.quiethome.domain

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.json.JSONObject

data class UpcomingEvent(val title: String, val startsAt: Instant, val minutesUntil: Int)

object OutlookSupport {
    const val REDIRECT_SCHEME = "msauth"
    const val REDIRECT_HOST = "com.petertroll.quiethome"
    const val REDIRECT_PATH = "/0qyOYJyiO5ZLlV7HwxfLMQBxMuQ="
    const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST$REDIRECT_PATH"
    const val SCOPES = "offline_access Calendars.Read"

    fun authorizationUrl(clientId: String, redirectUri: String, state: String, challenge: String): String {
        val values = linkedMapOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "response_mode" to "query",
            "scope" to SCOPES,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "prompt" to "select_account",
        )
        return "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?" +
            values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    }

    fun parseNextEvent(raw: String, now: Instant): UpcomingEvent? {
        val values = JSONObject(raw).optJSONArray("value") ?: return null
        return buildList {
            for (index in 0 until values.length()) {
                val event = values.optJSONObject(index) ?: continue
                if (event.optBoolean("isCancelled", false)) continue
                val title = event.optString("subject").trim().ifEmpty { "Calendar event" }
                val dateTime = event.optJSONObject("start")?.optString("dateTime").orEmpty()
                val start = parseInstant(dateTime) ?: continue
                if (start < now) continue
                add(UpcomingEvent(title, start, Duration.between(now, start).toMinutes().toInt().coerceAtLeast(0)))
            }
        }.minByOrNull { it.startsAt }
    }

    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
