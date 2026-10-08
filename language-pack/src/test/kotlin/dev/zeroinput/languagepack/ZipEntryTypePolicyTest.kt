package dev.zeroinput.languagepack

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class ZipEntryTypePolicyTest {
    @Test fun ordinaryZipWithACommentIsAccepted() = archive { bytes ->
        validate(bytes)
    }

    @Test fun symlinksAndSpecialFilesAreRejectedRegardlessOfCreatorOs() = archive { bytes ->
        val central = centralOffset(bytes)
        for (type in listOf(0xa0, 0x10, 0x20, 0x60, 0xc0)) {
            for (creator in listOf(0, 3, 19)) {
                val changed = bytes.copyOf()
                changed[central + 5] = creator.toByte()
                changed[central + 41] = type.toByte()
                assertThrows(IllegalArgumentException::class.java) { validate(changed) }
            }
        }
    }

    @Test fun explicitRegularFileAndDirectoryModesAreAccepted() = archive { bytes ->
        for (type in listOf(0x80, 0x40)) {
            val changed = bytes.copyOf()
            changed[centralOffset(bytes) + 41] = type.toByte()
            validate(changed)
        }
    }

    @Test fun truncatedAndInconsistentDirectoriesAreRejected() = archive { bytes ->
        assertThrows(IllegalArgumentException::class.java) { validate(bytes.copyOf(bytes.size - 1)) }
        val changed = bytes.copyOf()
        changed[centralOffset(bytes) + 28] = 0x7f
        assertThrows(IllegalArgumentException::class.java) { validate(changed) }
    }

    private fun validate(bytes: ByteArray) {
        val file = Files.createTempFile("pack-mode-check", ".zip").toFile()
        try { file.writeBytes(bytes); ZipEntryTypePolicy.validate(file, 2049) }
        finally { file.delete() }
    }

    private fun archive(check: (ByteArray) -> Unit) {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use {
            it.setComment("public fixture")
            it.putNextEntry(ZipEntry("dictionary.txt"))
            it.write("ni\t你\n".toByteArray())
            it.closeEntry()
        }
        check(output.toByteArray())
    }

    private fun centralOffset(bytes: ByteArray): Int = (0 until bytes.size - 4).first {
        bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() &&
            bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte()
    }
}
