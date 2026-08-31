package com.aras.client.dto.entities

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    var updateInterval: Long = 1440, // in minutes, default to 24 hours
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,
    var requestHeaders: String? = null,

    /** Traffic/expiry metadata from the subscription-userinfo response header. */
    var trafficUsed: Long = -1,
    var trafficTotal: Long = -1,
    var expireAt: Long = -1,

    /** Optional announcement banner text (from sub headers or set manually). */
    var announcement: String? = null,

    /** Optional creator/owner message shown with the announcement. */
    var creatorMessage: String? = null,
)

