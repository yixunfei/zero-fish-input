package dev.zeroinput.languagepack

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads bounded central-directory metadata that java.util.zip does not expose. */
internal object ZipEntryTypePolicy {
    fun validate(archive: File, maxEntries: Int) = RandomAccessFile(archive, "r").use { file ->
        require(file.length() >= 22) { "Invalid language pack archive" }
        val tailSize = minOf(file.length(), 65_557L).toInt()
        val tailStart = file.length() - tailSize
        val tail = ByteArray(tailSize)
        file.seek(tailStart)
        file.readFully(tail)
        val end = (tail.size - 22 downTo 0).firstOrNull { offset ->
            u32(tail, offset) == 0x06054b50L && offset + 22 + u16(tail, offset + 20) == tail.size
        } ?: throw IllegalArgumentException("Invalid language pack archive")
        val count = u16(tail, end + 10)
        val size = u32(tail, end + 12)
        val start = u32(tail, end + 16)
        require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0 &&
            u16(tail, end + 8) == count && count in 1..maxEntries &&
            start + size == tailStart + end) { "Unsupported language pack archive layout" }
        file.seek(start)
        val header = ByteArray(46)
        repeat(count) {
            require(file.filePointer + header.size <= start + size) { "Invalid archive directory" }
            file.readFully(header)
            require(u32(header, 0) == 0x02014b50L) { "Invalid archive directory" }
            // The high word is the Unix mode. Never materialize links, devices,
            // sockets or FIFOs, even if the archive declares another creator OS.
            val type = (u32(header, 38) ushr 16).toInt() and 0xf000
            require(type == 0 || type == 0x8000 || type == 0x4000) { "Unsupported archive entry type" }
            require(u16(header, 34) == 0) { "Unsupported archive entry disk" }
            val next = file.filePointer + u16(header, 28) + u16(header, 30) + u16(header, 32)
            require(next <= start + size) { "Invalid archive directory" }
            file.seek(next)
        }
        require(file.filePointer == start + size) { "Invalid archive directory size" }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
}
