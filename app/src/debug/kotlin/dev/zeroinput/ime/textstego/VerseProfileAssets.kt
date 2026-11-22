package dev.zeroinput.ime.textstego

import android.content.res.AssetManager
import dev.zeroinput.ime.core.stego.VerseTestFormatFailure
import dev.zeroinput.ime.core.stego.VerseTestSurface
import dev.zeroinput.ime.core.stego.WindowObservationGrammar
import dev.zeroinput.ime.core.stego.WordContextBody
import java.security.MessageDigest

/** Built from pinned public inputs. Only the caller's worker may load these assets. */
internal class VerseProfileAssets(assets: AssetManager) {
    private val modelHash = WordContextBody.ASSET_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val wordBody by lazy { WordContextBody(readVerseAsset(assets, "word-context-v1.bin", WordContextBody.ASSET_BYTES)) }
    val five by lazy {
        val form = VerseTestSurface.Form.FIVE
        val words = readVerseAsset(assets, "opening.bin", form.assetBytes)
        Profile(VerseTestSurface(words, form), profileFingerprint("v3/verse", sha256(words), modelHash))
    }
    val seven by lazy {
        val form = VerseTestSurface.Form.SEVEN
        val words = readVerseAsset(assets, "seven.bin", form.assetBytes)
        Profile(VerseTestSurface(words, form), profileFingerprint("v3/verse-seven", sha256(words), modelHash))
    }
    val window by lazy {
        val graph = readVerseAsset(assets, WindowObservationGrammar.ASSET_NAME, WindowObservationGrammar.ASSET_BYTES)
        Profile(WindowObservationGrammar(graph), profileFingerprint("v4/prose-window-clauses", sha256(graph), modelHash))
    }

    class Profile<T>(val surface: T, val digest: ByteArray)
}

internal fun profileFingerprint(version: String, vararg hashes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run {
        update("ZeroInput/natural-wording-trial/profile/$version\u0000".toByteArray(Charsets.US_ASCII))
        hashes.forEach(::update)
        digest()
    }

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun readVerseAsset(assets: AssetManager, name: String, size: Int): ByteArray =
    assets.open("themed-verse/$name").use { stream ->
        val data = ByteArray(size)
        var offset = 0
        while (offset < data.size) {
            val count = stream.read(data, offset, data.size - offset)
            if (count <= 0) throw VerseTestFormatFailure()
            offset += count
        }
        if (stream.read() != -1) throw VerseTestFormatFailure()
        data
    }
