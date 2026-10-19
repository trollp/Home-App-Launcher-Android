package com.petertroll.quiethome.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object HomeDateFormatter {
    private val englishDayMonth = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

    fun format(date: LocalDate): String = date.format(englishDayMonth)
}
