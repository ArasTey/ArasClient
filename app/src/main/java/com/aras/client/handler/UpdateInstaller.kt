package com.aras.client.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.aras.client.AppConfig
import com.aras.client.util.LogUtil
import java.io.File
import java.util.zip.ZipFile

object UpdateInstaller {

    private const val MIME_APK = "application/vnd.android.package-archive"

    fun canInstallPackages(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun installedVersion(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    /**
     * ABI token of the installed base APK, or "universal" when it bundles several.
     * Our releases are standalone APKs, so the base APK is also the patch source.
     */
    fun installedApkAbi(context: Context): String? = runCatching {
        val sourceApk = context.applicationInfo.sourceDir?.let(::File) ?: return null
        val abis = readApkAbis(sourceApk)
        when {
            abis.isEmpty() -> null
            abis.size == 1 -> abis.first()
            else -> "universal"
        }
    }.getOrNull()

    private fun readApkAbis(apk: File): Set<String> = runCatching {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") }
                .mapNotNull { it.split('/').getOrNull(1)?.takeIf { abi -> abi.isNotBlank() } }
                .toSet()
        }
    }.getOrDefault(emptySet())

    fun isUpdateApk(context: Context, file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?.packageName == context.packageName
        }.getOrDefault(false)
    }

    fun install(context: Context, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to launch update installer", e)
            false
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to open install permission settings", e)
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
