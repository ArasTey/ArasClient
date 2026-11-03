package com.aras.client.handler

import android.os.Build
import com.aras.client.AppConfig
import com.aras.client.BuildConfig
import com.aras.client.dto.CheckUpdateResult
import com.aras.client.dto.GitHubRelease
import com.aras.client.dto.UrlContentRequest
import com.aras.client.extension.concatUrl
import com.aras.client.util.HttpUtil
import com.aras.client.util.JsonUtil
import com.aras.client.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {
    suspend fun checkForUpdate(
        includePreRelease: Boolean = false,
        installedVersion: String? = null,
        installedAbi: String? = null
    ): CheckUpdateResult = withContext(Dispatchers.IO) {
        val url = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        var response = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000
            )
        )
        if (response.isNullOrEmpty()) {
            val httpPort = SettingsManager.getHttpPort()
            response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000,
                    httpPort = httpPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                )
            )
                ?: throw IllegalStateException("Failed to get response")
        }

        val latestRelease = if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
                ?.firstOrNull()
                ?: throw IllegalStateException("No pre-release found")
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        }
        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val latestVersion = latestRelease.tagName.removePrefix("v")
        LogUtil.i(
            AppConfig.TAG,
            "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val asset = pickApkAsset(latestRelease)
                ?: throw IllegalStateException("No compatible APK found")
            val apkSha256 = asset.digest.toSha256()
            // A patch is only offered when the rebuilt APK can be verified against
            // the published digest, so a bad or foreign APK can never be installed.
            val patch = if (apkSha256 != null && !isFdroidBuild()) {
                pickPatchAsset(latestRelease, latestVersion, installedVersion, installedAbi)
                    ?.takeIf { it.digest.toSha256() != null }
            } else {
                null
            }
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = asset.browserDownloadUrl,
                downloadFileName = asset.name,
                downloadSha256 = apkSha256,
                patchUrl = patch?.browserDownloadUrl,
                patchFileName = patch?.name,
                patchSha256 = patch?.digest.toSha256(),
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    private fun isFdroidBuild(): Boolean =
        BuildConfig.APPLICATION_ID.contains("fdroid", ignoreCase = true)

    private fun compareVersions(version1: String, version2: String): Int {
        // Compare all numeric segments so suffixed tags like "2.3.2-P1" work:
        // "2.3.2-P1" -> [2,3,2,1], "2.3.2" -> [2,3,2], "2.3.2-P2" > "2.3.2-P1" > "2.3.2"
        val numberRegex = Regex("\\d+")
        val v1 = numberRegex.findAll(version1).map { it.value.toInt() }.toList()
        val v2 = numberRegex.findAll(version2).map { it.value.toInt() }.toList()

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = v1.getOrElse(i) { 0 }
            val num2 = v2.getOrElse(i) { 0 }
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    private fun pickApkAsset(release: GitHubRelease): GitHubRelease.Asset? {
        val fDroid = "fdroid"
        val isFdroidBuild = BuildConfig.APPLICATION_ID.contains(fDroid, ignoreCase = true)
        val apks = release.assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) &&
                it.name.contains(fDroid, ignoreCase = true) == isFdroidBuild
        }

        // Prefer the device's primary ABI, then any supported ABI, then a universal APK.
        for (abi in Build.SUPPORTED_ABIS) {
            apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
    }

    /**
     * Patch assets are named `ArasClient_<newVersion>_<abi>_from_<installedVersion>.patch`,
     * so the ABI and starting version have to match the installed app exactly.
     */
    internal fun pickPatchAsset(
        release: GitHubRelease,
        latestVersion: String?,
        installedVersion: String?,
        installedAbi: String?
    ): GitHubRelease.Asset? {
        if (latestVersion.isNullOrBlank() || installedVersion.isNullOrBlank() || installedAbi.isNullOrBlank()) {
            return null
        }
        val prefix = "arasclient_${latestVersion}_".lowercase()
        val suffix = "_from_${installedVersion}.patch".lowercase()
        return release.assets.firstOrNull { asset ->
            val name = asset.name.lowercase()
            name.startsWith(prefix) &&
                name.endsWith(suffix) &&
                name.length > prefix.length + suffix.length &&
                name.substring(prefix.length, name.length - suffix.length) == installedAbi.lowercase()
        }
    }

    private fun String?.toSha256(): String? =
        this?.removePrefix("sha256:")
            ?.trim()
            ?.lowercase()
            ?.takeIf { digest -> digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' } }
}
