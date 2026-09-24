package com.aras.client.dto

/** Result of importing a batch that may contain configs and subscription URLs. */
data class BatchImportResult(
    val configCount: Int = 0,
    val newSubscriptionIds: List<String> = emptyList(),
) {
    val subscriptionCount: Int get() = newSubscriptionIds.size
    val totalCount: Int get() = configCount + subscriptionCount
}
