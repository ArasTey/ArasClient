package com.aras.client.dto.entities

data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    var countryCode: String? = null,
    var ipAddress: String? = null,
    var countryTestedAt: Long = 0L,
)
