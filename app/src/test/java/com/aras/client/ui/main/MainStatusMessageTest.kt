package com.aras.client.ui.main

import com.aras.client.dto.ConnectionTestResult
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
    fun hiddenPingDetailsDoNotEraseResultOrRestoreConnectedFallback() {
        val result = ConnectionTestResult(42, country = "Test", ipAddress = "192.0.2.1")
        val state = MainUiState(isRunning = true, status = MainStatus.ConnectionTest(result), statusMessageVisible = true)
        val formatted = "Connection succeeded in 42 ms\n(Test) 192.0.2.1"
        assertEquals(MainBarText("Connection succeeded in 42 ms", "(Test) 192.0.2.1"), mainBarText(state, formatted, "Server"))
        val hidden = state.copy(statusMessageVisible = false)
        assertEquals(MainBarText("Connection succeeded in 42 ms", ""), mainBarText(hidden, formatted, "Server"))
        assertEquals(state.status, hidden.status)
        assertTrue(hidden.isRunning)
    }

    @Test
    fun failedPingAndTestProgressRemainReadableWithoutGuidance() {
        val failed = MainUiState(isRunning = true, status = MainStatus.ConnectionTest(ConnectionTestResult(-1)))
        assertEquals(MainBarText("Test failed", ""), mainBarText(failed, "Test failed", "Server"))
        val testing = failed.copy(isTesting = true, status = MainStatus.TestProgress("3"))
        assertEquals(MainBarText("Tests running: 3", ""), mainBarText(testing, "Tests running: 3", "Server"))
    }
}
