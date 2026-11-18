package dev.zeroinput.languagepack

import java.io.Reader
import org.json.JSONObject

/** Folds JSON events into bounded dictionary rows without retaining the document tree. */
internal object LanguagePackDictionaryJson {
    fun read(reader: Reader, limit: Int): Set<PackDictionaryEntry> {
        val visitor = DictionaryVisitor(limit)
        LanguagePackJsonReader(reader, visitor).read()
        val root = checkNotNull(visitor.root)
        require(root.kind == Kind.OBJECT || root.kind == Kind.ARRAY) { "Invalid language pack dictionary" }
        return root.entries
    }

    private enum class Kind { STRING, PRIMITIVE, OBJECT, ARRAY }
    private data class Value(val kind: Kind, val text: String?, val json: String?,
        val entries: Set<PackDictionaryEntry> = emptySet(), val pair: List<String?> = emptyList(),
        val mapped: Set<PackDictionaryEntry> = emptySet())

    private class DictionaryVisitor(private val limit: Int) : LanguagePackJsonReader.Visitor {
        private val stack = ArrayDeque<Frame>()
        var root: Value? = null
            private set

        override fun beginObject() { stack.addLast(Frame(Kind.OBJECT, limit)) }
        override fun beginArray() {
            val key = stack.lastOrNull()?.takeIf { it.kind == Kind.OBJECT }?.name
            stack.addLast(Frame(Kind.ARRAY, limit, key))
        }
        override fun name(value: String) { stack.last().name = value }
        override fun endObject() = complete(stack.removeLast().finish())
        override fun endArray() = complete(stack.removeLast().finish())
        override fun scalar(value: String, quoted: Boolean) {
            complete(Value(if (quoted) Kind.STRING else Kind.PRIMITIVE, value,
                (if (quoted) JSONObject.quote(value) else value).takeIf { it.length <= MAX_VALUE_CHARS }))
        }

        private fun complete(value: Value) {
            if (stack.isEmpty()) root = value else stack.last().accept(value)
        }
    }

    private class Frame(val kind: Kind, private val limit: Int, private val mapKey: String? = null) {
        var name = ""
        private val entries = LinkedHashSet<PackDictionaryEntry>()
        private val mapped = LinkedHashSet<PackDictionaryEntry>()
        private val pair = ArrayList<String?>(2)
        // Only unusual scalar coercions need serialized containers; never retain more
        // than a valid row can use. Overlong representations cannot become a row.
        private var compact: StringBuilder? = StringBuilder(if (kind == Kind.OBJECT) "{" else "[")
        private var first = true
        private var hasShortcut = false
        private var hasValue = false
        private var shortcut: String? = null
        private var word: String? = null

        fun accept(value: Value) {
            append(if (first) "" else ",")
            first = false
            if (kind == Kind.OBJECT) {
                append(JSONObject.quote(name)); append(":")
                when (name) {
                    "shortcut" -> { hasShortcut = true; shortcut = fieldText(value) }
                    "value" -> { hasValue = true; word = fieldText(value) }
                }
                when (value.kind) {
                    Kind.STRING -> value.text?.let { add(entries, PackDictionaryEntry.pair(name, it)) }
                    Kind.OBJECT -> merge(entries, value.entries)
                    Kind.ARRAY -> merge(entries, value.mapped)
                    Kind.PRIMITIVE -> Unit
                }
            } else {
                if (pair.size < 2) pair += value.text
                when (value.kind) {
                    Kind.OBJECT -> { merge(entries, value.entries); if (mapKey != null) merge(mapped, value.entries) }
                    Kind.ARRAY -> if (value.pair.size == 2) {
                        val first = value.pair[0]
                        val second = value.pair[1]
                        if (first != null && second != null) add(entries, PackDictionaryEntry.pair(first, second))
                    }
                    else -> Unit
                }
                if (mapKey != null && value.kind != Kind.OBJECT) value.text?.let {
                    add(mapped, PackDictionaryEntry.create(mapKey, it))
                }
            }
            append(value.json)
        }

        fun finish(): Value {
            append(if (kind == Kind.OBJECT) "}" else "]")
            if (kind == Kind.OBJECT && hasShortcut && hasValue) {
                entries.clear()
                val code = shortcut
                val text = word
                if (code != null && text != null) add(entries, PackDictionaryEntry.create(code, text))
            }
            val json = compact?.toString()
            return Value(kind, json, json, entries, pair, mapped)
        }

        private fun fieldText(value: Value): String? =
            if (value.kind == Kind.PRIMITIVE && value.text == "null") "" else value.text

        private fun append(text: String?) {
            val buffer = compact ?: return
            if (text == null || buffer.length + text.length > MAX_VALUE_CHARS) compact = null else buffer.append(text)
        }

        private fun add(target: MutableSet<PackDictionaryEntry>, entry: PackDictionaryEntry?) {
            if (entry != null && target.size < limit) target += entry
        }

        private fun merge(target: MutableSet<PackDictionaryEntry>, values: Set<PackDictionaryEntry>) {
            for (value in values) { if (target.size >= limit) break; target += value }
        }
    }

    private const val MAX_VALUE_CHARS = 128
}
