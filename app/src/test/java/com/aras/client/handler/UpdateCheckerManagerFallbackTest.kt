package com.aras.client.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerManagerFallbackTest {

    @Test
    fun tagParsedFromReleaseRedirect() {
        assertEquals(
            "v1.8.90",
            UpdateCheckerManager.parseTag("https://github.com/ArasTey/ArasClient/releases/tag/v1.8.90")
        )
    }

    @Test
    fun firstTagWinsInAtomFeed() {
        val feed = """
            <feed>
              <link rel="self" href="https://github.com/ArasTey/ArasClient/releases.atom"/>
              <entry>
                <link rel="alternate" href="https://github.com/ArasTey/ArasClient/releases/tag/v1.8.90"/>
                <title>ArasClient v1.8.90</title>
              </entry>
              <entry>
                <link rel="alternate" href="https://github.com/ArasTey/ArasClient/releases/tag/v1.8.76"/>
              </entry>
            </feed>
        """.trimIndent()
        assertEquals("v1.8.90", UpdateCheckerManager.parseTag(feed))
    }

    @Test
    fun noTagFromEmptyOrUnrelatedText() {
        assertNull(UpdateCheckerManager.parseTag(null))
        assertNull(UpdateCheckerManager.parseTag(""))
        assertNull(UpdateCheckerManager.parseTag("https://github.com/ArasTey/ArasClient/releases"))
    }

    @Test
    fun arm64DevicePrefersArm64ThenUniversal() {
        val names = UpdateCheckerManager.fallbackApkNames(
            "1.8.90",
            listOf("arm64-v8a", "armeabi-v7a", "armeabi")
        )
        assertEquals(
            listOf(
                "ArasClient_1.8.90_arm64-v8a.apk",
                "ArasClient_1.8.90_armeabi-v7a.apk",
                "ArasClient_1.8.90_universal.apk"
            ),
            names
        )
    }

    @Test
    fun x86EmulatorPrefersX86_64() {
        val names = UpdateCheckerManager.fallbackApkNames("1.8.90", listOf("x86_64", "x86"))
        assertEquals("ArasClient_1.8.90_x86_64.apk", names.first())
        assertEquals("ArasClient_1.8.90_universal.apk", names.last())
    }

    @Test
    fun unknownAbiFallsBackToUniversal() {
        val names = UpdateCheckerManager.fallbackApkNames("1.8.90", listOf("mips"))
        assertEquals(listOf("ArasClient_1.8.90_universal.apk"), names)
    }

    @Test
    fun versionComparisonDetectsNewerRelease() {
        assertTrue(UpdateCheckerManager.compareVersions("1.8.90", "1.8.76") > 0)
        assertTrue(UpdateCheckerManager.compareVersions("1.8.76", "1.8.76") == 0)
        assertTrue(UpdateCheckerManager.compareVersions("1.8.7", "1.8.76") < 0)
        assertTrue(UpdateCheckerManager.compareVersions("1.8.90", "1.8.90-P1") < 0)
    }
}
