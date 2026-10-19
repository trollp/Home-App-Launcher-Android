package com.petertroll.quiethome.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class OutlookSupportTest {
    @Test fun `authorization URL uses PKCE and least privilege calendar scope`() {
        val url = OutlookSupport.authorizationUrl(
            clientId = "client id",
            redirectUri = OutlookSupport.REDIRECT_URI,
            state = "state-value",
            challenge = "challenge-value",
        )
        assertTrue(url.startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?"))
        assertTrue(url.contains("client_id=client+id"))
        assertTrue(url.contains("code_challenge=challenge-value"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("Calendars.Read"))
        assertTrue(url.contains("offline_access"))
        assertTrue(url.contains("redirect_uri=msauth%3A%2F%2Fcom.petertroll.quiethome%2F0qyOYJyiO5ZLlV7HwxfLMQBxMuQ%3D"))
        assertFalse(url.contains("client_secret"))
    }

    @Test fun `next event parser chooses first future non-cancelled event`() {
        val raw = """{"value":[
          {"subject":"Cancelled","isCancelled":true,"start":{"dateTime":"2026-10-19T12:00:00Z","timeZone":"UTC"}},
          {"subject":"Planning","isCancelled":false,"start":{"dateTime":"2026-10-19T13:00:00Z","timeZone":"UTC"}},
          {"subject":"Later","isCancelled":false,"start":{"dateTime":"2026-10-19T16:00:00Z","timeZone":"UTC"}}
        ]}"""
        val event = OutlookSupport.parseNextEvent(raw, Instant.parse("2026-10-19T12:30:00Z"))
        assertNotNull(event)
        assertEquals("Planning", event!!.title)
        assertEquals(30, event.minutesUntil)
    }

    @Test fun `event parser returns null when there is no future event`() {
        assertNull(OutlookSupport.parseNextEvent("{\"value\":[]}", Instant.parse("2026-10-19T12:30:00Z")))
    }
}
