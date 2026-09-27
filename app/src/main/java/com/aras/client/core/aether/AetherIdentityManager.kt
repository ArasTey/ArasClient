package com.aras.client.core.aether

import android.content.Context
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherProtocol
import com.aras.client.util.LogUtil
import com.aras.client.AppConfig
import java.io.File

/**
 * The WARP identity the aether core holds.
 *
 * The core registers a device itself on first run and writes the credentials beside
 * its config path; the app's job is to report what is there and, when the user asks
 * for a new key, to move the old files aside and let the core register again. The
 * swap is done by rename rather than delete so a failed registration leaves the
 * working identity in place.
 */
data class AetherIdentity(
    val deviceId: String,
    val ipv4: String,
    val ipv6: String,
)

data class AetherIdentityStatus(
    val protocol: AetherProtocol,
    val primary: AetherIdentity?,
    val secondary: AetherIdentity? = null,
)

object AetherIdentityManager {

    const val BASE_FILE = "aether.toml"
    const val MASQUE_FILE = "aether-masque.toml"
    const val MASQUE_INNER_FILE = "aether-masque-secondary.toml"
    const val WIREGUARD_FILE = "aether-wg.toml"
    const val WIREGUARD_INNER_FILE = "aether-wg-secondary.toml"

    private const val PREVIOUS_DIR = "aether-previous"

    private val identityField = Regex("""^(device_id|ipv4|ipv6)\s*=\s*"([^"]*)"$""")

    /** The core logs this once the device it registered is usable. */
    private val identityReady = Regex("""identity ready: device=\S+""")

    /** For the two-hop tunnels, both hops in one line. */
    private val hopIdentitiesReady = Regex("""outer device=\S+ .*\| inner device=\S+""")

    fun workDir(context: Context): File = File(context.filesDir, AetherCoreManager.WORK_DIR)

    /** What identity the profile's protocol is using, or null when it has none yet. */
    fun status(context: Context, protocol: AetherProtocol): AetherIdentityStatus =
        status(workDir(context), protocol)

    internal fun status(workDir: File, protocol: AetherProtocol): AetherIdentityStatus = when (protocol) {
        AetherProtocol.MASQUE -> AetherIdentityStatus(
            protocol,
            read(workDir, MASQUE_FILE),
            read(workDir, MASQUE_INNER_FILE),
        )

        AetherProtocol.MIM -> AetherIdentityStatus(
            protocol,
            read(workDir, BASE_FILE),
            read(workDir, MASQUE_INNER_FILE),
        )

        else -> AetherIdentityStatus(
            protocol,
            read(workDir, WIREGUARD_FILE) ?: read(workDir, BASE_FILE),
            read(workDir, WIREGUARD_INNER_FILE),
        )
    }

    private fun read(workDir: File, name: String): AetherIdentity? {
        val file = File(workDir, name)
        if (!file.isFile) return null
        return runCatching {
            val values = HashMap<String, String>()
            file.forEachLine { line ->
                // Only top-level keys; the files nest a multiline block that can carry
                // lookalike lines that are not this device's identity.
                if (line.isBlank() || line.startsWith(" ") || line.startsWith("[")) {
                    if (line.startsWith("[")) return@forEachLine
                }
                val match = identityField.find(line.trim()) ?: return@forEachLine
                values[match.groupValues[1]] = match.groupValues[2]
            }
            val device = values["device_id"]?.takeIf { it.isNotBlank() } ?: return null
            AetherIdentity(device, values["ipv4"].orEmpty(), values["ipv6"].orEmpty())
        }.getOrNull()
    }

    /**
     * Registers a new WARP key for [profile] and keeps the old one until the new one works.
     *
     * @return the new identity, or null when the core never reported one ready
     */
    fun renew(
        context: Context,
        profile: ProfileItem,
        onOutput: (String) -> Unit,
    ): AetherIdentityStatus? {
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        val work = workDir(context).apply { mkdirs() }
        val previous = File(context.filesDir, PREVIOUS_DIR).apply { mkdirs() }
        val port = AetherCoreManager.scanPort(profile)

        // Move the old identities aside first so the core registers afresh, and so a
        // registration that never completes can still be undone.
        val stashed = stash(work, previous)
        val ready = AetherCoreManager.runUntil(
            context = context,
            arguments = AetherCoreManager.buildArguments(profile, port, scan = true),
            timeoutMs = AetherCoreManager.ONE_SHOT_TIMEOUT_MS,
            source = "aether-key",
            onOutput = onOutput,
        ) { line -> line.takeIf { isReady(protocol, it) } != null }

        if (ready == null) {
            LogUtil.w(AppConfig.TAG, "AetherIdentity: no new key; restoring the previous one")
            restore(stashed, work)
            return null
        }
        previous.listFiles()?.forEach { it.delete() }
        return status(context, protocol)
    }

    private fun isReady(protocol: AetherProtocol, line: String): Boolean =
        if (protocol.twoHops) hopIdentitiesReady.containsMatchIn(line)
        else identityReady.containsMatchIn(line)

    private data class Stashed(val name: String, val file: File)

    private fun stash(work: File, previous: File): List<Stashed> {
        val names = listOf(
            BASE_FILE, MASQUE_FILE, MASQUE_INNER_FILE, WIREGUARD_FILE, WIREGUARD_INNER_FILE,
        )
        return names.mapNotNull { name ->
            val file = File(work, name)
            if (!file.isFile) return@mapNotNull null
            val target = File(previous, name)
            target.delete()
            if (file.renameTo(target)) Stashed(name, target) else null
        }
    }

    private fun restore(stashed: List<Stashed>, work: File) {
        stashed.forEach { saved ->
            val target = File(work, saved.name)
            target.delete()
            saved.file.renameTo(target)
        }
    }
}
