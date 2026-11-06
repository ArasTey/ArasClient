package dev.cluvex.zedsecure.data.map

import dev.cluvex.zedsecure.shared.resources.Res

object WorldMap {
    const val WIDTH: Float = 65535f

    const val HEIGHT: Float = WIDTH / 2f

    class Country(
        val code: String,
        val anchorX: Float,
        val anchorY: Float,
        val rings: List<FloatArray>,
    )

    private var cached: List<Country>? = null

    suspend fun countries(): List<Country> {
        cached?.let { return it }
        val parsed = runCatching { parse(Res.readBytes("files/map/world.bin")) }.getOrDefault(emptyList())
        cached = parsed
        return parsed
    }

    fun anchorOf(countries: List<Country>, code: String?): Pair<Float, Float>? {
        val c = code?.trim()?.uppercase()?.takeIf { it.length == 2 } ?: return null
        val hit = countries.firstOrNull { it.code == c } ?: return null
        return hit.anchorX to hit.anchorY
    }

    fun gridOf(lon: Float, lat: Float): Pair<Float, Float> =
        ((lon + 180f) / 360f * WIDTH) to ((90f - lat) / 180f * HEIGHT)

    private const val Y_SCALE = 0.5f

    private fun parse(bytes: ByteArray): List<Country> {
        var o = 0
        fun u8(): Int = bytes[o++].toInt() and 0xFF
        fun u16(): Int {
            val v = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
            o += 2
            return v
        }

        require(bytes.size > 6) { "world map asset truncated" }
        require(
            bytes[0] == 'Z'.code.toByte() && bytes[1] == 'W'.code.toByte() &&
                bytes[2] == 'M'.code.toByte() && bytes[3] == '1'.code.toByte()
        ) { "world map asset has the wrong magic" }
        o = 4

        val count = u16()
        val out = ArrayList<Country>(count)
        repeat(count) {
            val code = "${bytes[o].toInt().toChar()}${bytes[o + 1].toInt().toChar()}"
            o += 2
            val ax = u16().toFloat()

            val ay = u16().toFloat() * Y_SCALE
            val ringCount = u8()
            val rings = ArrayList<FloatArray>(ringCount)
            repeat(ringCount) {
                val pointCount = u16()
                val ring = FloatArray(pointCount * 2)
                for (i in 0 until pointCount) {
                    ring[i * 2] = u16().toFloat()
                    ring[i * 2 + 1] = u16().toFloat() * Y_SCALE
                }
                rings += ring
            }
            out += Country(code, ax, ay, rings)
        }
        return out
    }
}
