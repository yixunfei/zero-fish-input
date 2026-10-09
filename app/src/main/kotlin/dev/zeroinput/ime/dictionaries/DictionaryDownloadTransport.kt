package dev.zeroinput.ime.dictionaries

import java.io.File
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.CancellationException

/** Explicit public catalog/download requests only. No credentials, cookies, editor or personal data. */
internal class DictionaryDownloadTransport {
    fun download(address: String, target: File, limit: Long, timeoutMillis: Long = 300_000,
        progress: (Long, Long) -> Unit = { _, _ -> }) {
        require(timeoutMillis in 1..1_800_000)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var uri = validate(address)
        try {
            repeat(6) {
                checkActive(deadline)
                val connection = uri.toURL().openConnection() as HttpsURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.useCaches = false
                    connection.setRequestProperty("User-Agent", "ZeroInput-Dictionaries/1.0")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    // Public source page context; never an application/editor referrer.
                    if (uri.host == "pinyin.sogou.com") {
                        connection.setRequestProperty("Referer", "https://pinyin.sogou.com/dict/")
                    }
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location") ?: error("Missing download location")
                        uri = validate(uri.resolve(location).toASCIIString())
                        return@repeat
                    }
                    check(status == 200) { "Public source unavailable" }
                    val total = connection.contentLengthLong
                    require(total <= limit) { "Download exceeds limit" }
                    connection.inputStream.use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var count = 0L
                        while (true) {
                            checkActive(deadline)
                            val size = input.read(buffer)
                            if (size < 0) break
                            count += size
                            require(count <= limit) { "Download exceeds limit" }
                            output.write(buffer, 0, size)
                            progress(count, total)
                        }
                        require(total < 0 || count == total) { "Incomplete download" }
                    } }
                    return
                } finally { connection.disconnect() }
            }
            error("Too many download redirects")
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun checkActive(deadline: Long) {
        if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) throw CancellationException()
    }

    companion object {
        private val hosts = setOf("api.github.com", "github.com", "raw.githubusercontent.com",
            "codeload.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
            "cdict.qq.pinyin.cn", "pinyin.sogou.com", "download.pinyin.sogou.com", "dl.qqpy.sogou.com")

        fun validate(address: String): URI {
            val uri = URI(address)
            require(uri.scheme == "https" && uri.host in hosts && uri.rawUserInfo == null &&
                uri.port in listOf(-1, 443) && uri.rawFragment == null) { "Unsupported dictionary source" }
            return uri
        }
    }
}
