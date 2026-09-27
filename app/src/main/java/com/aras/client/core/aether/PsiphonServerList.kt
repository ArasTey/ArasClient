package com.aras.client.core.aether

import com.google.gson.JsonParser
import com.aras.client.AppConfig
import com.aras.client.util.LogUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.InflaterInputStream
import kotlin.io.encoding.Base64

/**
 * The server list Psiphon's client starts with.
 *
 * It is the signed, compressed list the client downloads for itself, shipped in the
 * app's assets as `psiphon_servers.dat` so a fresh install has servers before it can
 * reach the list on its own. The client takes it only as a plain file of server
 * entries, so the list is unpacked beside the identity files, and the entries from a
 * list that does not check out are never handed on: the client trusts what it is
 * given, and the file can be replaced by hand.
 */
object PsiphonServerList {

    /**
     * Psiphon's public key for its server list, the one its client checks its own
     * downloads with and the one the aether core carries.
     */
    const val SIGNING_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH" +
            "5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5" +
            "OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42Kcot" +
            "LFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6" +
            "/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7G" +
            "stZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1O" +
            "geF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8" +
            "u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz" +
            "31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xal" +
            "KxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="

    const val ASSET_NAME = "psiphon_servers.dat"
    const val ENTRIES_FILE = "psiphon-servers.txt"

    private const val SOURCE_MARK = "psiphon-servers.source"

    /**
     * The entries file for the bundled list, unpacked into [workDir] when the list is
     * new or has changed since; the entries kept from before when the list cannot be
     * used, with the reason logged, and null when there is no list at all.
     */
    fun entriesFile(assetDir: File, workDir: File): File? {
        val source = File(assetDir, ASSET_NAME)
        if (!source.isFile) return null
        val entries = File(workDir, ENTRIES_FILE)
        val mark = File(workDir, SOURCE_MARK)
        val stamp = "${source.length()}:${source.lastModified()}"
        if (entries.isFile && mark.isFile && runCatching { mark.readText() }.getOrNull() == stamp) {
            return entries
        }
        return try {
            val text = unpack(source.readBytes(), SIGNING_KEY)
            // A daemon and an editor may both unpack a new list; each writes under a name
            // of its own, so a second writer cannot see a half-written file.
            val fresh = File(workDir, "$ENTRIES_FILE.${System.nanoTime()}.new")
            fresh.writeText(text)
            if (!fresh.renameTo(entries)) {
                entries.delete()
                if (!fresh.renameTo(entries)) throw IOException("could not put ${entries.name} in place")
            }
            mark.writeText(stamp)
            entries
        } catch (e: IOException) {
            LogUtil.w(
                AppConfig.TAG,
                "Psiphon: ${ASSET_NAME} is not a usable server list; the entries kept from before stay",
                e,
            )
            entries.takeIf { it.isFile }
        }
    }

    /**
     * The server entries inside [packed], a signed, compressed list as Psiphon writes
     * it; throws when it is not one, or is not signed with [signingKey].
     *
     * Psiphon names the key by the digest of its text as written, not of the key's
     * bytes, and signs the entry text with it.
     */
    @Throws(IOException::class)
    internal fun unpack(packed: ByteArray, signingKey: String): String {
        val text = try {
            InflaterInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }.toString(Charsets.UTF_8)
        } catch (e: IOException) {
            throw IOException("the list is not compressed the way Psiphon writes it", e)
        }
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: RuntimeException) {
            throw IOException("the list is not the package Psiphon writes", e)
        }
        val data = json.get("data")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IOException("the list carries no entries")
        val keyDigest = json.get("signingPublicKeyDigest")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IOException("the list names no key")
        val signature = json.get("signature")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IOException("the list is not signed")
        try {
            val expected = MessageDigest.getInstance("SHA-256").digest(signingKey.toByteArray(Charsets.US_ASCII))
            if (!Base64.decode(keyDigest).contentEquals(expected)) {
                throw IOException("the list is signed with another key")
            }
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(signingKey)))
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update(data.toByteArray(Charsets.UTF_8))
            if (!verifier.verify(Base64.decode(signature))) {
                throw IOException("the signature of the list does not check out")
            }
        } catch (e: GeneralSecurityException) {
            throw IOException("the signature of the list cannot be checked", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("the list is not encoded the way Psiphon writes it", e)
        }
        if (data.lineSequence().none { it.isNotBlank() }) throw IOException("the list is empty")
        return data
    }
}
