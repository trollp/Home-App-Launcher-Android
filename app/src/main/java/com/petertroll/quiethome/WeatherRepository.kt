package com.petertroll.quiethome

import android.content.Context
import com.petertroll.quiethome.domain.SmhiForecastParser
import com.petertroll.quiethome.domain.WeatherSnapshot
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONObject

class WeatherRepository(context: Context) {
    private val storage = context.getSharedPreferences("weather_cache", Context.MODE_PRIVATE)

    fun cached(now: Instant = Instant.now()): WeatherSnapshot? {
        val raw = storage.getString(KEY_RAW, null) ?: return null
        val fetched = storage.getLong(KEY_FETCHED, 0L).takeIf { it > 0 } ?: return null
        return runCatching { SmhiForecastParser.parse(raw, now, Instant.ofEpochMilli(fetched)) }.getOrNull()
    }

    fun isFresh(snapshot: WeatherSnapshot, now: Instant = Instant.now()): Boolean =
        now.toEpochMilli() - snapshot.fetchedAt.toEpochMilli() < CACHE_TTL_MS

    fun refresh(now: Instant = Instant.now()): WeatherSnapshot {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "QuietHome Android launcher")
        }
        try {
            require(connection.responseCode in 200..299) { "SMHI returned ${connection.responseCode}" }
            require(connection.contentType?.contains("application/json", true) == true) { "SMHI returned non-JSON data" }
            val raw = connection.inputStream.use { BoundedNetworkReader.readUtf8(it, 512_000) }
            val snapshot = SmhiForecastParser.parse(raw, now, now)
            storage.edit().putString(KEY_RAW, raw).putLong(KEY_FETCHED, now.toEpochMilli()).apply()
            return snapshot
        } finally {
            connection.disconnect()
        }
    }

    fun clear() = storage.edit().clear().apply()

    companion object {
        const val ENDPOINT = "https://opendata-download-metfcst.smhi.se/api/category/snow1g/version/1/geotype/point/lon/18.0686/lat/59.3293/data.json"
        const val CACHE_TTL_MS = 60 * 60 * 1000L
        private const val KEY_RAW = "raw"
        private const val KEY_FETCHED = "fetched"
    }
}
