package dev.zeroinput.model

import java.io.DataInputStream
import java.io.InputStream

internal data class HandwritingStrokeCandidate(val text: String, val score: Float)

/** Feature-major linear classifier: no native dependency, personal data or persistent state. */
internal class HandwritingStrokeModel private constructor(
    private val labels: List<String>,
    private val biases: FloatArray,
    private val features: IntArray,
    private val offsets: IntArray,
    private val classes: ShortArray,
    private val weights: FloatArray,
) {
    fun recognize(input: HandwritingStrokeFeatures, cancelled: () -> Boolean): List<HandwritingStrokeCandidate> {
        val scores = DoubleArray(labels.size) { biases[it].toDouble() }
        try {
            for (feature in features.indices) {
                if (feature % 32 == 0 && cancelled()) return emptyList()
                val value = input[features[feature]]
                if (value == 0f) continue
                for (posting in offsets[feature] until offsets[feature + 1]) {
                    if (posting % 1024 == 0 && cancelled()) return emptyList()
                    scores[classes[posting].toInt() and 0xffff] += (value * weights[posting]).toDouble()
                }
            }
            val ranked = ArrayList<HandwritingStrokeCandidate>(8)
            for (index in scores.indices) {
                val score = scores[index].toFloat()
                if (!score.isFinite()) return emptyList()
                val position = ranked.indexOfFirst { score > it.score }.let { if (it < 0) ranked.size else it }
                if (position < 8) ranked.add(position, HandwritingStrokeCandidate(labels[index], score))
                if (ranked.size > 8) ranked.removeAt(8)
            }
            return if (cancelled()) emptyList() else ranked
        } finally { scores.fill(0.0) }
    }

    companion object {
        private const val MAX_LABELS = 20_000
        private const val MAX_FEATURES = 30_000
        private const val MAX_POSTINGS = 8_000_000

        fun read(source: InputStream, size: Long): HandwritingStrokeModel {
            val input = DataInputStream(source.buffered(65_536))
            check(input.readInt() == 0x5a534831 && input.littleInt() == 1) { "Invalid stroke model" }
            val labelCount = input.littleInt()
            val featureCount = input.littleInt()
            val postingCount = input.littleInt()
            val largestFeature = input.littleInt()
            check(labelCount in 1..MAX_LABELS && featureCount in 1..MAX_FEATURES &&
                postingCount in 1..MAX_POSTINGS && HandwritingStrokeFeatures.validIndex(largestFeature)) { "Invalid stroke model" }
            check(size == 28L + labelCount * 8L + featureCount * 8L + postingCount * 6L) { "Invalid stroke model" }
            val labels = ArrayList<String>(labelCount)
            val biases = FloatArray(labelCount)
            repeat(labelCount) { index ->
                val codePoint = input.littleInt()
                check(Character.isValidCodePoint(codePoint) && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                    "Invalid stroke model"
                }
                labels.add(String(Character.toChars(codePoint)))
                biases[index] = input.littleFloat()
                check(biases[index].isFinite()) { "Invalid stroke model" }
            }
            check(labels.toSet().size == labels.size) { "Invalid stroke model" }
            val features = readFeatures(input, featureCount, largestFeature)
            val offsets = readOffsets(input, featureCount, postingCount)
            val classes = ShortArray(postingCount) {
                val value = java.lang.Short.reverseBytes(input.readShort())
                check((value.toInt() and 0xffff) < labelCount) { "Invalid stroke model" }
                value
            }
            val weights = FloatArray(postingCount) {
                input.littleFloat().also { check(it.isFinite()) { "Invalid stroke model" } }
            }
            check(input.read() == -1) { "Invalid stroke model" }
            return HandwritingStrokeModel(labels, biases, features, offsets, classes, weights)
        }

        private fun readFeatures(input: DataInputStream, count: Int, largest: Int): IntArray {
            var previous = -1
            val values = IntArray(count) {
                input.littleInt().also { value ->
                    check(value > previous && HandwritingStrokeFeatures.validIndex(value)) { "Invalid stroke model" }
                    previous = value
                }
            }
            check(values.last() == largest) { "Invalid stroke model" }
            return values
        }

        private fun readOffsets(input: DataInputStream, count: Int, postingCount: Int): IntArray {
            var previous = -1
            val values = IntArray(count + 1) { index ->
                input.littleInt().also { value ->
                    check(value in 0..postingCount && value > previous && (index != 0 || value == 0)) { "Invalid stroke model" }
                    previous = value
                }
            }
            check(values.last() == postingCount) { "Invalid stroke model" }
            return values
        }

        private fun DataInputStream.littleInt(): Int = Integer.reverseBytes(readInt())
        private fun DataInputStream.littleFloat(): Float = Float.fromBits(littleInt())
    }
}
