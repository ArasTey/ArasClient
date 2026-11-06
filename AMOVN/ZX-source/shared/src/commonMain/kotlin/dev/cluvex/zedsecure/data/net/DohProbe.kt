package dev.cluvex.zedsecure.data.net

import dev.cluvex.zedsecure.platform.httpJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object DohProbe {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(server: String, host: String, timeoutMs: Int = 8_000): Result<List<String>> {
        val url = if (server.startsWith("http", ignoreCase = true)) server else "https://$server/dns-query"
        val response = runCatching {
            httpJson(
                method = "GET",
                url = "$url?name=$host&type=A",
                headers = mapOf("accept" to "application/dns-json"),
                body = null,
                readTimeoutMs = timeoutMs,
            )
        }.getOrElse { return Result.failure(IllegalStateException("$url did not answer: ${it.message}")) }

        if (response.code !in 200..299) {
            return Result.failure(IllegalStateException("$url answered HTTP ${response.code}"))
        }
        val answers = runCatching {
            json.parseToJsonElement(response.body).jsonObject["Answer"]?.jsonArray
                ?.mapNotNull { it.jsonObject["data"]?.jsonPrimitive?.contentOrNull }
        }.getOrNull().orEmpty()
        return if (answers.isEmpty()) {
            Result.failure(IllegalStateException("$url answered, but returned no address for $host"))
        } else {
            Result.success(answers)
        }
    }
}
