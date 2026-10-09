package dev.zeroinput.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Looper
import java.nio.FloatBuffer

/** Worker-owned, fully offline single-character handwriting recognizer. */
class OfflineHandwritingRecognizer(context: Context, resources: dev.zeroinput.engine.api.PublicResourceSource? = null) : AutoCloseable {
    private val ownerThread = Thread.currentThread()
    private val environment: OrtEnvironment
    private val session: OrtSession
    private val characters: List<String>
    private var strokeModels: List<HandwritingStrokeModel> = emptyList()
    private val resourceLease: dev.zeroinput.engine.api.PublicResourceLease?

    init {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Handwriting model requires worker thread" }
        resourceLease = resources?.acquire("handwriting")
        check(resources == null || resourceLease != null) { "Handwriting resource not installed" }
        try {
        val files = resourceLease?.files
        val model = HandwritingAssets.model(context, files)
        characters = HandwritingAssets.characters(context, files)
        strokeModels = HandwritingStrokeAssets.load(context, files)
        environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
            environment.createSession(model.absolutePath, options)
        }
        } catch (error: Throwable) { resourceLease?.close(); throw error }
    }

    fun recognize(strokes: List<FloatArray>, cancelled: () -> Boolean = { false }): List<String> {
        check(Thread.currentThread() === ownerThread) { "Handwriting recognition requires its worker" }
        if (cancelled()) return emptyList()
        val features = HandwritingStrokeFeatures.from(strokes) ?: return emptyList()
        val strokeResults = features.use { input -> strokeModels.map { it.recognize(input, cancelled) } }
        if (cancelled()) return emptyList()
        val imageResults = recognizeImage(strokes, cancelled)
        return if (cancelled()) emptyList() else HandwritingCandidateFusion.combine(strokeResults, imageResults)
    }

    private fun recognizeImage(strokes: List<FloatArray>, cancelled: () -> Boolean): List<String> {
        val image = HandwritingRasterizer.rasterize(strokes) ?: return emptyList()
        try {
            if (cancelled()) return emptyList()
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(image),
                longArrayOf(1, 3, HandwritingRasterizer.HEIGHT.toLong(), HandwritingRasterizer.WIDTH.toLong())).use { tensor ->
                if (cancelled()) return emptyList()
                session.run(mapOf("x" to tensor)).use { result ->
                    if (cancelled()) return emptyList()
                    val output = result[0] as OnnxTensor
                    val shape = output.info.shape
                    if (shape.size != 3 || shape[0] != 1L || shape[2] != characters.size.toLong()) return emptyList()
                    val probabilities = output.floatBuffer
                    try {
                        return HandwritingDecoder.decode(probabilities, shape[1].toInt(), characters)
                    } finally {
                        if (!probabilities.isReadOnly) {
                            for (index in 0 until probabilities.limit()) probabilities.put(index, 0f)
                        }
                    }
                }
            }
        } finally { image.fill(0f) }
    }

    override fun close() {
        check(Thread.currentThread() === ownerThread) { "Handwriting recognition requires its worker" }
        strokeModels = emptyList()
        try { session.close() } finally { resourceLease?.close() }
    }
}
