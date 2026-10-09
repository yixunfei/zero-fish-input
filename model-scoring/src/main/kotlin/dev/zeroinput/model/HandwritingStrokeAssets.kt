package dev.zeroinput.model

import android.content.Context
import java.security.DigestInputStream
import java.security.MessageDigest

/** Public, separately licensed model tables. Assets are never executable or user-supplied. */
internal object HandwritingStrokeAssets {
    private data class Asset(val name: String, val size: Long, val hash: String)

    private val assets = listOf(
        Asset("stroke-simplified.zsh", 7_016_330L, "fdd47959e8cb95add75fc1e5bd10ff62e88b5d09fc6c6305d284d8f3df57667f"),
        Asset("stroke-traditional.zsh", 39_052_454L, "7eaa62001987b03fa0ea24824b1a1203599064db905604026da8bc7e4e3b0288"),
    )

    fun load(context: Context, files: Map<String, dev.zeroinput.engine.api.PublicResourceFile>? = null): List<HandwritingStrokeModel> = assets.map { asset ->
        val digest = MessageDigest.getInstance("SHA-256")
        val spec = files?.get("handwriting/${asset.name}")
        val model = (spec?.let { java.io.File(it.path).inputStream() }
            ?: context.assets.open("handwriting/${asset.name}")).use { source ->
            DigestInputStream(source, digest).use { input -> HandwritingStrokeModel.read(input, spec?.bytes ?: asset.size) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == (spec?.sha256 ?: asset.hash)) { "Invalid stroke model" }
        model
    }
}
