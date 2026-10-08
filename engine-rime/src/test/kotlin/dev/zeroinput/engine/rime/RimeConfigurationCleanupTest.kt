package dev.zeroinput.engine.rime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RimeConfigurationCleanupTest {
    @Test fun failedObsoleteCleanupDoesNotPreventOtherCleanupOrTouchTheSelectedSchema() {
        val root = Files.createTempDirectory("rime-cleanup").toFile()
        try {
            val user = File(root, "user").apply { mkdir() }
            val kept = File(user, "zeroinput_pinyin_kept.schema.yaml").apply { writeText("public fixture") }
            val blocked = File(user, "zeroinput_pinyin_old.schema.yaml").apply { writeText("public fixture") }
            val removable = File(user, "zeroinput_pinyin_old.prism.bin").apply { writeText("public fixture") }
            val unrelated = File(user, "other.txt").apply { writeText("public fixture") }
            var reject = true
            val installer = RimeConfigurationInstaller(RimeAssetInstaller.Directories(root, user), emptyList()) { file ->
                if (reject && file == blocked) false else file.delete()
            }
            installer.removeUnused(setOf("zeroinput_pinyin_kept"))
            assertTrue(kept.exists())
            assertTrue(blocked.exists())
            assertTrue(unrelated.exists())
            assertFalse(removable.exists())
            reject = false
            installer.removeUnused(setOf("zeroinput_pinyin_kept"))
            assertFalse(blocked.exists())
            assertTrue(kept.exists())
        } finally { root.deleteRecursively() }
    }
}
