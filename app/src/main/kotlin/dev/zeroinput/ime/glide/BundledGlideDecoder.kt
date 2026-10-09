package dev.zeroinput.ime.glide

import android.content.Context
import dev.zeroinput.engine.api.GlideDecoder
import dev.zeroinput.engine.dictionary.DictionaryGlideDecoder
import dev.zeroinput.engine.english.EnglishGlideLexicon
import dev.zeroinput.engine.rime.RimeGlideLexicon

/** Invoked only by the bounded decoding worker. All input resources are public and bundled. */
internal object BundledGlideDecoder {
    fun create(context: Context): GlideDecoder {
        val cancelled = { Thread.currentThread().isInterrupted }
        val english = EnglishGlideLexicon.loadBundled(cancelled)
        val chinese = context.assets.open("glide-zh.tsv").bufferedReader().use { dictionary ->
            RimeGlideLexicon.readPrepared(dictionary, cancelled)
        }
        check(!cancelled()) { "Glide preparation cancelled" }
        return DictionaryGlideDecoder(english + chinese, cancelled)
    }
}
