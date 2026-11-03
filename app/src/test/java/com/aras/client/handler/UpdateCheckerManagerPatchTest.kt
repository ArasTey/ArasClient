package com.aras.client.handler

import com.aras.client.dto.GitHubRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerManagerPatchTest {

    private fun asset(name: String) = GitHubRelease.Asset(
        name = name,
        browserDownloadUrl = "https://example.invalid/$name",
        digest = "sha256:" + "a".repeat(64)
    )

    private val release = GitHubRelease(
        tagName = "v1.8.76",
        body = "",
        assets = listOf(
            asset("ArasClient_1.8.76_arm64-v8a.apk"),
            asset("ArasClient_1.8.76_x86_64.apk"),
            asset("ArasClient_1.8.76_arm64-v8a_from_1.8.75.patch"),
            asset("ArasClient_1.8.76_x86_64_from_1.8.75.patch"),
            asset("ArasClient_1.8.76_arm64-v8a_from_1.8.71.patch")
        )
    )

    @Test
    fun picksPatchForInstalledAbiAndVersion() {
        val patch = UpdateCheckerManager.pickPatchAsset(release, "1.8.76", "1.8.75", "arm64-v8a")
        assertEquals("ArasClient_1.8.76_arm64-v8a_from_1.8.75.patch", patch?.name)
    }

    @Test
    fun x86DoesNotMatchX86_64() {
        assertNull(UpdateCheckerManager.pickPatchAsset(release, "1.8.76", "1.8.75", "x86"))
    }

    @Test
    fun unknownInstalledVersionHasNoPatch() {
        assertNull(UpdateCheckerManager.pickPatchAsset(release, "1.8.76", "1.8.60", "arm64-v8a"))
    }

    @Test
    fun missingInstalledInfoHasNoPatch() {
        assertNull(UpdateCheckerManager.pickPatchAsset(release, "1.8.76", null, "arm64-v8a"))
        assertNull(UpdateCheckerManager.pickPatchAsset(release, "1.8.76", "1.8.75", null))
        assertNull(UpdateCheckerManager.pickPatchAsset(release, null, "1.8.75", "arm64-v8a"))
    }
}
