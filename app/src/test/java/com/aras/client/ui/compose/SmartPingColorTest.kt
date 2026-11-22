package com.aras.client.ui.compose

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the smart ping colour bands the user specified (0-300 green, 300-600
 * pale green, 600-1000 yellow, 1000-1600 orange, 1600+ red, -1 dark red) by
 * checking each band's representative value and the inclusive boundary on each
 * side. A wrong threshold or a band that silently collides with a neighbour
 * fails here rather than on the card.
 */
class SmartPingColorTest {

    @Test
    fun darkBands() {
        val p = DarkGlassPalette
        assertEquals(p.pingGood, smartPingColor(150L, p))
        assertEquals(Color(0xFF9BE6B4), smartPingColor(450L, p))
        assertEquals(p.pingMid, smartPingColor(800L, p))
        assertEquals(Color(0xFFF59E5B), smartPingColor(1300L, p))
        assertEquals(p.pingBad, smartPingColor(2000L, p))
        assertEquals(Color(0xFFB23B36), smartPingColor(-1L, p))
    }

    @Test
    fun lightBands() {
        val p = LightGlassPalette
        assertEquals(p.pingGood, smartPingColor(150L, p))
        assertEquals(Color(0xFF3F9D63), smartPingColor(450L, p))
        assertEquals(p.pingMid, smartPingColor(800L, p))
        assertEquals(Color(0xFFC2681B), smartPingColor(1300L, p))
        assertEquals(p.pingBad, smartPingColor(2000L, p))
        assertEquals(Color(0xFF7A1410), smartPingColor(-1L, p))
    }

    @Test
    fun boundariesAreInclusiveOfTheLowerBand() {
        val p = DarkGlassPalette
        val good = smartPingColor(300L, p)
        val pale = smartPingColor(600L, p)
        val yellow = smartPingColor(1000L, p)
        val orange = smartPingColor(1600L, p)
        val red = smartPingColor(2000L, p)

        // The upper edge of each band stays in that band...
        assertEquals(good, smartPingColor(300L, p))
        assertEquals(pale, smartPingColor(600L, p))
        assertEquals(yellow, smartPingColor(1000L, p))
        assertEquals(orange, smartPingColor(1600L, p))
        // ...and one millisecond past it drops into the next, hotter band.
        assertNotEquals(good, smartPingColor(301L, p))
        assertNotEquals(pale, smartPingColor(601L, p))
        assertNotEquals(yellow, smartPingColor(1001L, p))
        assertNotEquals(orange, smartPingColor(1601L, p))
        // Every band is a distinct colour, so a card never reads two ways.
        val set = listOf(good, pale, yellow, orange, red, smartPingColor(-1L, p)).distinct()
        assertEquals(6, set.size)
    }
}
