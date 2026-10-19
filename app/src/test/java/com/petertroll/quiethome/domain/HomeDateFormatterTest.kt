package com.petertroll.quiethome.domain

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeDateFormatterTest {
    @Test fun `date line stays English and omits obvious year`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val formatted = HomeDateFormatter.format(LocalDate.of(2026, 10, 19))

            assertEquals("Monday, 19 October", formatted)
            assertFalse(formatted.contains("2026"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
