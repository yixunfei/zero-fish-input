package dev.zeroinput.engine.api

/** Immutable, verified public data references. Implementations run only on the engine worker. */
data class PublicDictionaryFile(val id: String, val path: String, val sha256: String)

fun interface PublicDictionarySource {
    fun enabledFiles(): List<PublicDictionaryFile>

    /** Holds source files alive until the consumer has copied its immutable snapshot. */
    fun <T> withEnabledFiles(consume: (List<PublicDictionaryFile>) -> T): T = consume(enabledFiles())

    companion object {
        val Empty = PublicDictionarySource { emptyList() }
    }
}
