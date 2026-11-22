package dev.zeroinput.ime.core.stego

import java.math.BigInteger
import java.nio.ByteBuffer

/** Frozen scenery pools and canonical mixed-radix layout. */
class VerseTestSurface(asset: ByteArray, private val form: Form = Form.FIVE) {
    private val width = form.width
    private val rowUnits = width + 1
    private val maxLines = (4 * StegoCarrierCodec.MAX_BYTES + 4) / (12 * rowUnits + 1)
    private val lines = CharArray(form.opening * width)
    private val lastFamily = ByteArray(form.opening)
    private val pools = form.branches.map { IntArray(it) }
    private val radices = (listOf(form.opening) + form.branches)
        .map { BigInteger.valueOf(it.toLong()) }
    // The opening pool is used only for the first line.
    private val maximumSpan = radices[0].multiply(
        BigInteger.valueOf(form.branches.max().toLong()).pow(maxLines - 1),
    )

    init {
        verifyVerseAsset(asset, form.assetBytes, form.assetHash)
        ByteBuffer.wrap(asset).asCharBuffer().get(lines)
        indexPools()
    }

    private fun indexPools() {
        val marks = IntArray(65_536)
        FAMILIES.forEachIndexed { i, family -> family.forEach { marks[it.code] = 1 shl i } }
        val offsets = IntArray(pools.size)
        for (row in lastFamily.indices) {
            var mask = 0
            var tail = 0
            for (position in row * width until (row + 1) * width) {
                val bit = marks[lines[position].code]
                if (bit != 0) { mask = mask or bit; tail = bit }
            }
            if (tail == 0) throw VerseTestFormatFailure()
            lastFamily[row] = Integer.numberOfTrailingZeros(tail).toByte()
            for (i in pools.indices) {
                val allowed = (1 shl i) or (1 shl ((i + 1) % pools.size))
                if ((mask and allowed) != 0) {
                    if (offsets[i] == pools[i].size) throw VerseTestFormatFailure()
                    pools[i][offsets[i]++] = row
                }
            }
        }
        if (pools.indices.any { offsets[it] != pools[it].size }) throw VerseTestFormatFailure()
    }

    fun encode(envelope: ByteArray): String {
        checkCapacity(envelope.size)
        return encode(BigInteger(1, envelope), BigInteger.ONE.shiftLeft(envelope.size * 8))
    }

    fun checkWrappedCapacity(size: Int, maxBytes: Int) = WordingFrame.fits(size, maxBytes, maximumSpan)

    fun encodeWrapped(envelope: ByteArray, maxBytes: Int, current: () -> Boolean = { true }): String {
        checkWrappedCapacity(envelope.size, maxBytes)
        val number = WordingFrame.pack(envelope, maxBytes)
        return encode(number, number.add(BigInteger.ONE), current)
    }

    private fun encode(number: BigInteger, target: BigInteger, current: () -> Boolean = { true }): String {
        val chosen = IntArray(maxLines)
        var remaining = number
        var span = BigInteger.ONE
        var previous = -1
        var count = 0
        while (span < target) {
            if (count % 32 == 0) ensureCurrent(current)
            if (count == chosen.size) throw VerseTestCapacityFailure()
            val radix = radices[previous + 1]
            val division = remaining.divideAndRemainder(radix)
            val digit = division[1].toInt()
            val row = if (previous < 0) digit else pools[previous][digit]
            chosen[count++] = row
            previous = lastFamily[row].toInt()
            remaining = division[0]
            span = span.multiply(radix)
        }
        return buildString(count * rowUnits + (count - 1) / 4) {
            for (i in 0 until count) {
                if (i % 32 == 0) ensureCurrent(current)
                append(lines, chosen[i] * width, width)
                append(separator(i + 1, i + 1 == count))
                if ((i + 1) % 4 == 0 && i + 1 != count) append('\n')
            }
            ensureCurrent(current)
        }
    }

    fun checkCapacity(envelopeBytes: Int) {
        if (form != Form.FIVE || envelopeBytes !in ENVELOPE_RANGE || (envelopeBytes - 98) % 64 != 0)
            throw VerseTestFormatFailure()
        if (maximumSpan < BigInteger.ONE.shiftLeft(envelopeBytes * 8)) throw VerseTestCapacityFailure()
    }

    fun decode(carrier: String): ByteArray {
        if (form != Form.FIVE || carrier.length !in 1..StegoCarrierCodec.MAX_UNITS) throw VerseTestFormatFailure()
        val value = parse(carrier)
        var size = 0
        for (candidate in ENVELOPE_RANGE step 64) {
            val target = BigInteger.ONE.shiftLeft(candidate * 8)
            if (value.before < target && target <= value.span) {
                if (size != 0 || value.number >= target) throw VerseTestFormatFailure()
                size = candidate
            }
        }
        if (size == 0) throw VerseTestFormatFailure()
        val bytes = value.number.toByteArray()
        val start = if (bytes.size > size) 1 else 0
        val envelope = ByteArray(size)
        bytes.copyInto(envelope, size - (bytes.size - start), start)
        if (encode(envelope) != carrier) { envelope.fill(0); throw VerseTestFormatFailure() }
        return envelope
    }

    fun decodeWrapped(carrier: String, maxBytes: Int, current: () -> Boolean = { true }): ByteArray {
        if (carrier.length !in 1..StegoCarrierCodec.MAX_UNITS) throw VerseTestFormatFailure()
        val span = parse(carrier, current)
        if (span.number < span.before) throw VerseTestFormatFailure()
        val envelope = WordingFrame.unpack(span.number, maxBytes)
        try {
            if (encodeWrapped(envelope, maxBytes, current) != carrier) throw VerseTestFormatFailure()
            return envelope
        } catch (failure: Throwable) {
            envelope.fill(0)
            throw failure
        }
    }

    private fun parse(carrier: String, current: () -> Boolean = { true }): Span {
        var span = BigInteger.ONE
        var number = BigInteger.ZERO
        var before = span
        var position = 0
        var previous = -1
        var count = 0
        while (position < carrier.length) {
            if (count % 32 == 0) ensureCurrent(current)
            if (++count > maxLines || position + rowUnits > carrier.length) throw VerseTestFormatFailure()
            val last = position + rowUnits == carrier.length
            if (carrier[position + width] != separator(count, last)) throw VerseTestFormatFailure()
            val row = findLine(carrier, position)
            val digit = if (previous < 0) row else pools[previous].binarySearch(row)
            if (digit < 0) throw VerseTestFormatFailure()
            number = number.add(span.multiply(BigInteger.valueOf(digit.toLong())))
            before = span
            span = span.multiply(radices[previous + 1])
            previous = lastFamily[row].toInt()
            position += rowUnits
            if (!last && count % 4 == 0) {
                if (position + 1 >= carrier.length || carrier[position++] != '\n') throw VerseTestFormatFailure()
            }
        }
        ensureCurrent(current)
        return Span(number, before, span)
    }

    private fun ensureCurrent(current: () -> Boolean) {
        if (!current()) throw VerseTestFormatFailure()
    }

    private fun findLine(carrier: String, at: Int): Int {
        var low = 0
        var high = form.opening - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val start = middle * width
            var ordering = 0
            for (i in 0 until width) {
                ordering = lines[start + i].compareTo(carrier[at + i])
                if (ordering != 0) break
            }
            when {
                ordering < 0 -> low = middle + 1
                ordering > 0 -> high = middle - 1
                else -> return middle
            }
        }
        throw VerseTestFormatFailure()
    }

    private fun separator(number: Int, last: Boolean): Char =
        if (number % 2 == 1 && !last) '，' else '。'

    private data class Span(val number: BigInteger, val before: BigInteger, val span: BigInteger)

    enum class Form(internal val width: Int, internal val opening: Int, internal val branches: List<Int>,
                    internal val assetHash: String) {
        FIVE(5, 79_074, listOf(61_428, 43_754, 66_637),
            "a5b5d0b3f193ab88b2e44a23270d346e7f9b9119c8790ac8f07c2d8bd67cbe32"),
        SEVEN(7, 42_153, listOf(33_468, 24_291, 36_323),
            "329235ef9495609e166725a582ff79489a59fd59aacac4c24d2814e1fb807cb0");

        val assetBytes get() = opening * width * Char.SIZE_BYTES
    }

    companion object {
        private val ENVELOPE_RANGE = 162..2210
        private val FAMILIES = listOf(
            "天日月星云霞风雨雪霜露雾雷晴夜朝暮晨晓春夏秋冬晖烟夕寒阴",
            "江河海湖溪川水泉潮波浪潭池浦渚岸渡舟船帆瀑沧沙津湾汀",
            "山峰岭岫崖岩壑石松林竹木树花草叶柳梅桃谷苔径坡丘庭",
        )
    }
}
