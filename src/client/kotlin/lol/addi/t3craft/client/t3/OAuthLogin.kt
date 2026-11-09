package lol.addi.t3craft.client.t3

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import lol.addi.t3craft.client.config.StoredCredential
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class OAuthLoginException(message: String) : RuntimeException(message)

class PendingLogin(
    val authorizeUri: URI,
    val credential: CompletableFuture<StoredCredential>,
    private val onCancel: () -> Unit,
) {
    fun cancel() = onCancel()
}

object OAuthLogin {
    private const val CALLBACK_PATH = "/callback"
    private const val SCOPES = "orchestration:read orchestration:operate"
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val random = SecureRandom()

    fun start(serverUrl: String): PendingLogin {
        val callbackServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirectUri = "http://127.0.0.1:${callbackServer.address.port}$CALLBACK_PATH"
        val clientId = register(serverUrl, redirectUri)
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(24)
        val code = CompletableFuture<String>()

        callbackServer.createContext(CALLBACK_PATH) { exchange ->
            val params = queryParameters(exchange.requestURI)
            val outcome = callbackOutcome(params, state)
            respond(exchange, outcome.page)
            outcome.code?.let(code::complete) ?: code.completeExceptionally(OAuthLoginException(outcome.page))
        }
        callbackServer.start()

        val credential = code
            .orTimeout(10, TimeUnit.MINUTES)
            .thenApply { exchangeCode(serverUrl, clientId, redirectUri, verifier, it) }
            .whenComplete { _, _ -> callbackServer.stop(1) }

        return PendingLogin(
            authorizeUri = authorizeUri(serverUrl, clientId, redirectUri, verifier, state),
            credential = credential,
            onCancel = { code.completeExceptionally(OAuthLoginException("Sign-in cancelled.")) },
        )
    }

    private fun register(serverUrl: String, redirectUri: String): String {
        val body = JsonObject().apply {
            addProperty("client_name", "T3 Craft (Minecraft)")
            add("redirect_uris", JsonArray().apply { add(redirectUri) })
        }
        val request = HttpRequest.newBuilder(URI.create("$serverUrl/oauth/mcp/register"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val json = sendOrExplain(request, serverUrl)
        return json.get("client_id")?.asString
            ?: throw OAuthLoginException(json.get("error_description")?.asString ?: "T3 Code refused to register the mod.")
    }

    private fun authorizeUri(
        serverUrl: String,
        clientId: String,
        redirectUri: String,
        verifier: String,
        state: String,
    ): URI {
        val query = formEncode(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "code_challenge" to s256(verifier),
            "code_challenge_method" to "S256",
            "scope" to SCOPES,
            "resource" to "$serverUrl/mcp",
            "state" to state,
        )
        return URI.create("$serverUrl/oauth/mcp/authorize?$query")
    }

    private fun exchangeCode(
        serverUrl: String,
        clientId: String,
        redirectUri: String,
        verifier: String,
        code: String,
    ): StoredCredential {
        val body = formEncode(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to verifier,
            "resource" to "$serverUrl/mcp",
        )
        val request = HttpRequest.newBuilder(URI.create("$serverUrl/oauth/mcp/token"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val json = sendOrExplain(request, serverUrl)
        val token = json.get("access_token")?.asString
            ?: throw OAuthLoginException(json.get("error_description")?.asString ?: "T3 Code did not issue a token.")
        val lifetimeSeconds = json.get("expires_in")?.asLong ?: Duration.ofDays(30).seconds
        return StoredCredential(serverUrl, token, System.currentTimeMillis() + lifetimeSeconds * 1000)
    }

    private fun sendOrExplain(request: HttpRequest, serverUrl: String): JsonObject {
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (unreachable: java.io.IOException) {
            throw OAuthLoginException("Could not reach T3 Code at $serverUrl. Is it running?")
        }
        return runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrElse {
            throw OAuthLoginException("T3 Code answered HTTP ${response.statusCode()} without JSON at ${request.uri().path}.")
        }
    }

    private data class CallbackOutcome(val code: String?, val page: String)

    private fun callbackOutcome(params: Map<String, String>, expectedState: String): CallbackOutcome {
        val error = params["error"]
        return when {
            params["state"] != expectedState -> CallbackOutcome(null, "This sign-in link is stale. Start again from Minecraft.")
            error == "access_denied" -> CallbackOutcome(null, "You declined the sign-in. Minecraft stays disconnected.")
            error != null -> CallbackOutcome(null, "Sign-in failed: ${params["error_description"] ?: error}")
            params["code"] == null -> CallbackOutcome(null, "T3 Code sent no authorization code.")
            else -> CallbackOutcome(params["code"], "T3 Craft is connected. You can close this tab and go back to Minecraft.")
        }
    }

    private fun respond(exchange: HttpExchange, message: String) {
        val html = """
            <!doctype html><html><head><meta charset="utf-8"><title>T3 Craft</title></head>
            <body style="font-family:system-ui;background:#111;color:#eee;display:grid;place-items:center;height:100vh;margin:0">
            <p style="font-size:1.2rem">${message.escapeHtml()}</p></body></html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, html.size.toLong())
        exchange.responseBody.use { it.write(html) }
    }

    private fun queryParameters(uri: URI): Map<String, String> =
        uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
            val (key, value) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

    private fun formEncode(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }

    private fun s256(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    private fun randomUrlSafe(byteCount: Int): String = base64Url(ByteArray(byteCount).also(random::nextBytes))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
