package dev.zeroinput.engine.english

import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.engine.api.GlideLexiconEntry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Public CMUdict spellings plus the existing project-authored common-word prior. */
object EnglishGlideLexicon {
    /** Worker-only, never called on key dispatch. No user learning or network access is involved. */
    fun loadBundled(isCancelled: () -> Boolean = { false }): List<GlideLexiconEntry> {
        checkCancelled(isCancelled)
        val source = checkNotNull(EnglishGlideLexicon::class.java.getResourceAsStream("/glide-english.txt")) {
            "Public English glide lexicon is missing"
        }
        val bytes = source.use { readBounded(it, isCancelled) }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(digest == EXPECTED_SHA256) { "Public English glide lexicon checksum mismatch" }
        val words = LinkedHashMap<String, Int>()
        DefaultEnglishLexicon.words.forEachIndexed { index, word -> words[word] = 500_000 / (index + 1) }
        bytes.toString(Charsets.US_ASCII).lineSequence().forEachIndexed { index, word ->
            if (index % 128 == 0) checkCancelled(isCancelled)
            if (word.isNotEmpty() && !word.startsWith('#')) {
                check(word.length in 1..32 && word.all { it in 'a'..'z' || it == '\'' }) {
                    "Invalid public English glide spelling"
                }
                words.putIfAbsent(word, 0)
            }
        }
        checkCancelled(isCancelled)
        return words.map { (word, frequency) ->
            GlideLexiconEntry(GlideLayout.ENGLISH_QWERTY, word, frequency = frequency)
        }
    }

    private fun readBounded(source: InputStream, isCancelled: () -> Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled(isCancelled)
            val count = source.read(buffer)
            if (count == -1) break
            check(output.size() + count <= MAX_BYTES) { "Public English glide lexicon exceeds bound" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun checkCancelled(check: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || check()) throw CancellationException("Glide lexicon cancelled")
    }

    private const val MAX_BYTES = 1_100_000
    private const val EXPECTED_SHA256 = "44b7f9daf8d99d18eeca42523c3922a140562a6e7ef0a695530942ddf8818948"
}
