package com.aras.client.dto

data class CheckUpdateResult(
    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val downloadFileName: String? = null,
    val downloadSha256: String? = null,
    val patchUrl: String? = null,
    val patchFileName: String? = null,
    val patchSha256: String? = null,
    val error: String? = null,
    val isPreRelease: Boolean = false
)