package dev.zeroinput.engine.rime

internal object NativeRimeBridge {
    private val loadError: Throwable?

    init {
        loadError = if (BuildConfig.HAS_NATIVE_RIME) {
            runCatching { System.loadLibrary("zeroinput_rime") }.exceptionOrNull()
        } else {
            null
        }
    }

    internal val isLoaded: Boolean
        get() = BuildConfig.HAS_NATIVE_RIME && loadError == null

    internal fun loadFailure(): Throwable? = loadError

    external fun nativeInitialize(sharedDirectory: String, userDirectory: String): Boolean

    external fun nativeFinalize()

    external fun nativeCreateSession(schemaId: String = "zeroinput_pinyin"): Long

    external fun nativeDeploySchema(path: String): Boolean

    external fun nativeSetOptions(sessionId: Long, simplified: Boolean, asciiPunctuation: Boolean)

    external fun nativeConvertText(text: String, simplified: Boolean): String

    external fun nativeDestroySession(sessionId: Long)

    external fun nativeProcessKey(sessionId: Long, keyCode: Int, modifiers: Int): Boolean

    external fun nativeReadUpdate(sessionId: Long, simplified: Boolean): NativeRimeUpdate

    external fun nativeCandidatePage(
        sessionId: Long,
        page: Int,
        size: Int,
        simplified: Boolean,
    ): NativeCandidatePage

    external fun nativeSelectCandidate(sessionId: Long, index: Int): Boolean

    external fun nativeSelectAbsoluteCandidate(sessionId: Long, index: Int): Boolean

    external fun nativeChangePage(sessionId: Long, backwards: Boolean): Boolean

    external fun nativeClearComposition(sessionId: Long)

    external fun nativeSetInput(sessionId: Long, input: String): Boolean
    external fun nativeSetCaret(sessionId: Long, position: Int): Boolean

    external fun nativeVersion(): String
}
