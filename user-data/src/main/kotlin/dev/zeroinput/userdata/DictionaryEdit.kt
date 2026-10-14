package dev.zeroinput.userdata

data class DictionaryWord(val text: String, val reading: String, val weight: Int) {
    init {
        DictionaryEditLimits.text(text, 256)
        require(reading.length <= 1024 && (reading.isEmpty() || reading.split(' ').all { part ->
            part.length in 1..12 && part.all { it in 'a'..'z' }
        })) { "Invalid dictionary reading" }
        require(weight >= 0) { "Invalid dictionary weight" }
    }
}

data class DictionaryEdit(val dictionaryId: String, val original: DictionaryWord?, val replacement: DictionaryWord?) {
    init {
        DictionaryEditLimits.id(dictionaryId)
        require(original != null || replacement != null)
    }
    val key: String get() = (original ?: requireNotNull(replacement)).let { "${it.text}\t${it.reading}" }
}

data class DictionaryEdits(val names: Map<String, String> = emptyMap(), val entries: List<DictionaryEdit> = emptyList())

internal object DictionaryEditLimits {
    const val ENTRIES = 20_000
    const val BYTES = 5 * 1024 * 1024
    fun id(value: String) { require(value.matches(Regex("[a-z0-9_]{1,64}"))) { "Invalid dictionary identity" } }
    fun text(value: String, limit: Int) {
        require(value.isNotBlank() && value.length <= limit && value.none {
            it.isISOControl() || it == '\uFFFD' || Character.getType(it) == Character.FORMAT.toInt()
        }) { "Invalid dictionary text" }
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate())
            else require(!character.isLowSurrogate())
        }
    }
}
