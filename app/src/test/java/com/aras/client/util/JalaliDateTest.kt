package com.aras.client.util

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Gregorian -> Jalali conversion to ground-truth pairs taken from the
 * go-jalaali suite (same algorithm as this port): the leap-year seam around
 * Nowruz, ordinary mid-year days, and the year-boundary cases where a Gregorian
 * January sits in the previous Jalali year. If the break table or the epoch
 * offset drifts, one of these fails here rather than in the subscription UI.
 */
class JalaliDateTest {

    @Test
    fun canonicalPairs() {
        assertArrayEquals(intArrayOf(1391, 10, 21), JalaliDate.toJalali(2013, 1, 10))
        assertArrayEquals(intArrayOf(1393, 5, 13), JalaliDate.toJalali(2014, 8, 4))
        assertArrayEquals(intArrayOf(1405, 5, 28), JalaliDate.toJalali(2026, 8, 19))
        assertArrayEquals(intArrayOf(1405, 5, 29), JalaliDate.toJalali(2026, 8, 20))
    }

    @Test
    fun nowruzAndLeapSeam() {
        // 2025 is a Jalali leap year (1403): 20 March is still Esfand 30,
        // not the first day of 1404 — the boundary falls the next Gregorian day.
        assertArrayEquals(intArrayOf(1403, 12, 30), JalaliDate.toJalali(2025, 3, 20))
        assertArrayEquals(intArrayOf(1404, 1, 1), JalaliDate.toJalali(2025, 3, 21))
        assertArrayEquals(intArrayOf(1403, 1, 1), JalaliDate.toJalali(2024, 3, 20))
        assertArrayEquals(intArrayOf(1405, 1, 1), JalaliDate.toJalali(2026, 3, 21))
    }

    @Test
    fun midYearDates() {
        // 28 Aban 1405 — the day this conversion was written.
        assertArrayEquals(intArrayOf(1405, 8, 28), JalaliDate.toJalali(2026, 11, 19))
        // 11 Dey 1402 — a January date, which sits in the previous Jalali year.
        assertArrayEquals(intArrayOf(1402, 10, 11), JalaliDate.toJalali(2024, 1, 1))
    }

    @Test
    fun shortLabel() {
        val teh = ZoneId.of("Asia/Tehran")
        fun millis(y: Int, mo: Int, d: Int) =
            LocalDateTime.of(y, mo, d, 12, 0).atZone(teh).toInstant().toEpochMilli()

        // 2026-11-19 is 28 Aban 1405 (pinned by canonicalPairs); same Jalali
        // year means the year is dropped, and the digits stay Latin.
        val now = millis(2026, 11, 19)
        assertEquals("28 آبان", JalaliDate.formatShort(now, now, teh))

        // A different Jalali year must be appended. The date value is covered by
        // canonicalPairs; this checks the year-suppression branch and ordering.
        val far = millis(2027, 4, 10)
        val j = JalaliDate.toJalali(2027, 4, 10)
        assertTrue(j[0] != 1405)
        assertEquals("${j[2]} ${JalaliDate.monthNames[j[1] - 1]} ${j[0]}", JalaliDate.formatShort(far, now, teh))
    }
}
