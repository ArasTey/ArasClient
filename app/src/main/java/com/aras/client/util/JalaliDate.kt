package com.aras.client.util

/**
 * Gregorian -> Jalali (Solar Hijri / Shamsi) conversion, ported from the
 * reference jalaali-js algorithm. Integer-only and dependency-free, so the
 * result is identical on every device and API level — unlike an ICU keyword
 * lookup that can silently fall back to Gregorian. The 2820-year break table
 * keeps the leap pattern exact across any year a subscription can carry.
 */
object JalaliDate {

    /** Persian month names; index 0 is Farvardin. */
    val monthNames = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )

    private val breaks = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635, 2060,
        2097, 2192, 2262, 2324, 2394, 2456, 3178,
    )

    // JS ~~(a/b) truncates toward zero, exactly like Kotlin's Int division;
    // mod mirrors jalaali-js's a - ~~(a/b)*b so the two stay in step.
    private fun div(a: Int, b: Int) = a / b
    private fun mod(a: Int, b: Int) = a - (a / b) * b

    /** Jalali [year, month (1..12), day (1..31)] for a Gregorian calendar date. */
    fun toJalali(gy: Int, gm: Int, gd: Int): IntArray {
        val jdn = g2d(gy, gm, gd)
        var jy = gy - 621
        val r = jalCal(jy)
        val jdn1f = g2d(gy, 3, r[2])
        var k = jdn - jdn1f
        if (k >= 0) {
            if (k <= 185) return intArrayOf(jy, 1 + div(k, 31), mod(k, 31) + 1)
            k -= 186
        } else {
            jy -= 1
            k += 179
            if (r[0] == 1) k += 1
        }
        return intArrayOf(jy, 7 + div(k, 30), mod(k, 30) + 1)
    }

    /**
     * Short Shamsi label for an expiry instant: day + Persian month name, with
     * the year only when it is not the current Jalali year. Int digits are
     * already Latin, so this honours the app-wide forced-Latin rule untouched.
     */
    fun formatShort(
        expiryMillis: Long,
        nowMillis: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String {
        val j = toJalaliInstant(expiryMillis, zone)
        val nowJ = toJalaliInstant(nowMillis, zone)
        val month = monthNames[j[1] - 1]
        return if (j[0] == nowJ[0]) "${j[2]} $month" else "${j[2]} $month ${j[0]}"
    }

    private fun toJalaliInstant(millis: Long, zone: java.time.ZoneId): IntArray {
        val g = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        return toJalali(g.year, g.monthValue, g.dayOfMonth)
    }

    /** jalCal's three values as [leap, gregorianYear, farvardinMarchDay]. */
    private fun jalCal(jy: Int): IntArray {
        val bl = breaks.size
        val gy = jy + 621
        var leapJ = -14
        var jp = breaks[0]
        var jump = -14
        for (i in 1 until bl) {
            val jm = breaks[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ = leapJ + div(jump, 33) * 8 + div(mod(jump, 33), 4)
            jp = jm
        }
        var n = jy - jp
        leapJ = leapJ + div(n, 33) * 8 + div(mod(n, 33) + 3, 4)
        if (mod(jump, 33) == 4 && jump - n == 4) leapJ += 1

        val leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150
        val march = 20 + leapJ - leapG

        if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33
        var leap = mod(mod(n + 1, 33) - 1, 4)
        if (leap == -1) leap = 4

        return intArrayOf(leap, gy, march)
    }

    /** Julian Day Number for a Gregorian date, on the jalaali-js epoch offset. */
    private fun g2d(gy: Int, gm: Int, gd: Int): Int {
        var d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4) +
            div(153 * mod(gm + 9, 12) + 2, 5) + gd - 34840408
        d = d - div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752
        return d
    }
}
