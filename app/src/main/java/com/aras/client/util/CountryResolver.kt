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

    private val flagRegex = Regex("[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]")
    private val isoTokenRegex = Regex("(^|[^a-zA-Z])([a-zA-Z]{2})([^a-zA-Z]|$)")

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
     * Priority: the provider's own naming (flag emoji, ISO code, country
     * name) always wins — subscription panels deliberately label nodes with
     * a country while many share one IP behind a CDN/worker, so a GeoIP
     * lookup would be wrong there. The server-IP lookup ([geoIso], from
     * GeoIPResolver) is only used when the name carries no country hint.
     */
    fun resolve(profile: ProfileItem, geoIso: String = ""): String {
        val raw = "${profile.remarks} ${profile.description.orEmpty()}"
        val text = raw.lowercase(Locale.ROOT)

        // 1) Existing flag emoji wins.
        flagRegex.find(raw)?.let { return it.value }

        // 2) Standalone ISO-2 token that is a real country code.
        isoTokenRegex.findAll(text).forEach { m ->
            val iso = m.groupValues[2].uppercase(Locale.ROOT)
            if (iso in isoCodes) return isoToFlag(iso)
        }

        // 3) Full English country name.
        nameToIso.forEach { (name, iso) ->
            if (text.contains(name)) return isoToFlag(iso)
        }

        // 4) Well-known city / region.
        cityToIso.forEach { (city, iso) ->
            if (text.contains(city)) return isoToFlag(iso)
        }

        // 5) Server-IP lookup — only when the name had no country hint at all.
        if (geoIso.length == 2) return isoToFlag(geoIso)

        return ""
    }
}
