package dev.zeroinput.ime.dictionaries

import android.content.Context
import dev.zeroinput.ime.R

internal fun dictionarySourceLabel(context: Context, source: String): String = context.getString(when (source) {
    "WANXIANG" -> R.string.public_dictionary_wanxiang
    "ICE" -> R.string.public_dictionary_ice
    "ZHWIKI" -> R.string.public_dictionary_wiki
    "QQ" -> R.string.public_dictionary_qq
    "SOGOU" -> R.string.public_dictionary_sogou
    else -> R.string.dictionary_source_other
})
