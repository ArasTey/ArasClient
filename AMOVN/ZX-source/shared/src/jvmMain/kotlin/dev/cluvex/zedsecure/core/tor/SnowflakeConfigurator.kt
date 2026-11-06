package dev.cluvex.zedsecure.core.tor

object SnowflakeConfigurator {
    const val RENDEZVOUS_AMP = "amp"
    const val RENDEZVOUS_CDN77 = "cdn77"
    const val RENDEZVOUS_AMAZON = "amazon"

    private const val UTLS = "hellorandomizedalpn"
    private const val COVERT_DTLS = "randomizemimic"
    private const val ARG_MAX = 510

    private const val SQS_QUEUE = "https://sqs.us-east-1.amazonaws.com/893902434899/snowflake-broker"
    private const val SQS_CREDS =
        "eyJhd3MtYWNjZXNzLWtleS1pZCI6IkFLSUE1QUlGNFdKSlhTN1lIRUczIiwiYXdzLXNlY3JldC1rZXkiOiI3U0RN" +
            "c0pBNHM1RitXZWJ1L3pMOHZrMFFXV0lsa1c2Y1dOZlVsQ0tRIn0="

    val DEFAULT_STUN = listOf(
        "stun.l.google.com:19302",
        "stun.antisip.com:3478",
        "stun.bluesip.net:3478",
        "stun.dus.net:3478",
        "stun.epygi.com:3478",
        "stun.sonetel.com:3478",
        "stun.uls.co.za:3478",
        "stun.voipgate.com:3478",
        "stun.voys.nl:3478",
    )

    val ANCHORS = listOf(
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72",
        "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
            "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA",
    )

    fun bridgeLines(rendezvous: String, stunOverride: String = ""): List<String> {
        val stun = stunOverride.split(',', '\n').map { it.trim() }.filter { it.matches(STUN_RE) }
            .ifEmpty { DEFAULT_STUN }
        val head = when (rendezvous) {
            RENDEZVOUS_CDN77 -> "url=https://1098762253.rsc.cdn77.org/ " +
                "fronts=www.cdn77.com,www.phpmyadmin.net"
            RENDEZVOUS_AMAZON -> "fronts=ajax.aspnetcdn.com sqsqueue=$SQS_QUEUE sqscreds=$SQS_CREDS"
            else -> "url=https://snowflake-broker.torproject.net/ " +
                "ampcache=https://cdn.ampproject.org/ fronts=www.google.com"
        }
        return ANCHORS.map { anchor ->
            var line = "$anchor $head utls-imitate=$UTLS covertdtls-config=$COVERT_DTLS"

            val ice = StringBuilder("ice=")
            for ((i, s) in stun.withIndex()) {
                val next = (if (i == 0) "" else ",") + "stun:$s"
                if (line.length + ice.length + next.length > ARG_MAX) break
                ice.append(next)
            }
            if (ice.length > "ice=".length) line = "$line $ice"
            line
        }
    }

    private val STUN_RE = Regex(".+\\..+:\\d+")
}
