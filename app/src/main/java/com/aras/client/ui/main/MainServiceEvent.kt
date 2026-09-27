package com.aras.client.ui.main

import com.aras.client.dto.ConnectionTestResult
import com.aras.client.dto.QuickConnectResult

sealed class MainServiceEvent {
    data object StateRunning : MainServiceEvent()
    data object StateNotRunning : MainServiceEvent()
    data object StateStartSuccess : MainServiceEvent()
    // Carries the core's own startup error, which is far more actionable than a
    // generic "failed to start" string. Null when the sender had nothing to report.
    data class StateStartFailure(val message: String?) : MainServiceEvent()
    data object StateStopSuccess : MainServiceEvent()
    data class MeasureDelayResult(val result: ConnectionTestResult) : MainServiceEvent()
    data object MeasureConfigSuccess : MainServiceEvent()
    data class MeasureConfigNotify(val progress: String) : MainServiceEvent()
    data class MeasureConfigFinish(val finishedCount: String?) : MainServiceEvent()
    data class QuickConnectFinished(val result: QuickConnectResult) : MainServiceEvent()
    data object QuickConnectStarted : MainServiceEvent()
}
