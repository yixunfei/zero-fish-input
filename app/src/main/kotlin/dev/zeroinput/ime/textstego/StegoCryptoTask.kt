package dev.zeroinput.ime.textstego

import android.content.res.AssetManager
import android.os.Handler
import android.os.SystemClock
import dev.zeroinput.ime.R
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.core.stego.StegoTransport
import dev.zeroinput.security.message.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Cancellation owns any queued result; it does not interrupt the running KDF. */
internal class StegoCryptoOperation(val token: Long, private val deadline: Long) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private var delivery: StegoDelivery? = null
    fun current(): Boolean = active.get() && SystemClock.elapsedRealtime() < deadline
    @Synchronized fun offer(value: StegoDelivery) {
        if (current() && delivery == null) delivery = value else value.close()
    }
    @Synchronized fun take(): StegoDelivery? {
        val queued = delivery
        delivery = null
        if (current()) return queued
        queued?.close()
        return null
    }
    @Synchronized override fun close() {
        active.set(false); delivery?.close(); delivery = null
    }
}

internal class StegoDelivery(val inspectingWording: Boolean = false) : AutoCloseable {
    var encoded: String? = null
    var result: CharArray? = null
    var outputs: StegoOutputs? = null
    var wording: StegoWordingCarrier? = null
    var requirements: MessageRequirements? = null
    var failure = if (inspectingWording) R.string.stego_wording_style_mismatch else R.string.stego_failed
    fun compute(action: () -> Unit) {
        try { action() }
        catch (_: StegoWordingCapacityFailure) { close(); failure = R.string.stego_wording_capacity }
        catch (_: OutOfMemoryError) { close(); failure = R.string.stego_memory_unavailable }
        catch (cancelled: java.util.concurrent.CancellationException) { close(); throw cancelled }
        catch (interrupted: InterruptedException) { close(); Thread.currentThread().interrupt(); throw interrupted }
        catch (_: Exception) { close() }
    }
    override fun close() {
        encoded = null; wording = null; requirements = null
        result?.fill('\u0000'); result = null; outputs?.close(); outputs = null
    }
}

/** Worker owns only immutable configuration, transient buffers and a weak foreground owner. */
internal class StegoCryptoTask(
    private val operation: StegoCryptoOperation,
    private val owner: WeakReference<StegoWorkspace>,
    private val main: Handler,
    private val decrypt: Boolean,
    private val stages: List<MessageStage>,
    private val transport: StegoTransport,
    private val kdf: MessageKdf,
    private val cover: CharArray,
    private val secret: CharArray,
    private val carrier: CharArray,
    private val master: MessageCredential?,
    private val wording: StegoWordingProfile?,
    private val wordingAssets: AssetManager?,
    private val randomWidths: Boolean,
    private val credentialIssue: Int?,
    private val inspectWording: Boolean,
) : Runnable, AutoCloseable {
    override fun run() {
        val delivery = StegoDelivery(inspectWording)
        try {
            if (!operation.current()) return
            delivery.compute {
                when {
                    inspectWording -> delivery.requirements = StegoWordingCodec.inspect(
                        requireNotNull(wordingAssets), requireNotNull(wording), String(carrier), operation::current)
                    wording != null && decrypt -> processWording(requireNotNull(wordingAssets), wording, delivery)
                    kdf != MessageKdf.NONE && !reserveHeadroom() -> delivery.failure = R.string.stego_memory_unavailable
                    wording != null -> processWording(requireNotNull(wordingAssets), wording, delivery)
                    else -> process(delivery)
                }
            }
        } catch (failure: Error) {
            // Publish failure before propagating a fatal worker error; never leave WORKING stuck.
            delivery.close()
            throw failure
        } finally {
            close()
            operation.offer(delivery)
            if (!operation.current() || !main.post {
                val workspace = owner.get()
                if (workspace == null) operation.close() else workspace.publish(operation)
            }) operation.close()
        }
    }

    override fun close() {
        master?.close(); cover.fill('\u0000'); secret.fill('\u0000'); carrier.fill('\u0000')
    }

    private fun processWording(assets: AssetManager, selected: StegoWordingProfile, delivery: StegoDelivery) {
        val key = requireNotNull(master)
        if (decrypt) {
            val text = String(carrier)
            val opened = StegoWordingCodec.open(assets, selected, text, key, operation::current) { requirements ->
                when {
                    requirements.mode != key.mode || requirements.kdf != kdf -> {
                        delivery.requirements = requirements; false
                    }
                    credentialIssue != null -> { delivery.failure = credentialIssue; false }
                    kdf != MessageKdf.NONE && !reserveHeadroom() -> {
                        delivery.failure = R.string.stego_memory_unavailable; false
                    }
                    else -> true
                }
            } ?: return
            delivery.result = opened
            delivery.outputs = StegoOutputs(null, opened, null, text)
        } else {
            val wording = StegoWordingCodec.seal(assets, selected, secret, key, stages, kdf, randomWidths, operation::current)
            delivery.wording = wording
            delivery.encoded = wording.text
            delivery.outputs = StegoOutputs(null, secret, null, wording.text)
        }
    }

    private fun process(delivery: StegoDelivery) {
        val crypto = MessageEnvelope()
        val key = requireNotNull(master)
        if (decrypt) {
            val parsed = StegoCarrierCodec.decode(String(carrier), String(cover))
            val coverChars = parsed.cover.toCharArray()
            try {
                val opened = crypto.open(coverChars, parsed.envelope, key) { it == kdf }
                delivery.result = opened
                val complete = StegoCarrierCodec.encode(parsed.cover, parsed.envelope, parsed.transport)
                delivery.outputs = StegoOutputs(coverChars, opened, parsed.envelope, complete)
            } finally { parsed.envelope.fill(0); coverChars.fill('\u0000') }
        } else {
            val envelope = crypto.seal(cover, secret, key, stages, kdf)
            delivery.encoded = try { StegoCarrierCodec.encode(String(cover), envelope, transport).also {
                val extracted = StegoCarrierCodec.decode(it)
                try {
                    if (!envelope.contentEquals(extracted.envelope) || extracted.cover != String(cover)) throw MessageFailure()
                } finally { extracted.envelope.fill(0) }
                delivery.outputs = StegoOutputs(cover, secret, envelope, it)
            } } finally { envelope.fill(0) }
        }
    }

    private fun reserveHeadroom(): Boolean {
        val runtime = Runtime.getRuntime()
        fun sufficient() = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory() >= StegoOptions.requiredMemory(kdf)
        // Reclaim completed managed KDFs only on this worker, never on a key event.
        if (!sufficient()) runtime.gc()
        return operation.current() && sufficient()
    }
}
