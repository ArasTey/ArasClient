package com.aras.client.ui.main

import com.aras.client.dto.ConnectionTestResult
import com.aras.client.util.CountryResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainStatusMessageTest {
    @Test
    fun switchingServerClearsTwoColumnPingResultAndSubtitle() {
        val state = MainUiState(
            selectedGuid = "old-server",
            doubleColumnDisplay = true,
            isRunning = true,
            status = MainStatus.ConnectionTest(ConnectionTestResult(151, country = "NL", ipAddress = "192.0.2.1")),
            statusMessageVisible = true
        )
        val cleared = state.withoutServerStatusMessage().copy(selectedGuid = "new-server")
        assertEquals(MainStatus.Connected, cleared.status)
        assertEquals(MainBarText("New server", ""), mainBarText(cleared, "Connected. Tap to check.", "New server"))
        assertTrue(cleared.isRunning)
        assertTrue(cleared.doubleColumnDisplay)
        assertEquals(MainStatus.Disconnected, state.copy(isRunning = false).withoutServerStatusMessage().status)
    }

    @Test
    fun clearingServerMessagePreservesBatchTestProgress() {
        val state = MainUiState(isTesting = true, status = MainStatus.TestProgress("3"), statusMessageVisible = true)
        val cleared = state.withoutServerStatusMessage()
        assertEquals(state.status, cleared.status)
        assertTrue(cleared.isTesting)
        assertEquals(false, cleared.statusMessageVisible)
    }

    @Test
    fun connectedGuidanceAppearsOnlyInVisibleSubtitle() {
        val state = MainUiState(isRunning = true, status = MainStatus.Connected, statusMessageVisible = true)
        assertEquals(MainBarText("Server", "Connected. Tap to check."), mainBarText(state, "Connected.\nTap to check.", "Server"))
        assertEquals(MainBarText("Server", ""), mainBarText(state.copy(statusMessageVisible = false), "Connected.\nTap to check.", "Server"))
        assertTrue(state.isRunning)
    }

    @Test
    fun hiddenPingDetailsKeepTheConfigNameAndEgress() {
        val result = ConnectionTestResult(42, country = "Netherlands", ipAddress = "192.0.2.1")
        val state = MainUiState(
            isRunning = true,
            status = MainStatus.ConnectionTest(result),
            statusMessageVisible = true,
            egressCountry = "Netherlands",
            egressIp = "192.0.2.1"
        )
        val formatted = "Connection succeeded in 42 ms"
        assertEquals(
            MainBarText(formatted, "Netherlands", "192.0.2.1"),
            mainBarText(state, formatted, "Server", "Netherlands")
        )
        // The confirmation owns the title only while it is fresh; the country and
        // address stay under it instead of vanishing with the message.
        val hidden = state.copy(statusMessageVisible = false)
        assertEquals(
            MainBarText("Server", "Netherlands", "192.0.2.1"),
            mainBarText(hidden, formatted, "Server", "Netherlands")
        )
        assertEquals(state.status, hidden.status)
        assertTrue(hidden.isRunning)
    }

    @Test
    fun connectedBarKeepsTheEgressLineAfterTheConfirmationClears() {
        val state = MainUiState(
            isRunning = true,
            status = MainStatus.Connected,
            egressCountry = "NL",
            egressIp = "192.0.2.1"
        )
        assertEquals(
            MainBarText("Server", "Netherlands", "192.0.2.1"),
            mainBarText(state, "Connected", "Server", "Netherlands")
        )
        // Without a measured egress the old guidance timer still applies.
        assertEquals(
            MainBarText("Server", "Connected"),
            mainBarText(state.copy(egressCountry = null, egressIp = null, statusMessageVisible = true), "Connected", "Server")
        )
    }

    @Test
    fun pingCountryCodeIsResolvedToIso() {
        assertEquals("DE", CountryResolver.isoForCountry("DE"))
        assertEquals("NL", CountryResolver.isoForCountry("Netherlands"))
        assertEquals(null, CountryResolver.isoForCountry("Raw"))
    }

    @Test
    fun countryNamesAreLocalizedForTheBar() {
        assertEquals("Germany", CountryResolver.displayName("DE", java.util.Locale.ENGLISH))
        assertEquals("Netherlands", CountryResolver.displayName("Netherlands", java.util.Locale.ENGLISH))
        assertEquals("Raw", CountryResolver.displayName("Raw", java.util.Locale.ENGLISH))
        assertEquals(null, CountryResolver.displayName(" ", java.util.Locale.ENGLISH))
    }

    @Test
    fun longEgressAddressesKeepRoomForTheCountryName() {
        assertEquals("192.0.2.1", compactAddress("192.0.2.1"))
        assertEquals("2001:db8::1", compactAddress("2001:db8::1"))
        assertEquals(
            "2a02:4540:…:951e:1f12",
            compactAddress("2a02:4540:64:55f:81a0:1b2f:951e:1f12")
        )
    }

    @Test
    fun failedPingAndTestProgressRemainReadableWithoutGuidance() {
        val failed = MainUiState(isRunning = true, status = MainStatus.ConnectionTest(ConnectionTestResult(-1)))
        // The failure owns the line while it is fresh; once it clears the bar goes
        // back to naming the config instead of keeping a failure pinned under it.
        assertEquals(
            MainBarText("Test failed", ""),
            mainBarText(failed.copy(statusMessageVisible = true), "Test failed", "Server")
        )
        assertEquals(MainBarText("Server", ""), mainBarText(failed, "Test failed", "Server"))
        val testing = failed.copy(isTesting = true, status = MainStatus.TestProgress("3"))
        assertEquals(MainBarText("Tests running: 3", ""), mainBarText(testing, "Tests running: 3", "Server"))
    }
}
