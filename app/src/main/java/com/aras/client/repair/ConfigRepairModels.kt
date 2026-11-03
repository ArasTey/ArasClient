package com.aras.client.repair

data class RepairChange(
    val field: String,
    val before: String,
    val after: String,
    val note: String
)

data class RepairedConfig(
    val label: String,
    val uri: String,
    val changes: List<RepairChange>
)

data class ConfigRepairResult(
    val original: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val variants: List<RepairedConfig>,
    val warnings: List<String>
)
