package com.aras.client.util

import java.util.Locale

/**
 * Best-effort country detection for a server profile, returning an ISO-3166-1
 * alpha-2 code (e.g. "DE") or null when nothing matches. The UI renders the
 * code with the bundled vector flag set.
 *
 * Strategy, in order:
 *  1. A flag emoji already present in the remarks is converted to its ISO code.
 *  2. A standalone ISO-3166-1 alpha-2 token ("DE", "nl", "US"…) is accepted.
 *     Only real ISO codes count, so random 2-letter words are never mistaken
 *     for countries.
 *  3. An English country name ("Germany", "Netherlands"…) from the complete
 *     ISO country list is mapped to its code.
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

    /** Maps a flag emoji (two regional-indicator code points) back to its ISO-2 code. */
    private fun isoFromFlag(flag: String): String? {
        if (flag.length < 4) return null
        val first = flag.codePointAt(0)
        val second = flag.codePointAt(2)
        if (first !in 0x1F1E6..0x1F1FF || second !in 0x1F1E6..0x1F1FF) return null
        return buildString(2) {
            append(('A'.code + (first - 0x1F1E6)).toChar())
            append(('A'.code + (second - 0x1F1E6)).toChar())
        }
    }

    /** Removes flag emoji from provider-supplied remarks. */
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

    /**
     * Resolves any provider-provided country hint (ISO code, English country
     * name, known city, or a flag emoji) to ISO-2. Returns null when unknown.
     */
    fun isoForCountry(country: String?): String? {
        val raw = country?.trim().orEmpty()
        if (raw.isBlank()) return null
        findFlag(raw).takeIf { it.isNotBlank() }?.let { return isoFromFlag(it) }
        normalizeIso(raw)?.let { return it }
        return resolveCountryIso(raw)
    }

    /**
     * The country's name in [locale], for the connected bar: an IP API's English
     * "Croatia" or a raw "HR" both read as the name the rest of the UI is written
     * in. Provider text that maps to no country is handed back unchanged rather
     * than dropped.
     */
    fun displayName(country: String?, locale: Locale = Locale.getDefault()): String? {
        val raw = country?.trim().orEmpty()
        if (raw.isBlank()) return null
        val iso = isoForCountry(raw) ?: return raw
        val name = Locale("", iso).getDisplayCountry(locale)
        return name.ifBlank { raw }
    }

    private fun normalizeIso(value: String): String? = value
        .trim()
        .uppercase(Locale.ROOT)
        .takeIf { it.length == 2 && it in isoCodes }

    private fun resolveCountryIso(raw: String): String? {
        val text = raw.lowercase(Locale.ROOT)
        isoTokenRegex.findAll(text).forEach { match ->
            normalizeIso(match.groupValues[2])?.let { return it }
        }
        nameEntriesByLength
            .firstOrNull { (name, _) -> name in text }
            ?.let { return it.value }
        cityEntriesByLength
            .firstOrNull { (city, _) -> city in text }
            ?.let { return it.value }
        return null
    }
}
