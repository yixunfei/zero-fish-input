package dev.zeroinput.languagepack

import org.json.JSONTokener

/** Manifests are small bounded documents; dictionary values use streaming events directly. */
internal object LanguagePackJson {
    fun parse(document: String): Any {
        val normalized = document.removePrefix("\uFEFF")
        LanguagePackJsonReader(normalized.reader()).read()
        val parser = JSONTokener(normalized)
        val value = parser.nextValue()
        require(parser.nextClean() == '\u0000') { "Language pack JSON has trailing data" }
        return value
    }
}
