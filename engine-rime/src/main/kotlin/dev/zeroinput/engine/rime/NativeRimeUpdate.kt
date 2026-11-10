package dev.zeroinput.engine.rime

/** One owned copy of one Rime context; JNI never exposes native pointers. */
internal class NativeRimeUpdate(
    val rawInput: String,
    val composition: String,
    val committedText: String,
    val candidates: Array<String>,
    val comments: Array<String>,
    val pageNumber: Int,
    val lastPage: Boolean,
    val highlightedIndex: Int,
    val caretPosition: Int,
    val spellings: Array<String>,
    /** Start/end byte offsets, valid as character offsets only for ASCII input. */
    val spellingRanges: IntArray,
)
