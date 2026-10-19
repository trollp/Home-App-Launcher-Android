package com.petertroll.quiethome.domain

import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject

data class WeatherSnapshot(
    val temperatureC: Double,
    val condition: String,
    val lowC: Double,
    val highC: Double,
    val rainStartsAt: Instant?,
    val createdAt: Instant,
    val fetchedAt: Instant,
) {
    fun headline(): String = "${temperatureC.round()}°C · $condition"
    fun detail(): String = "High ${highC.round()}°, low ${lowC.round()}°"
    private fun Double.round() = kotlin.math.round(this).toInt()
}

object SmhiForecastParser {
    private val stockholm = ZoneId.of("Europe/Stockholm")

    fun parse(raw: String, now: Instant, fetchedAt: Instant = now): WeatherSnapshot {
        val root = JSONObject(raw)
        val created = Instant.parse(root.getString("createdTime"))
        val series = root.getJSONArray("timeSeries")
        require(series.length() > 0) { "SMHI forecast is empty" }
        data class Point(val time: Instant, val temperature: Double, val symbol: Int, val rain: Double)
        val points = buildList {
            for (index in 0 until series.length()) {
                val item = series.optJSONObject(index) ?: continue
                val time = runCatching { Instant.parse(item.getString("time")) }.getOrNull() ?: continue
                val data = item.optJSONObject("data") ?: continue
                if (!data.has("air_temperature")) continue
                add(Point(time, data.optDouble("air_temperature"), data.optInt("symbol_code", 0), data.optDouble("precipitation_amount_mean", 0.0)))
            }
        }.filter { it.temperature.isFinite() }.sortedBy { it.time }
        require(points.isNotEmpty()) { "SMHI forecast has no usable temperatures" }
        val current = points.minWith(compareBy<Point> { kotlin.math.abs(it.time.epochSecond - now.epochSecond) }.thenBy { if (it.time >= now) 0 else 1 })
        val localDate = now.atZone(stockholm).toLocalDate()
        val today = points.filter { it.time.atZone(stockholm).toLocalDate() == localDate }.ifEmpty { listOf(current) }
        val rain = points.firstOrNull { it.time >= now && it.rain > 0.0 }?.time
        return WeatherSnapshot(
            current.temperature,
            condition(current.symbol),
            today.minOf { it.temperature },
            today.maxOf { it.temperature },
            rain,
            created,
            fetchedAt,
        )
    }

    private fun condition(code: Int): String = when (code) {
        1 -> "Clear"
        2 -> "Mostly clear"
        3, 4 -> "Partly cloudy"
        5, 6 -> "Cloudy"
        7 -> "Fog"
        in 8..10, in 18..20 -> "Rain"
        11, 21 -> "Thunder"
        in 12..17, in 22..27 -> "Snow or sleet"
        else -> "Forecast"
    }
}
