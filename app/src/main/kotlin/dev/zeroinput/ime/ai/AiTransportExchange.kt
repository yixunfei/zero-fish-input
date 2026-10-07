package dev.zeroinput.ime.ai

import java.io.InputStream
import java.io.OutputStream

/** Injectable I/O boundary for deterministic transport tests; the provider owns its only implementation. */
internal interface AiTransportExchange {
    fun prepare(method: String, key: String, accept: String, timeoutMs: Int, bodySize: Int?)
    fun timeout(timeoutMs: Int)
    fun connect()
    fun output(): OutputStream
    fun status(): Int
    fun contentType(): String?
    fun input(): InputStream
    fun disconnect()
}
