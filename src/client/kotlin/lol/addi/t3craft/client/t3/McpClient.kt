package lol.addi.t3craft.client.t3

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class T3UnauthorizedException : RuntimeException("T3 Code rejected the saved sign-in.")

class T3ToolException(message: String) : RuntimeException(message)

class T3ProtocolException(message: String) : RuntimeException(message)

class McpClient(
    private val serverUrl: String,
    private val accessToken: String,
    private val clientVersion: String,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val endpoint: URI = URI.create("$serverUrl/mcp")
    private val nextId = AtomicLong(1)
    private var sessionId: String? = null

    @Synchronized
    fun callTool(name: String, arguments: JsonObject): JsonObject {
        val params = JsonObject().apply {
            addProperty("name", name)
            add("arguments", arguments)
        }
        val result = requestWithSession("tools/call", params)
        if (result.get("isError")?.asBoolean == true) throw T3ToolException(toolErrorText(result))
        return result.getAsJsonObject("structuredContent")
            ?: throw T3ProtocolException("$name returned no structured content.")
    }

    @Synchronized
    fun close() {
        val id = sessionId ?: return
        sessionId = null
        runCatching {
            http.send(
                baseRequest().header(SESSION_HEADER, id).DELETE().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        }
    }

    private fun requestWithSession(method: String, params: JsonObject): JsonObject {
        if (sessionId == null) initialize()
        return try {
            request(method, params)
        } catch (expired: SessionExpiredException) {
            initialize()
            request(method, params)
        }
    }

    private fun initialize() {
        sessionId = null
        val params = JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", "t3craft")
                addProperty("version", clientVersion)
            })
        }
        request("initialize", params)
        notify("notifications/initialized")
    }

    private fun request(method: String, params: JsonObject): JsonObject {
        val id = nextId.getAndIncrement()
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        val response = post(body)
        response.headers().firstValue(SESSION_HEADER).ifPresent { sessionId = it }
        val message = findResponse(response, id)
        message.getAsJsonObject("error")?.let { error ->
            throw T3ProtocolException(error.get("message")?.asString ?: "MCP error on $method.")
        }
        return message.getAsJsonObject("result") ?: JsonObject()
    }

    private fun notify(method: String) {
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
        }
        post(body)
    }

    private fun post(body: JsonObject): HttpResponse<String> {
        val builder = baseRequest()
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        sessionId?.let { builder.header(SESSION_HEADER, it) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        when (response.statusCode()) {
            401, 403 -> throw T3UnauthorizedException()
            404 -> if (sessionId != null) throw SessionExpiredException()
        }
        if (response.statusCode() !in 200..299) {
            throw T3ProtocolException("T3 Code answered HTTP ${response.statusCode()}: ${response.body().take(200)}")
        }
        return response
    }

    private fun baseRequest(): HttpRequest.Builder =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)

    private fun findResponse(response: HttpResponse<String>, id: Long): JsonObject {
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        val messages = if (contentType.startsWith("text/event-stream")) {
            sseDataPayloads(response.body()).map(JsonParser::parseString)
        } else {
            listOf(JsonParser.parseString(response.body()))
        }
        return messages.flatMap(::flattenBatch).firstOrNull { it.get("id")?.asLong == id }
            ?: throw T3ProtocolException("T3 Code sent no reply to request $id.")
    }

    private fun flattenBatch(element: JsonElement): List<JsonObject> =
        if (element.isJsonArray) element.asJsonArray.map { it.asJsonObject } else listOf(element.asJsonObject)

    private fun sseDataPayloads(body: String): List<String> =
        body.split(Regex("\r?\n\r?\n"))
            .map { event ->
                event.lines()
                    .filter { it.startsWith("data:") }
                    .joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
            }
            .filter { it.isNotBlank() }

    private fun toolErrorText(result: JsonObject): String {
        val text = result.getAsJsonArray("content")
            ?.firstOrNull { it.asJsonObject.has("text") }
            ?.asJsonObject?.get("text")?.asString
            .orEmpty()
        val structuredMessage = runCatching {
            JsonParser.parseString(text).asJsonObject.get("message")?.asString
        }.getOrNull()
        return structuredMessage ?: text.ifBlank { "The T3 Code tool call failed." }
    }

    private class SessionExpiredException : RuntimeException()

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val SESSION_HEADER = "Mcp-Session-Id"
    }
}
