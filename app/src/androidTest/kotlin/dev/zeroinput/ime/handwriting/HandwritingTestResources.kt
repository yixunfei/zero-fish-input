package dev.zeroinput.ime.handwriting

import android.content.Context
import dev.zeroinput.engine.api.PublicResourceFile
import dev.zeroinput.engine.api.PublicResourceLease
import dev.zeroinput.engine.api.PublicResourceSource
import java.io.File
import java.security.MessageDigest

/** Uses pinned public handwriting data from the test APK without bundling it in the app APK. */
internal object HandwritingTestResources {
    private data class Asset(val name: String, val bytes: Long, val sha256: String)

    fun source(assetContext: Context, cacheContext: Context): PublicResourceSource {
        val assets = assetManifest(assetContext)
        val files = assets.associate { asset ->
            asset.name to PublicResourceFile(
                materialize(assetContext, cacheContext, asset).absolutePath,
                asset.bytes,
                asset.sha256,
            )
        }
        return object : PublicResourceSource {
            override val revision: Long = 1L

            override fun acquire(id: String): PublicResourceLease? {
                if (id != "handwriting") return null
                return object : PublicResourceLease {
                    override val files: Map<String, PublicResourceFile> = files
                    override fun close() = Unit
                }
            }
        }
    }

    private fun assetManifest(context: Context): List<Asset> = buildList {
        context.assets.open("handwriting-test-assets.properties").bufferedReader().useLines { lines ->
            lines.filter(String::isNotBlank).forEach { line ->
                val (name, metadata) = line.split('=', limit = 2).let {
                    require(it.size == 2) { "Invalid handwriting asset manifest entry" }
                    it[0] to it[1]
                }
                val values = metadata.split(',', limit = 2)
                require(values.size == 2) { "Invalid handwriting asset metadata" }
                add(Asset(name, values[0].toLong(), values[1]))
            }
        }
    }

    private fun materialize(assetContext: Context, cacheContext: Context, asset: Asset): File {
        val target = File(cacheContext.cacheDir, "handwriting-test/${asset.sha256}")
        if (target.isFile && target.length() == asset.bytes && verified(target, asset)) return target
        val parent = checkNotNull(target.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Unable to create handwriting test cache" }
        val temporary = File.createTempFile("handwriting-", ".tmp", parent)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            assetContext.assets.open(asset.name).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= asset.bytes) { "Oversized handwriting test asset" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(size == asset.bytes && hex(digest.digest()) == asset.sha256) {
                "Invalid pinned handwriting test asset"
            }
            check(temporary.renameTo(target)) { "Unable to prepare handwriting test asset" }
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun verified(file: File, asset: Asset): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest()) == asset.sha256
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
