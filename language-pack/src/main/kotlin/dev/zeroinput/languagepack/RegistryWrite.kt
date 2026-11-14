package dev.zeroinput.languagepack

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Android's AtomicFile can report a failed rename only through the system log. */
internal fun AtomicFile.writeRegistry(json: String) {
    val output = startWrite()
    try {
        output.write(json.toByteArray(Charsets.UTF_8))
        if (Thread.currentThread().isInterrupted) throw CancellationException()
        finishWrite(output)
        val file = baseFile
        if (!file.isFile || File(file.path + ".new").exists() || File(file.path + ".bak").exists()) {
            throw IOException("Registry could not be committed")
        }
    } catch (error: Exception) {
        failWrite(output)
        throw error
    }
}
