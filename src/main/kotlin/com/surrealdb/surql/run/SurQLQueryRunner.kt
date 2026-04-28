package com.surrealdb.surql.run

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.surrealdb.surql.settings.SurQLSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * The result of executing a single SurrealQL statement against SurrealDB's HTTP `/sql` endpoint.
 *
 * [rows] is populated when the [status] is `"OK"` and the result is a JSON array of objects.
 * [error] is populated when the server reports a query-level error or the connection fails.
 * [rawJson] contains the pretty-printed JSON of the `result` field for display in a text view.
 */
data class QueryResult(
    val query: String,
    val status: String,
    val time: String,
    val rows: List<Map<String, Any?>>,
    val error: String?,
    val rawJson: String,
)

/**
 * Executes SurrealQL queries against the SurrealDB HTTP `/sql` endpoint using the connection
 * settings from [SurQLSettings].
 *
 * This object is thread-safe; [execute] blocks the calling thread and is intended to be called
 * from a pooled background thread (not the EDT).
 *
 * Endpoint conversion: `ws://` → `http://`, `wss://` → `https://`. Any path component (e.g.
 * `/rpc`) is stripped; `/sql` is always appended to the scheme+host+port base.
 */
object SurQLQueryRunner {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val prettyGson: Gson = Gson()

    /**
     * Executes [query] and returns the parsed result. Throws on network/IO failure.
     *
     * [fileNamespace] and [fileDatabase] are values extracted from `USE NS` / `USE DB`
     * directives in the source file. When non-null they take precedence over the namespace
     * and database configured in [SurQLSettings], so that a file which declares its own
     * context works without requiring the settings to be pre-filled.
     *
     * SurrealDB 3.x does not honour standalone `NS`/`DB` HTTP headers for session context on
     * the `/sql` endpoint. Instead, `USE NS`/`USE DB` statements are prepended to the query
     * body so that all statements in the same HTTP request share the correct session context.
     * The response array includes results for those preamble statements; [parseResponse] always
     * surfaces the first error (if any) or the last result (the actual user query).
     */
    fun execute(
        query: String,
        fileNamespace: String? = null,
        fileDatabase: String? = null,
    ): QueryResult {
        val settings = SurQLSettings.getInstance()
        val url = buildSqlUrl(settings.surrealEndpoint)
        val auth = buildAuthHeader(settings)

        // Prefer file-level USE directives; fall back to settings.
        val ns = fileNamespace?.takeIf { it.isNotBlank() }
            ?: settings.surrealNamespace.takeIf { it.isNotBlank() }
        val db = fileDatabase?.takeIf { it.isNotBlank() }
            ?: settings.surrealDatabase.takeIf { it.isNotBlank() }

        // Prepend USE NS/DB to the body to establish session context within the request.
        val safeNs = ns?.replace("`", "\\`")
        val safeDb = db?.replace("`", "\\`")
        val fullQuery = buildString {
            if (safeNs != null) append("USE NS `$safeNs`; ")
            if (safeDb != null) append("USE DB `$safeDb`; ")
            append(query)
        }
        val preambleCount = (if (safeNs != null) 1 else 0) + (if (safeDb != null) 1 else 0)

        var reqBuilder = HttpRequest.newBuilder()
            .uri(URI(url))
            .header("Accept", "application/json")
            .header("Content-Type", "text/plain")
        if (ns != null) reqBuilder = reqBuilder.header("NS", ns)
        if (db != null) reqBuilder = reqBuilder.header("DB", db)
        if (auth != null) reqBuilder = reqBuilder.header("Authorization", auth)

        val request = reqBuilder
            .POST(HttpRequest.BodyPublishers.ofString(fullQuery))
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body() ?: ""

        if (response.statusCode() !in 200..299) {
            return QueryResult(
                query = query,
                status = "ERR",
                time = "",
                rows = emptyList(),
                error = "HTTP ${response.statusCode()}: $body",
                rawJson = body,
            )
        }

        return parseResponse(query, body, preambleCount)
    }

    private fun buildAuthHeader(settings: SurQLSettings): String? {
        if (settings.surrealUsername.isBlank() || settings.surrealPassword.isBlank()) return null
        val credentials = "${settings.surrealUsername}:${settings.surrealPassword}"
        return "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))}"
    }

    private fun buildSqlUrl(endpoint: String): String {
        val withHttp = endpoint
            .replace(Regex("^ws://", setOf(RegexOption.IGNORE_CASE)), "http://")
            .replace(Regex("^wss://", setOf(RegexOption.IGNORE_CASE)), "https://")
        return try {
            val uri = URI(withHttp)
            buildString {
                append(uri.scheme ?: "http")
                append("://")
                append(uri.host ?: "localhost")
                if (uri.port != -1) append(":${uri.port}")
                append("/sql")
            }
        } catch (_: Exception) {
            "$withHttp/sql"
        }
    }

    private fun parseResponse(query: String, body: String, preambleCount: Int = 0): QueryResult {
        return try {
            val array = JsonParser.parseString(body).asJsonArray
            if (array.isEmpty) {
                return QueryResult(query, "OK", "", emptyList(), null, "[]")
            }
            // If preamble USE NS/DB statements were prepended, their results appear first.
            // Surface the first ERR result so setup failures are visible; otherwise show the
            // last result, which corresponds to the actual user query.
            val firstErr = array.firstOrNull {
                it.isJsonObject && it.asJsonObject.get("status")?.asString == "ERR"
            }
            val obj = (firstErr ?: array[array.size() - 1]).asJsonObject
            val status = obj.get("status")?.asString ?: "OK"
            val time = obj.get("time")?.asString ?: ""
            val resultEl = obj.get("result")
            val (rows, error) = parseResult(status, resultEl)
            val rawJson = if (resultEl != null) prettyGson.toJson(resultEl) else ""
            QueryResult(query, status, time, rows, error, rawJson)
        } catch (e: Exception) {
            QueryResult(
                query = query,
                status = "ERR",
                time = "",
                rows = emptyList(),
                error = e.message ?: "Failed to parse response",
                rawJson = body,
            )
        }
    }

    private fun parseResult(
        status: String,
        element: JsonElement?,
    ): Pair<List<Map<String, Any?>>, String?> {
        if (element == null) return emptyList<Map<String, Any?>>() to null
        if (status != "OK") {
            val msg = if (element.isJsonPrimitive) element.asString else element.toString()
            return emptyList<Map<String, Any?>>() to msg
        }
        if (!element.isJsonArray) return emptyList<Map<String, Any?>>() to null
        val rows = element.asJsonArray.map { elem ->
            if (elem.isJsonObject) {
                elem.asJsonObject.entrySet().associate { (k, v) -> k to jsonToValue(v) }
            } else {
                mapOf("value" to elem.toString())
            }
        }
        return rows to null
    }

    private fun jsonToValue(element: JsonElement): Any? = when {
        element.isJsonNull -> null
        element.isJsonPrimitive -> element.asJsonPrimitive.let { prim ->
            when {
                prim.isBoolean -> prim.asBoolean
                prim.isNumber -> try { prim.asLong } catch (_: Exception) { prim.asDouble }
                else -> prim.asString
            }
        }
        // Nested arrays and objects are kept as compact JSON strings for table display.
        else -> element.toString()
    }
}
