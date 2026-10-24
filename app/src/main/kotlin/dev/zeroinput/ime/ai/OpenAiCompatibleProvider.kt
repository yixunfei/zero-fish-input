package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.ai.device.DeviceAgentProtocolCodec
import dev.zeroinput.userdata.AiConfiguration
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Sole network transport for explicit generation, model tests and model discovery. */
class OpenAiCompatibleProvider internal constructor(
    /** Must return a prepared in-memory snapshot; never performs storage work. */
    private val configurationReader: () -> AiConfiguration,
    private val executor: ExecutorService,
    private val cancellationExecutor: java.util.concurrent.Executor = executor,
    private val exchangeFactory: (URI) -> AiTransportExchange = { PlatformExchange(it) },
    private val nanoTime: () -> Long = System::nanoTime,
    private val scheduleTimeout: (Long, () -> Unit) -> AutoCloseable = AiRequestTimeouts::schedule,
) : AiProvider, AiModelCatalog, DeviceAgentProvider {
    override fun stream(request: AiRequest, listener: (AiStreamEvent) -> Unit): AiRequestHandle =
        launch(request, { listener(AiStreamEvent.Started) },
            { listener(AiStreamEvent.Failed(it)) }, { listener(AiStreamEvent.Cancelled) }) { config, operation ->
            val profile = checkNotNull(config.activeProvider())
            val body = AiRequestEncoder.encode(request, request.model ?: config.activeModel(), profile.protocol,
                profile.streaming).toByteArray(StandardCharsets.UTF_8)
            try {
                require(body.size <= 2 * 1024 * 1024)
                val thinking = StringBuilder()
                val output = exchange(config, AiEndpoint.parse(profile.endpoint, profile.protocol).generation, body, operation) { http ->
                    val type = http.contentType()?.substringBefore(';')?.trim()?.lowercase()
                    if (type != "text/event-stream" && type != "application/json")
                        throw AiProviderError.Response("AI endpoint returned an unsupported protocol")
                    http.input().use { input ->
                        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                        BufferedReader(InputStreamReader(input, decoder)).use { reader ->
                            AiProtocolResponse.read(profile.protocol, type == "application/json", reader, operation::stopped,
                                emit = { delta ->
                                    operation.deliver { listener(AiStreamEvent.Delta(delta)) }
                                }, thinking = { delta ->
                                    AiProtocolResponse.append(thinking, delta)
                                    operation.deliver { listener(AiStreamEvent.Delta("", delta)) }
                                })
                        }
                    }
                }
                if (output.isBlank()) throw AiProviderError.Response("AI response is empty")
                operation.complete { listener(AiStreamEvent.Completed(output, thinking.toString())) }
            } finally { body.fill(0) }
        }

    override fun request(
        request: DeviceAgentModelRequest,
        listener: (DeviceAgentProviderEvent) -> Unit,
    ): AiRequestHandle {
        val ownedAttachments = request.attachments.map { AiAttachment(it.mimeType, it.bytes.copyOf(), it.displayName) }
        val ownedRequest = request.copy(attachments = ownedAttachments)
        return launch(null, { listener(DeviceAgentProviderEvent.Started) },
            { listener(DeviceAgentProviderEvent.Failed(it)) }, { listener(DeviceAgentProviderEvent.Cancelled) },
            resources = request.attachments + ownedAttachments) { config, operation ->
        val profile = checkNotNull(config.activeProvider())
        val model = ownedRequest.model ?: config.activeModel()
        validateAgentConfiguration(config, model, ownedRequest.snapshot.hasScreenshot, ownedRequest.attachments)
        val body = DeviceAgentProtocolCodec.encode(ownedRequest, model, profile.protocol)
            .toByteArray(StandardCharsets.UTF_8)
        try {
            require(body.size <= DeviceAgentLimits.MAX_REQUEST_BYTES)
            val response = exchange(config, AiEndpoint.parse(profile.endpoint, profile.protocol).generation, body, operation) { http ->
                val type = http.contentType()?.substringBefore(';')?.trim()?.lowercase()
                if (type != "application/json") throw AiProviderError.Response("Device agent protocol is invalid")
                http.input().use { input ->
                    val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    InputStreamReader(input, decoder).buffered().use { reader ->
                        AiResponseReader.parse(reader, operation::stopped, AiLimits.MAX_STREAM_CHARS) { root ->
                            DeviceAgentProtocolCodec.decode(root, profile.protocol)
                        }
                    }
                }
            }
            operation.complete { listener(DeviceAgentProviderEvent.Completed(response)) }
        } finally { body.fill(0) }
    }
    }

    override fun fetchModels(listener: (AiModelCatalogEvent) -> Unit): AiRequestHandle =
        launch(null, { listener(AiModelCatalogEvent.Started) },
            { listener(AiModelCatalogEvent.Failed(it)) }, { listener(AiModelCatalogEvent.Cancelled) }) { config, operation ->
            val profile = checkNotNull(config.activeProvider())
            val models = exchange(config, AiEndpoint.parse(profile.endpoint, profile.protocol).models, null, operation) { http ->
                if (http.contentType()?.substringBefore(';')?.trim()?.lowercase() != "application/json")
                    throw AiProviderError.Response("AI model list protocol is invalid")
                http.input().use { input ->
                    val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    InputStreamReader(input, decoder).buffered().use { AiResponseReader.models(it, operation::stopped) }
                }
            }
            operation.complete { listener(AiModelCatalogEvent.Completed(models)) }
        }

    private fun launch(
        request: AiRequest?, started: () -> Unit,
        failed: (AiProviderError) -> Unit, cancelled: () -> Unit,
        resources: List<AiAttachment> = emptyList(),
        work: (AiConfiguration, Operation) -> Unit,
    ): AiRequestHandle {
        val operation = Operation(request?.attachments.orEmpty() + resources, failed, cancelled)
        // Bind credentials/model at submission, not when a queued worker eventually starts.
        val config = try { configurationReader() } catch (_: Exception) {
            operation.fail(AiProviderError.Configuration("AI configuration unavailable"))
            operation.resources.cancel()
            return operation
        }
        if (!config.enabled || !config.networkAllowed) {
            operation.fail(AiProviderError.Policy("AI network is disabled"))
            operation.resources.cancel()
            return operation
        }
        if (config.timeoutMs !in 1_000L..AiLimits.MAX_TIMEOUT_MS) {
            operation.fail(AiProviderError.Configuration("AI timeout is invalid"))
            operation.resources.cancel()
            return operation
        }
        operation.deadline = nanoTime() + config.timeoutMs * 1_000_000L
        val task = FutureTask<Unit> {
            if (operation.resources.start()) {
                try {
                    operation.check()
                    // A changed snapshot revokes queued work; it cannot retarget old content.
                    if (configurationReader() != config) throw InterruptedException()
                    validateConfiguration(config, request)
                    operation.deliver(started)
                    work(config, operation)
                } catch (error: Throwable) {
                    if (error is InterruptedException || Thread.currentThread().isInterrupted) operation.cancel()
                    else operation.fail(error.asProviderError())
                } finally { operation.finish() }
            }
        }
        operation.future.set(task)
        try {
            operation.installTimer(scheduleTimeout(config.timeoutMs) {
                operation.fail(java.net.SocketTimeoutException().asProviderError())
                operation.abort()
            })
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            operation.fail(AiProviderError.Network("AI request capacity is exhausted", reason = AiNetworkFailure.CAPACITY))
            operation.abort()
        }
        return operation
    }

    private fun <T> exchange(
        config: AiConfiguration, uri: URI, body: ByteArray?, operation: Operation,
        read: (AiTransportExchange) -> T,
    ): T {
        operation.check()
        val http = exchangeFactory(uri)
        operation.connection.set(http)
        try {
            operation.check()
            http.prepare(if (body == null) "GET" else "POST", config.activeKey(),
                if (body == null) "application/json" else "text/event-stream, application/json", operation.remaining(), body?.size,
                checkNotNull(config.activeProvider()).protocol)
            operation.check()
            http.connect()
            operation.check()
            if (body != null) {
                http.timeout(operation.remaining())
                http.output().use { it.write(body) }
            }
            http.timeout(operation.remaining())
            val status = http.status()
            if (status !in 200..299) throw responseError(status,
                if (body == null) AiNetworkOperation.MODELS else AiNetworkOperation.GENERATION)
            operation.check()
            http.timeout(operation.remaining())
            return read(http).also { operation.check() }
        } finally {
            operation.connection.compareAndSet(http, null)
            http.disconnect()
        }
    }

    private inner class Operation(
        attachments: List<AiAttachment>, private val failed: (AiProviderError) -> Unit,
        private val cancelled: () -> Unit,
    ) : AiRequestHandle {
        val resources = AiRequestResources(attachments)
        val connection = AtomicReference<AiTransportExchange?>(null)
        val future = AtomicReference<FutureTask<Unit>?>(null)
        private val timer = AtomicReference<AutoCloseable?>(null)
        private val ended = AtomicBoolean()
        var deadline = Long.MAX_VALUE

        fun installTimer(value: AutoCloseable) {
            timer.set(value)
            if (ended.get()) timer.getAndSet(null)?.close()
        }
        fun remaining(): Int {
            val remaining = deadline - nanoTime()
            if (remaining <= 0) throw java.net.SocketTimeoutException()
            return ((remaining + 999_999L) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        fun check() {
            if (ended.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
            remaining()
        }
        fun stopped(): Boolean { check(); return false }
        fun deliver(action: () -> Unit) { if (!ended.get()) action() }
        fun complete(action: () -> Unit) { check(); terminal(action) }
        private fun terminal(action: () -> Unit) {
            if (ended.compareAndSet(false, true)) {
                timer.getAndSet(null)?.close()
                action()
            }
        }
        fun fail(error: AiProviderError) = terminal { failed(error) }
        override fun cancel() { try { terminal(cancelled) } finally { abort() } }
        fun abort() {
            future.get()?.cancel(true)
            resources.cancel()
            connection.getAndSet(null)?.let { http ->
                try { cancellationExecutor.execute { runCatching { http.disconnect() } } }
                catch (_: RejectedExecutionException) { /* Worker finally/read timeout closes it. */ }
            }
            // Cancellation belongs to this operation, never unrelated queued model tests.
            dev.zeroinput.ime.concurrency.BoundedExecutors.purge(executor)
        }
        fun finish() {
            try {
                fail(AiProviderError.Response("AI request ended without a result"))
            } finally {
                timer.getAndSet(null)?.close()
                resources.finish()
            }
        }
    }

    private class PlatformExchange(uri: URI) : AiTransportExchange {
        private val http = uri.toURL().openConnection() as HttpURLConnection
        override fun prepare(method: String, key: String, accept: String, timeoutMs: Int, bodySize: Int?, protocol: AiProtocol) {
            http.instanceFollowRedirects = false
            http.useCaches = false
            http.connectTimeout = timeoutMs
            http.readTimeout = timeoutMs
            http.requestMethod = method
            http.setRequestProperty("Accept", accept)
            if (protocol == AiProtocol.ANTHROPIC_MESSAGES) {
                http.setRequestProperty("x-api-key", key)
                http.setRequestProperty("anthropic-version", "2023-06-01")
            } else http.setRequestProperty("Authorization", "Bearer $key")
            if (bodySize != null) {
                http.doOutput = true
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                http.setFixedLengthStreamingMode(bodySize)
            }
        }
        override fun timeout(timeoutMs: Int) { http.readTimeout = timeoutMs }
        override fun connect() = http.connect()
        override fun output() = http.outputStream
        override fun status() = http.responseCode
        override fun contentType(): String? = http.contentType
        override fun input() = http.inputStream
        override fun disconnect() = http.disconnect()
    }

    private fun validateConfiguration(config: AiConfiguration, request: AiRequest?) {
        val key = config.activeKey()
        val profile = config.activeProvider() ?: throw AiProviderError.Configuration("AI provider is missing")
        AiEndpoint.parse(profile.endpoint, profile.protocol)
        if (key.isBlank() || key.length > 512 || key.any { it.isWhitespace() || it.isISOControl() })
            throw AiProviderError.Configuration("AI key is invalid")
        if (request == null) return
        if (!profile.protocol.supportsAudio && request.attachments.any { it.mimeType.startsWith("audio/") })
            throw AiProviderError.Configuration("This protocol does not support audio input")
        val model = request.model ?: config.activeModel()
        if (model.isBlank() || model.length > 128 || model.any(Char::isISOControl) || model !in profile.models)
            throw AiProviderError.Configuration("AI model is invalid")
        if (request.attachments.any { it.mimeType.startsWith("image/") } && model !in profile.imageModels ||
            request.attachments.any { it.mimeType.startsWith("audio/") } && model !in profile.audioModels)
            throw AiProviderError.Configuration("Selected model does not support the attachment")
    }

    private fun validateAgentConfiguration(
        config: AiConfiguration,
        model: String,
        hasScreenshot: Boolean,
        attachments: List<AiAttachment>,
    ) {
        val profile = config.activeProvider() ?: throw AiProviderError.Configuration("AI provider is missing")
        AiEndpoint.parse(profile.endpoint, profile.protocol)
        if (model.isBlank() || model.length > 128 || model.any(Char::isISOControl) || model !in profile.models) {
            throw AiProviderError.Configuration("AI model is invalid")
        }
        if (hasScreenshot && model !in profile.imageModels) {
            throw AiProviderError.Configuration("Selected model does not support device screenshots")
        }
        if (!profile.protocol.supportsAudio && attachments.any { it.mimeType.startsWith("audio/") }) {
            throw AiProviderError.Configuration("This protocol does not support device audio")
        }
        if (attachments.any { it.mimeType.startsWith("image/") } && model !in profile.imageModels) {
            throw AiProviderError.Configuration("Selected model does not support device media")
        }
        if (attachments.any { it.mimeType.startsWith("audio/") } && model !in profile.audioModels) {
            throw AiProviderError.Configuration("Selected model does not support device audio")
        }
    }

    private fun Throwable.asProviderError(): AiProviderError = when (this) {
        is AiProviderError -> this
        is java.net.SocketTimeoutException -> AiProviderError.Network("AI request timed out", reason = AiNetworkFailure.TIMEOUT)
        is java.net.UnknownHostException -> AiProviderError.Network("AI host lookup failed", reason = AiNetworkFailure.DNS)
        is javax.net.ssl.SSLException -> AiProviderError.Network("AI secure connection failed", reason = AiNetworkFailure.TLS)
        is java.io.IOException -> AiProviderError.Network("AI network request failed")
        else -> AiProviderError.Response("AI response could not be processed")
    }

    internal fun responseError(status: Int, operation: AiNetworkOperation = AiNetworkOperation.GENERATION): AiProviderError {
        val reason = when (status) {
            400, 422 -> AiNetworkFailure.REQUEST
            in 300..399 -> AiNetworkFailure.REDIRECT
            401, 403 -> AiNetworkFailure.AUTHENTICATION
            404, 405 -> AiNetworkFailure.MODEL_OR_ENDPOINT
            429 -> AiNetworkFailure.RATE_LIMIT
            in 500..599 -> AiNetworkFailure.SERVICE
            else -> AiNetworkFailure.CONNECTION
        }
        return AiProviderError.Network("AI HTTP request failed", reason = reason, statusCode = status, operation = operation)
    }
}
