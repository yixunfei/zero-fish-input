package dev.zeroinput.ime.ai

import java.io.InputStream

/** Injectable I/O boundary for deterministic transport tests; the provider owns its only implementation. */
internal interface AiTransportExchange {
    fun prepare(method: String, key: String, accept: String, timeoutMs: Int, bodySize: Int?,
        protocol: dev.zeroinput.ai.api.AiProtocol)
    /** Sends at most one request and retains [body] only for that bounded call. */
    fun send(body: ByteArray?)
    fun status(): Int
    fun contentType(): String?
    fun input(): InputStream
    fun disconnect()
}
