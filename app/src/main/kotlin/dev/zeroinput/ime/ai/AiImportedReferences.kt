package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiReference

/** Split a reviewed import without truncation or separating a UTF-16 surrogate pair. */
internal fun importedReferences(text: String): List<AiReference> {
    require(text.indices.all { index ->
        val value = text[index]
        !value.isHighSurrogate() || (index + 1 < text.length && text[index + 1].isLowSurrogate())
    } && text.indices.all { index ->
        val value = text[index]
        !value.isLowSurrogate() || (index > 0 && text[index - 1].isHighSurrogate())
    }) { "Invalid UTF-16 reference" }
    val values = mutableListOf<AiReference>()
    var start = 0
    while (start < text.length) {
        var end = minOf(text.length, start + AiReference.MAX_REFERENCE_CHARS)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        val value = text.substring(start, end)
        if (value.isNotBlank()) values += AiReference(value, AiReference.Source.IMPORT)
        start = end
    }
    return values
}
