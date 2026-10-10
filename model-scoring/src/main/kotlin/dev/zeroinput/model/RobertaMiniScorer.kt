package dev.zeroinput.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.os.Looper
import dev.zeroinput.engine.api.CandidateScorer
import java.nio.LongBuffer

/** Offline Mini INT8 implementation. Construction, scoring and close belong to one worker. */
class RobertaMiniScorer(resources: dev.zeroinput.engine.api.PublicResourceSource) : CandidateScorer {
    private val resourceLease: dev.zeroinput.engine.api.PublicResourceLease
    private val environment: OrtEnvironment
    private val tokenizer: MaskedWordTokenizer
    private val session: OrtSession

    init {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Model requires worker thread" }
        resourceLease = checkNotNull(resources.acquire("mini-int8")) { "Mini resource not installed" }
        try {
            val model = MiniModelAssets.prepare(resourceLease.files)
            tokenizer = MiniModelAssets.vocabulary(resourceLease.files)
            environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
            session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
                environment.createSession(model.absolutePath, options)
            }
        } catch (error: Throwable) { resourceLease.close(); throw error }
    }

    override fun score(context: CharArray, words: Array<CharArray>, cancelled: () -> Boolean): FloatArray? {
        if (cancelled()) return null
        val batch = tokenizer.encode(context, words) ?: return null
        return batch.use {
            if (cancelled()) return null
            OnnxTensor.createTensor(environment, LongBuffer.wrap(batch.ids), longArrayOf(batch.rows.toLong(), batch.length.toLong())).use { ids ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(batch.positions), longArrayOf(batch.rows.toLong())).use { positions ->
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(batch.targets), longArrayOf(batch.rows.toLong())).use { targets ->
                        if (cancelled()) return null
                        session.run(mapOf("input_ids" to ids, "positions" to positions, "target_ids" to targets)).use { result ->
                            if (cancelled()) return null
                            val output = (result[0] as OnnxTensor).floatBuffer
                            if (output.remaining() != batch.rows) return null
                            val scores = FloatArray(words.size) { (output.get() + output.get()) / 2f }
                            if (scores.any { !it.isFinite() }) { scores.fill(0f); return null }
                            scores
                        }
                    }
                }
            }
        }
    }

    override fun close() { try { session.close() } finally { resourceLease.close() } }
}
