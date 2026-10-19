package com.petertroll.quiethome.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SmhiForecastParserTest {
    private val fixture = """{
      "createdTime":"2026-10-19T10:14:34Z","referenceTime":"2026-10-19T10:00:00Z",
      "timeSeries":[
       {"time":"2026-10-19T11:00:00Z","data":{"air_temperature":9.2,"symbol_code":1,"precipitation_amount_mean":0.0,"probability_of_precipitation":0}},
       {"time":"2026-10-19T12:00:00Z","data":{"air_temperature":10.1,"symbol_code":18,"precipitation_amount_mean":0.4,"probability_of_precipitation":70}},
       {"time":"2026-10-19T13:00:00Z","data":{"air_temperature":8.8,"symbol_code":18,"precipitation_amount_mean":0.2,"probability_of_precipitation":60}}
      ]} """

    @Test fun `parses current Celsius condition high low and rain timing`() {
        val weather = SmhiForecastParser.parse(fixture, Instant.parse("2026-10-19T10:40:00Z"))
        assertEquals(9.2, weather.temperatureC, 0.01)
        assertEquals("Clear", weather.condition)
        assertEquals(8.8, weather.lowC, 0.01)
        assertEquals(10.1, weather.highC, 0.01)
        assertEquals(Instant.parse("2026-10-19T12:00:00Z"), weather.rainStartsAt)
    }

    @Test fun `invalid payload fails instead of inventing weather`() {
        assertTrue(runCatching { SmhiForecastParser.parse("{\"timeSeries\":[]}", Instant.EPOCH) }.isFailure)
    }
}
