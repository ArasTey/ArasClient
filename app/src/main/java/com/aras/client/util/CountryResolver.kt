package com.aras.client.util

import com.aras.client.dto.entities.ProfileItem
import java.util.Locale

/**
 * Best-effort country detection for a server profile, returning a flag
 * emoji (e.g. "🇩🇪") or an empty string when nothing matches.
 *
 * Strategy, in order:
 *  1. A flag emoji already present in the remarks is returned as-is.
 *  2. A standalone ISO-3166-1 alpha-2 token ("DE", "nl", "US"…) is converted
 *     to its flag. Only real ISO codes count, so random 2-letter words are
 *     never mistaken for countries.
 *  3. An English country name ("Germany", "Netherlands"…) from the complete
 *     ISO country list is converted to its flag.
 *  4. Well-known VPN city/region names ("Frankfurt", "Istanbul"…) map to
 *     their country.
 *
 * The ISO name/code tables are built from java.util.Locale at runtime, so
 * every country on earth is supported with zero hardcoding bugs.
 */
object CountryResolver {

    /** ISO-2 code set from the runtime, e.g. {"DE", "US", …}. */
    private val isoCodes: Set<String> by lazy {
        Locale.getISOCountries().map { it.uppercase(Locale.ROOT) }.toSet()
    }

    /** Lowercase English country name → ISO-2 ("germany" → "DE"). */
    private val nameToIso: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        Locale.getISOCountries().forEach { iso ->
            val display = Locale("en", iso).getDisplayCountry(Locale.ENGLISH)
            if (display.isNotBlank()) {
                map[display.lowercase(Locale.ROOT)] = iso.uppercase(Locale.ROOT)
            }
        }
        // Common non-standard spellings providers actually use.
        map["usa"] = "US"
        map["united states"] = "US"
        map["uk"] = "GB"
        map["united kingdom"] = "GB"
        map["great britain"] = "GB"
        map["england"] = "GB"
        map["uae"] = "AE"
        map["emirates"] = "AE"
        map["turkiye"] = "TR"
        map["turkey"] = "TR"
        map["korea"] = "KR"
        map["south korea"] = "KR"
        map["russia"] = "RU"
        map["holland"] = "NL"
        map["iran"] = "IR"
        map
    }

    private val nameEntriesByLength by lazy {
        nameToIso.entries.sortedByDescending { it.key.length }
    }

    /** VPN city / region names → ISO-2 (cities only; country names live above). */
    private val cityToIso: Map<String, String> by lazy {
        mapOf(
            "frankfurt" to "DE", "berlin" to "DE", "munich" to "DE",
            "amsterdam" to "NL",
            "helsinki" to "FI",
            "paris" to "FR", "strasbourg" to "FR",
            "stockholm" to "SE",
            "zurich" to "CH", "geneva" to "CH",
            "vienna" to "AT",
            "istanbul" to "TR", "ankara" to "TR", "izmir" to "TR",
            "london" to "GB", "manchester" to "GB",
            "dallas" to "US", "new york" to "US", "washington" to "US",
            "los angeles" to "US", "seattle" to "US", "miami" to "US",
            "dubai" to "AE",
            "moscow" to "RU", "petersburg" to "RU",
            "warsaw" to "PL", "milan" to "IT", "madrid" to "ES",
            "tokyo" to "JP", "osaka" to "JP",
            "singapore" to "SG",
            "tehran" to "IR",
            "toronto" to "CA", "montreal" to "CA",
            "sydney" to "AU", "melbourne" to "AU",
            "prague" to "CZ", "bucharest" to "RO",
            "sofia" to "BG", "budapest" to "HU", "athens" to "GR",
            "lisbon" to "PT", "brussels" to "BE", "copenhagen" to "DK",
            "dublin" to "IE", "reykjavik" to "IS", "oslo" to "NO",
            "riga" to "LV", "vilnius" to "LT", "tallinn" to "EE",
            "hong kong" to "HK", "taipei" to "TW", "seoul" to "KR",
            "mumbai" to "IN", "delhi" to "IN",
            "kuala lumpur" to "MY", "bangkok" to "TH",
            "sao paulo" to "BR", "mexico city" to "MX",
        )
    }

    private val cityEntriesByLength by lazy {
        cityToIso.entries.sortedByDescending { it.key.length }
    }

    private val isoTokenRegex = Regex("(^|[^a-zA-Z])([a-zA-Z]{2})([^a-zA-Z]|$)")

    private fun findFlag(text: String): String {
        for (index in 0..text.length - 4) {
            if (text[index] in '\uD83C'..'\uD83D' &&
                text[index + 1] in '\uDDE6'..'\uDDFF' &&
                text[index + 2] in '\uD83C'..'\uD83D' &&
                text[index + 3] in '\uDDE6'..'\uDDFF'
            ) {
                return text.substring(index, index + 4)
            }
        }
        return ""
    }

    private fun isoToFlag(iso: String): String {
        val base = 0x1F1E6 - 'A'.code
        return iso.uppercase(Locale.ROOT)
            .take(2)
            .map { ch -> Character.toChars(base + ch.code).joinToString("") }
            .joinToString("")
    }

    /**
     * Returns the flag emoji for the profile's country, or "" when unknown.
     *
     * When [preferGeoIp] is true, a valid country resolved from the server IP
     * wins over provider naming. This is the card policy: the displayed flag
     * represents where the config endpoint is located. If GeoIP is unavailable,
     * provider flag/ISO/country/city text is used as a safe fallback.
     */
    fun withoutFlags(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (index + 3 < value.length &&
                value[index] in '\uD83C'..'\uD83D' &&
                value[index + 1] in '\uDDE6'..'\uDDFF' &&
                value[index + 2] in '\uD83C'..'\uD83D' &&
                value[index + 3] in '\uDDE6'..'\uDDFF'
            ) {
                index += 4
            } else {
                result.append(value[index])
                index++
            }
        }
        return result.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Converts an ISO code, English country name, known region, or existing flag to a flag. */
    fun flagForCountry(country: String?): String {
        val raw = country?.trim().orEmpty()
        if (raw.isBlank()) return ""
        findFlag(raw).takeIf { it.isNotBlank() }?.let { return it }
        normalizeIso(raw)?.let { return isoToFlag(it) }
        return resolveCountryText(raw)
    }

    fun resolveCardCountry(
        profile: ProfileItem,
        geoIso: String,
        testedCountry: String?,
    ): String = flagForCountry(testedCountry).ifBlank { resolve(profile, geoIso, preferGeoIp = true) }

    private fun normalizeIso(value: String): String? = value
        .trim()
        .uppercase(Locale.ROOT)
        .takeIf { it.length == 2 && it in isoCodes }

    private fun resolveCountryText(raw: String): String {
        val text = raw.lowercase(Locale.ROOT)
        isoTokenRegex.findAll(text).forEach { match ->
            normalizeIso(match.groupValues[2])?.let { return isoToFlag(it) }
        }
        nameEntriesByLength
            .firstOrNull { (name, _) -> name in text }
            ?.let { (_, iso) -> return isoToFlag(iso) }
        cityEntriesByLength
            .firstOrNull { (city, _) -> city in text }
            ?.let { (_, iso) -> return isoToFlag(iso) }
        return ""
    }

    fun resolve(
        profile: ProfileItem,
        geoIso: String = "",
        preferGeoIp: Boolean = false
    ): String {
        val raw = "${profile.remarks} ${profile.description.orEmpty()}"

        // 0) Cards and the connected profile prefer the resolved server-IP country.
        val validGeoIso = normalizeIso(geoIso)
        if (preferGeoIp && validGeoIso != null) return isoToFlag(validGeoIso)

        // 1) Provider flag/country text remains the fallback when GeoIP is unavailable.
        findFlag(raw).takeIf { it.isNotBlank() }?.let { return it }
        resolveCountryText(raw).takeIf { it.isNotBlank() }?.let { return it }

        // 2) Server-IP lookup when the name carried no usable country hint.
        return validGeoIso?.let(::isoToFlag).orEmpty()
    }
}
