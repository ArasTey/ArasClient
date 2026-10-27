package com.aras.client.handler

import com.aras.client.dto.entities.SubscriptionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Support-Url and Profile-Web-Page-Url response headers the panels send.
 * Plain-link cases only: the base64: branch depends on android.util.Base64,
 * which throws a stub under the JVM test so there is nothing deterministic to
 * assert for it.
 */
class SubscriptionMetadataTest {

    private fun sub() = SubscriptionItem(url = "https://panel.example.com", remarks = "p")

    @Test
    fun anyHttpOrHttpsLinkIsStored() {
        val sub = sub()
        AngConfigManager.applySubscriptionMetadata(
            sub,
            mapOf(
                "support-url" to "https://t.me/support",
                "profile-web-page-url" to "http://panel.example.com/info?id=7"
            )
        )
        assertEquals("https://t.me/support", sub.supportUrl)
        assertEquals("http://panel.example.com/info?id=7", sub.webPageUrl)
    }

    @Test
    fun theTelegramSchemeIsStored() {
        val sub = sub()
        AngConfigManager.applySubscriptionMetadata(
            sub,
            mapOf("support-url" to "tg://resolve?domain=support")
        )
        assertEquals("tg://resolve?domain=support", sub.supportUrl)
    }

    @Test
    fun aSchemeNoIntentWouldOpenIsDropped() {
        val sub = sub()
        AngConfigManager.applySubscriptionMetadata(
            sub,
            mapOf(
                "support-url" to "javascript://alert(1)",
                "profile-web-page-url" to "ftp://host/file"
            )
        )
        assertNull(sub.supportUrl)
        assertNull(sub.webPageUrl)
    }

    @Test
    fun aLinkWithoutAHostIsDroppedExceptTelegram() {
        val sub = sub()
        AngConfigManager.applySubscriptionMetadata(
            sub,
            mapOf(
                "support-url" to "https:/no-host",
                // "tg://" alone throws in java.net.URI (empty authority), so an opaque one.
                "profile-web-page-url" to "tg:payment"
            )
        )
        assertNull(sub.supportUrl)
        // The tg scheme needs no host.
        assertEquals("tg:payment", sub.webPageUrl)
    }

    @Test
    fun blankAndTooLongValuesAreDropped() {
        val sub = sub()
        AngConfigManager.applySubscriptionMetadata(
            sub,
            mapOf(
                "support-url" to "   ",
                "profile-web-page-url" to "https://${"x".repeat(2100)}.com"
            )
        )
        assertNull(sub.supportUrl)
        assertNull(sub.webPageUrl)
    }

    @Test
    fun absentHeadersLeaveEarlierValuesAlone() {
        val sub = sub().apply {
            supportUrl = "https://t.me/kept"
            webPageUrl = "https://panel.example.com/kept"
        }
        AngConfigManager.applySubscriptionMetadata(sub, mapOf("profile-title" to "t"))
        assertEquals("https://t.me/kept", sub.supportUrl)
        assertEquals("https://panel.example.com/kept", sub.webPageUrl)
    }
}
