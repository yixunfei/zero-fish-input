package dev.zeroinput.engine.api

/** Immutable, verified public data references. Implementations run only on the engine worker. */
data class PublicDictionaryFile(val id: String, val path: String, val sha256: String)

data class PublicDictionarySnapshot(
    val extraFiles: List<PublicDictionaryFile>,
    val disabledBuiltInTables: Set<String> = emptySet(),
    val builtInReplacements: Map<String, PublicDictionaryFile> = emptyMap(),
)

fun interface PublicDictionarySource {
    fun enabledFiles(): List<PublicDictionaryFile>

    /** Holds source files alive until the consumer has copied its immutable snapshot. */
    fun <T> withEnabledFiles(consume: (List<PublicDictionaryFile>) -> T): T = consume(enabledFiles())

    /** Holds both built-in selection and public files stable through deployment. */
    fun <T> withSnapshot(consume: (PublicDictionarySnapshot) -> T): T =
        withEnabledFiles { consume(PublicDictionarySnapshot(it)) }

    companion object {
        val Empty = PublicDictionarySource { emptyList() }
    }
}
