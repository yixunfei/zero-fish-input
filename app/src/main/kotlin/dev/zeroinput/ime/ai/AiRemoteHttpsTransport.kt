package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiConnection
import dev.zeroinput.ai.api.AiConnectionKind
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Exact E4 I/O boundary. Platform TLS, verified public DNS and endpoint-scoped credentials only. */
internal class AiRemoteHttpsTransport(private val resolve: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) }) : AiRemoteTransport {
    override fun mcp(connection: AiConnection, body: String, session: String?, cancellation: AiRemoteCancellation): AiRemoteReply {
        require(connection.kind == AiConnectionKind.MCP && body.toByteArray(Charsets.UTF_8).size <= 65_536)
        AiRemotePolicy.endpoint(connection.endpoint)
        val request = Request.Builder().url(connection.endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-11-25")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (connection.token.isNotEmpty()) request.header("Authorization", "Bearer ${connection.token}")
        if (session != null) { requireSession(session); request.header("Mcp-Session-Id", session) }
        return exchange(request.build(), cancellation)
    }
    override fun search(connection: AiConnection, query: String, cancellation: AiRemoteCancellation): AiRemoteReply {
        require(connection.kind == AiConnectionKind.BRAVE && connection.endpoint == AiConnection.BRAVE_ENDPOINT)
        require(query.isNotBlank() && query.length <= 256 && query.none(Char::isISOControl))
        val url = AiConnection.BRAVE_ENDPOINT.toHttpUrl().newBuilder().addQueryParameter("q", query)
            .addQueryParameter("count", "5").addQueryParameter("text_decorations", "false").addQueryParameter("spellcheck", "false").build()
        return exchange(Request.Builder().url(url).header("Accept", "application/json")
            .header("X-Subscription-Token", connection.token).get().build(), cancellation)
    }
    private fun exchange(request: Request, cancellation: AiRemoteCancellation): AiRemoteReply {
        cancellation.check()
        val expectedHost = AiRemotePolicy.endpoint(request.url.toString()).host
        val attempted = java.util.concurrent.atomic.AtomicBoolean()
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES).cache(null)
            .protocols(listOf(Protocol.HTTP_1_1)).connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    cancellation.check(); require(hostname == expectedHost)
                    val addresses = resolve(hostname)
                    if (!AiRemotePolicy.publicAnswers(addresses))
                        throw UnknownHostException("Remote address unavailable")
                    cancellation.check(); return addresses
                }
            })
            .addNetworkInterceptor { chain ->
                cancellation.check()
                // Also blocks response-driven follow-ups (for example 503 Retry-After: 0).
                if (!attempted.compareAndSet(false, true)) throw java.io.IOException("Remote retry forbidden")
                cancellation.dispatched.set(true)
                chain.proceed(chain.request()).also { response ->
                    if (response.code !in setOf(200, 202)) {
                        response.close(); throw java.io.IOException("Remote request rejected")
                    }
                }
            }
            .build()
        val call = client.newCall(request); cancellation.bind(call::cancel)
        try {
            cancellation.check()
            return call.execute().use { response ->
                cancellation.check()
                require(response.headers.byteCount() <= 16_384)
                require(response.headers.values("Mcp-Session-Id").size <= 1)
                val session = response.header("Mcp-Session-Id")?.also(::requireSession)
                if (response.code == 202) {
                    response.body?.let { body ->
                        require(body.contentLength() <= 0 && body.byteStream().read() == -1)
                    }
                    cancellation.check()
                    return@use AiRemoteReply(202, "", session)
                }
                require(response.code == 200)
                val body = requireNotNull(response.body)
                require(body.contentLength() <= 262_144)
                val type = requireNotNull(body.contentType()); require(type.type == "application" && type.subtype == "json" || type.type == "text" && type.subtype == "event-stream")
                require(type.charset(Charsets.UTF_8) == Charsets.UTF_8)
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                val limited = object : java.io.FilterInputStream(body.byteStream()) {
                    private var count = 0
                    override fun read(): Int = super.read().also { if (it >= 0) require(++count <= 262_144) }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        val read = `in`.read(bytes, offset, minOf(length, 262_145 - count))
                        if (read > 0) { count += read; require(count <= 262_144) }; return read
                    }
                }
                val text = java.io.InputStreamReader(limited, decoder).buffered().use { AiRemoteWire.read(it, type.subtype == "event-stream", cancellation::live) }
                cancellation.check(); AiRemoteReply(200, text, session)
            }
        } finally { cancellation.unbind(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    private fun requireSession(value: String) { require(value.length in 1..128 && value.all { it.code in 33..126 }) }
}
