package dev.cluvex.zedsecure.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object GeoPayload {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): NetworkInfo {
        val obj = json.parseToJsonElement(body).jsonObject

        fun JsonObject?.text(key: String): String? {
            val v = runCatching { this?.get(key)?.jsonPrimitive?.content }.getOrNull()
            return v?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        }

        fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { obj.text(it) }

        require(obj.text("success") != "false") { "provider reported failure" }

        val location = runCatching { obj["location"]?.jsonObject }.getOrNull()
        val connection = runCatching { obj["connection"]?.jsonObject }.getOrNull()

        val reported = str("ip", "clientIp", "ip_addr", "query", "ipAddress")
        val reportedIsV6 = reported != null && reported.contains(':')

        val countryValues = listOfNotNull(str("country_name"), str("countryName"), str("country"))

        return NetworkInfo(
            ipv4 = reported?.takeUnless { reportedIsV6 },
            ipv6 = reported?.takeIf { reportedIsV6 },
            isp = (
                str("isp", "organization", "asn_organization", "asOrganization", "asnOrganization", "asn_org", "org", "as")
                    ?: connection.text("isp")
                    ?: connection.text("org")
                )?.let { withoutAsNumber(it) },
            city = str("city", "cityName"),
            country = countryValues.firstOrNull { !isIsoCode(it) },
            countryCode = (
                str("country_code", "countryCode", "country_iso")
                    ?: location.text("country_code")
                    ?: countryValues.firstOrNull { isIsoCode(it) }
                )?.uppercase(),
        )
    }

    fun isComplete(info: NetworkInfo): Boolean =
        info.isp != null && info.city != null && info.country != null

    fun complete(winner: NetworkInfo, other: NetworkInfo): NetworkInfo {
        val sameAddress = when {
            winner.ipv4 != null && other.ipv4 != null -> winner.ipv4 == other.ipv4
            winner.ipv6 != null && other.ipv6 != null -> winner.ipv6 == other.ipv6

            else -> winner.countryCode != null && winner.countryCode == other.countryCode
        }
        if (!sameAddress) return winner
        val sameCountry = winner.countryCode != null && winner.countryCode == other.countryCode
        return winner.copy(
            isp = winner.isp ?: other.isp,
            city = winner.city ?: other.city.takeIf { sameCountry },
            country = winner.country ?: other.country.takeIf { sameCountry },
        )
    }

    private fun isIsoCode(value: String): Boolean = value.length == 2 && value.all { it.isLetter() }

    private fun withoutAsNumber(value: String): String =
        value.replace(AS_PREFIX, "").trim().ifEmpty { value }

    private val AS_PREFIX = Regex("^AS\\d+\\s+", RegexOption.IGNORE_CASE)
}
