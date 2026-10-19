package com.petertroll.quiethome.domain

object LocalBriefingComposer {
    fun compose(weather: String?, nextEvent: String?, routine: String?, coach: String?): List<String> =
        listOfNotNull(weather, nextEvent, routine, coach)
            .map { it.trim().take(100) }
            .filter { it.isNotEmpty() }
            .take(4)
}
