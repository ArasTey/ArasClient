package com.aras.client.core

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils
import java.io.File

/**
 * Country codes carried by the geosite.dat / geoip.dat currently installed.
 *
 * Xray aborts the whole config when a rule names a code the file does not have,
 * and the available codes differ between geo sources (the Iran source has no
 * gfw, Loyalsoldier has no phishing), so routes are checked against disk.
 */
object GeoDataCodes {

    private val cache = HashMap<String, Set<String>?>()

    /**
     * Codes in [fileName], or null when the file is missing or unreadable —
     * unknown availability, in which case nothing is filtered.
     */
    @Synchronized
    fun available(context: Context, fileName: String): Set<String>? {
        val file = File(Utils.userAssetPath(context), fileName)
        if (!file.isFile) return null

        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        if (cache.containsKey(key)) return cache[key]

        val codes = try {
            parse(file.readBytes())
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read geo codes from $fileName", e)
            null
        }
        // A replaced file changes the key; keep only the current file's answer.
        cache.clear()
        cache[key] = codes
        return codes
    }

    /** Drops `geosite:` entries the installed geosite.dat does not carry. */
    fun keepAvailableGeosite(entries: List<String>?, available: Set<String>?): List<String>? =
        keepAvailable(entries, "geosite:", available)

    /** Drops `geoip:` entries the installed geoip.dat does not carry. */
    fun keepAvailableGeoip(entries: List<String>?, available: Set<String>?): List<String>? =
        keepAvailable(entries, "geoip:", available)

    private fun keepAvailable(entries: List<String>?, prefix: String, available: Set<String>?): List<String>? {
        if (entries.isNullOrEmpty() || available == null) return entries
        return entries
            .filter { entry ->
                if (!entry.startsWith(prefix)) return@filter true
                // geosite:google@cn selects attributes of the google code.
                entry.removePrefix(prefix).substringBefore('@').trim().lowercase() in available
            }
            .ifEmpty { null }
    }

    /**
     * Read the codes from a geosite.dat / geoip.dat.
     *
     * Both are protobuf lists (field 1) of entries whose field 1 is the code;
     * the remaining fields (domains / CIDRs) are skipped without decoding.
     */
    fun parse(bytes: ByteArray): Set<String> {
        val codes = HashSet<String>()
        var index = 0

        while (index < bytes.size) {
            val key = readVarint(bytes, index) ?: break
            index = key.second
            when (key.first and 7) {
                2 -> {
                    val length = readVarint(bytes, index) ?: break
                    index = length.second
                    val end = index + length.first
                    if (end > bytes.size) break
                    if (key.first ushr 3 == 1) {
                        readCode(bytes, index, end)?.let { codes.add(it.lowercase()) }
                    }
                    index = end
                }
                0 -> index = readVarint(bytes, index)?.second ?: break
                1 -> index += 8
                5 -> index += 4
                else -> break
            }
        }

        return codes
    }

    private fun readCode(bytes: ByteArray, start: Int, end: Int): String? {
        var index = start
        while (index < end) {
            val key = readVarint(bytes, index) ?: return null
            index = key.second
            when (key.first and 7) {
                2 -> {
                    val length = readVarint(bytes, index) ?: return null
                    index = length.second
                    val valueEnd = index + length.first
                    if (valueEnd > end) return null
                    if (key.first ushr 3 == 1) {
                        return String(bytes, index, length.first, Charsets.UTF_8)
                    }
                    index = valueEnd
                }
                0 -> index = readVarint(bytes, index)?.second ?: return null
                1 -> index += 8
                5 -> index += 4
                else -> return null
            }
        }
        return null
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Int, Int>? {
        var result = 0
        var shift = 0
        var index = start
        while (index < bytes.size && shift < 32) {
            val byte = bytes[index].toInt() and 0xFF
            index++
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result to index
            shift += 7
        }
        return null
    }
}
