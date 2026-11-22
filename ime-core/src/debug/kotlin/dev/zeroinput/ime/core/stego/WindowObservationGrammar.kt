package dev.zeroinput.ime.core.stego

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.security.MessageDigest

/** The frozen host graph preserves ranking order and the required observation follow-ups. */
class WindowObservationGrammar(asset: ByteArray) {
    internal val states = read(asset)

    fun operation(current: () -> Boolean): WindowObservationSurface = WindowObservationSurface(this, current)

    internal class Group(val units: Int, val target: Int, val sentences: List<String>)
    internal class Choice(val group: Int, val index: Int)
    internal class State(val ending: Boolean, val groups: List<Group>) {
        val choices = buildMap {
            groups.forEachIndexed { groupIndex, group ->
                group.sentences.forEachIndexed { index, sentence ->
                    if (put(sentence, Choice(groupIndex, index)) != null) throw VerseTestFormatFailure()
                }
            }
        }
    }

    private fun read(asset: ByteArray): List<State> {
        if (asset.size != ASSET_BYTES || !MessageDigest.getInstance("SHA-256").digest(asset).contentEquals(assetHash))
            throw VerseTestFormatFailure()
        try {
            return DataInputStream(ByteArrayInputStream(asset)).use { input ->
                if (input.readInt() != 0x5a574f31) throw VerseTestFormatFailure()
                val count = input.readUnsignedShort()
                val states = input.readUnsignedShort()
                if (count == 0 || states != 896) throw VerseTestFormatFailure()
                val sentences = List(count) {
                    val length = input.readUnsignedByte()
                    if (length == 0) throw VerseTestFormatFailure()
                    String(CharArray(length) {
                        input.readChar().also { if (it !in '\u4e00'..'\u9fff') throw VerseTestFormatFailure() }
                    })
                }
                val graph = List(states) { readState(input, sentences, states) }
                if (input.read() != -1) throw VerseTestFormatFailure()
                graph
            }
        } catch (_: IOException) {
            throw VerseTestFormatFailure()
        }
    }

    private fun readState(input: DataInputStream, sentences: List<String>, states: Int): State {
        val ending = input.readUnsignedByte()
        val groupCount = input.readUnsignedShort()
        if (ending !in 0..1 || groupCount == 0) throw VerseTestFormatFailure()
        var previous = 0
        return State(ending == 1, List(groupCount) {
            val units = input.readUnsignedByte()
            val target = input.readUnsignedShort()
            val count = input.readUnsignedShort()
            val order = units * states + target
            if (units < 2 || target >= states || order <= previous || count !in 1..sentences.size)
                throw VerseTestFormatFailure()
            previous = order
            Group(units, target, List(count) {
                val sentence = sentences.getOrNull(input.readUnsignedShort()) ?: throw VerseTestFormatFailure()
                if (sentence.length + 1 != units) throw VerseTestFormatFailure()
                sentence
            })
        })
    }

    companion object {
        const val ASSET_NAME = "window-observation.bin"
        const val ASSET_BYTES = 235_920
        private const val ASSET_SHA256 = "3a8b44dfe515c5d1498fa09cf9b07a0fdc41937598bc718feab9be1e6b01ca1d"
        private val assetHash = ASSET_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
