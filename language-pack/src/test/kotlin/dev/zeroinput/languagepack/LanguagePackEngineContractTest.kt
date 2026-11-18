package dev.zeroinput.languagepack

import dev.zeroinput.engine.api.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Test

class LanguagePackEngineContractTest {
    @Test fun sharedContract() {
        val directory = Files.createTempDirectory("pack-contract").toFile()
        try {
            val file = File(directory, "dictionary.txt").apply { writeText("ni\t你\n") }
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val manifest = LanguagePackManifest(1, "contract", "Contract", "zh-CN", "1", "pack.contract",
                listOf(LanguagePackFile(file.name, hash, file.length())))
            val factory = LanguagePackEngineFactory(InstalledLanguagePack(manifest, directory))
            check(factory.isAvailable())
            InputEngineContract.verify(factory.create(), InputLanguage.CHINESE, "ni", "你")
        } finally { directory.deleteRecursively() }
    }
}
