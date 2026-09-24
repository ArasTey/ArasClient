package com.aras.client.dto

import java.io.Serializable

data class QuickConnectMessage(
    val subscriptionId: String,
) : Serializable

data class QuickConnectResult(
    val subscriptionId: String,
    val selectedGuid: String? = null,
    val success: Boolean,
    val reason: QuickConnectFailure? = null,
) : Serializable

enum class QuickConnectFailure {
    NO_SUBSCRIPTION,
    ALREADY_RUNNING,
    STOP_FAILED,
    UPDATE_FAILED,
    NO_SERVERS,
    TEST_FAILED,
    VPN_PERMISSION_REQUIRED,
    START_FAILED,
}
