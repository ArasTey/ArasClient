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
    private val TAG_REGEX = Regex("/releases/tag/([^/?#\"\\s]+)")

    suspend fun checkForUpdate(
        includePreRelease: Boolean = false,
        installedVersion: String? = null,
        installedAbi: String? = null
    ): CheckUpdateResult = withContext(Dispatchers.IO) {
        val apiRelease = try {
            fetchApiRelease(includePreRelease)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "GitHub API update check failed", e)
            null
        }

        if (apiRelease != null) {
            buildResultFromRelease(apiRelease, installedVersion, installedAbi)
        } else {
            checkViaReleasePage(includePreRelease)
        }
    }

    private fun fetchApiRelease(includePreRelease: Boolean): GitHubRelease? {
        val url = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }
        val response = fetchText(url) ?: return null
        return if (includePreRelease) {
            JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)?.firstOrNull()
        } else {
            JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        }
    }

    private fun buildResultFromRelease(
        latestRelease: GitHubRelease,
        installedVersion: String?,
        installedAbi: String?
    ): CheckUpdateResult {
        val latestVersion = latestRelease.tagName.removePrefix("v")
        LogUtil.i(
            AppConfig.TAG,
            "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) <= 0) {
            return CheckUpdateResult(hasUpdate = false)
        }
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
        return CheckUpdateResult(
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
    }

    /**
     * The REST API allows only 60 unauthenticated requests per hour per IP, a budget
     * shared NATs exhaust in minutes. The releases page and its Atom feed carry no
     * such limit, so the latest tag is still resolved when the API is unavailable.
     */
    private fun checkViaReleasePage(includePreRelease: Boolean): CheckUpdateResult {
        val tag = if (includePreRelease) {
            fetchLatestTagFromAtom()
        } else {
            fetchLatestStableTag() ?: fetchLatestTagFromAtom()
        } ?: throw IllegalStateException("Failed to resolve the latest release")

        val latestVersion = tag.removePrefix("v")
        LogUtil.i(
            AppConfig.TAG,
            "Found new version on release page: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) <= 0) {
            return CheckUpdateResult(hasUpdate = false)
        }
        val apkName = fallbackApkNames(latestVersion).first()
        return CheckUpdateResult(
            hasUpdate = true,
            latestVersion = latestVersion,
            downloadUrl = "${AppConfig.APP_RELEASES_URL}/download/v$latestVersion/$apkName",
            downloadFileName = apkName
        )
    }

    private fun fetchLatestStableTag(): String? {
        val location = fetchRedirect("${AppConfig.APP_RELEASES_URL}/latest") ?: return null
        return parseTag(location)
    }

    private fun fetchLatestTagFromAtom(): String? {
        val feed = fetchText(AppConfig.APP_RELEASES_ATOM_URL, timeout = 8000) ?: return null
        return parseTag(feed)
    }

    internal fun parseTag(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return TAG_REGEX.find(text)?.groupValues?.get(1)
    }

    /**
     * Asset names follow `ArasClient_<version>_<abi>.apk`, ordered by the device's
     * ABI preference with the universal APK last.
     */
    internal fun fallbackApkNames(
        version: String,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()
    ): List<String> {
        val known = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val abis = supportedAbis.map { it.lowercase() }.filter { it in known }
        return abis.map { "ArasClient_${version}_$it.apk" } +
            "ArasClient_${version}_universal.apk"
    }

    private fun fetchText(url: String, timeout: Int = 5000): String? {
        val direct = HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = timeout))
        if (!direct.isNullOrEmpty()) return direct
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        return HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = timeout,
                httpPort = httpPort,
                proxyUsername = SettingsManager.getSocksUsername(),
                proxyPassword = SettingsManager.getSocksPassword()
            )
        )
    }

    private fun fetchRedirect(url: String): String? {
        val direct = HttpUtil.getRedirectLocation(UrlContentRequest(url = url, timeout = 5000))
        if (direct != null) return direct
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        return HttpUtil.getRedirectLocation(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = SettingsManager.getSocksUsername(),
                proxyPassword = SettingsManager.getSocksPassword()
            )
        )
    }

    private fun isFdroidBuild(): Boolean =
        BuildConfig.APPLICATION_ID.contains("fdroid", ignoreCase = true)

    internal fun compareVersions(version1: String, version2: String): Int {
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
