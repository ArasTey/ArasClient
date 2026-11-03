package com.aras.client.dto

import com.aras.client.fmt.CustomFmt

/** Result of importing a batch that may contain configs and subscription URLs. */
data class BatchImportResult(
    val configCount: Int = 0,
    val newSubscriptionIds: List<String> = emptyList(),
    /** Highest core version the imported custom configs demand, when declared. */
    val minCoreVersion: String? = null,
) {
    val subscriptionCount: Int get() = newSubscriptionIds.size
    val totalCount: Int get() = configCount + subscriptionCount
}
