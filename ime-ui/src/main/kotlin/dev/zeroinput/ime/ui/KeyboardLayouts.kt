package dev.zeroinput.ime.ui

internal object KeyboardLayouts {
    fun nineKey(context: android.content.Context, languageLabel: String): List<List<KeySpec>> = listOf(
        listOf(KeySpec("'", context.getString(R.string.pinyin_separator), KeyboardAction.Text("'")),
            digit("2 ABC", "2"), digit("3 DEF", "3"),
            KeySpec("⌫", context.getString(R.string.key_backspace), KeyboardAction.Backspace, 0.8f, KeyStyle.MODIFIER)),
        listOf(digit("4 GHI", "4"), digit("5 JKL", "5"), digit("6 MNO", "6"),
            KeySpec("，", context.getString(R.string.key_comma), KeyboardAction.Text(","), 0.8f)),
        listOf(digit("7 PQRS", "7"), digit("8 TUV", "8"), digit("9 WXYZ", "9"),
            KeySpec("。", context.getString(R.string.key_period), KeyboardAction.Text("."), 0.8f)),
        listOf(KeySpec("?123", context.getString(R.string.key_symbols), KeyboardAction.ShowSymbols, 1f, KeyStyle.MODIFIER),
            KeySpec(languageLabel, context.getString(R.string.key_language), KeyboardAction.SwitchLanguage, 0.8f, KeyStyle.MODIFIER),
            KeySpec(context.getString(R.string.key_space), context.getString(R.string.key_space), KeyboardAction.Space, 2f),
            KeySpec("↵", context.getString(R.string.key_enter), KeyboardAction.Enter, 0.8f, KeyStyle.PRIMARY)),
    )

    private fun digit(label: String, code: String) = KeySpec(label, label, KeyboardAction.Text(code))

    fun letters(context: android.content.Context, shifted: Boolean, languageLabel: String,
        chinesePunctuation: Boolean, microsoftDoublePinyin: Boolean = false,
        literalPunctuation: Boolean = false): List<List<KeySpec>> {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        return listOf(
            characterRow(rows[0], shifted),
            characterRow(rows[1], shifted) + if (microsoftDoublePinyin)
                listOf(KeySpec(";", context.getString(R.string.key_semicolon), KeyboardAction.Text(";")))
            else emptyList(),
            listOf(
                KeySpec("⇧", context.getString(R.string.key_shift), KeyboardAction.Shift, 1.35f, KeyStyle.MODIFIER),
            ) + characterRow(rows[2], shifted) + listOf(
                KeySpec("⌫", context.getString(R.string.key_backspace), KeyboardAction.Backspace, 1.35f, KeyStyle.MODIFIER),
            ),
            listOf(
                KeySpec("?123", context.getString(R.string.key_symbols), KeyboardAction.ShowSymbols, 1.35f, KeyStyle.MODIFIER),
                KeySpec(languageLabel, context.getString(R.string.key_language), KeyboardAction.SwitchLanguage, 1.1f, KeyStyle.MODIFIER),
                KeySpec(if (chinesePunctuation) "，" else ",", context.getString(R.string.key_comma), if (literalPunctuation) KeyboardAction.LiteralText(",") else KeyboardAction.Text(","), 0.9f),
                KeySpec(context.getString(R.string.key_space), context.getString(R.string.key_space), KeyboardAction.Space, 3.8f),
                KeySpec(if (chinesePunctuation) "。" else ".", context.getString(R.string.key_period), if (literalPunctuation) KeyboardAction.LiteralText(".") else KeyboardAction.Text("."), 0.9f),
                KeySpec("↵", context.getString(R.string.key_enter), KeyboardAction.Enter, 1.35f, KeyStyle.PRIMARY),
            ),
        )
    }

    fun symbols(context: android.content.Context, page: Int): List<List<KeySpec>> {
        val entry = SymbolPages.entries[page]
        val values = SymbolPages.pages[page].map(::symbolKey)
        return listOf(
            values.take(10),
            values.drop(10).take(10),
            listOf(KeySpec("‹", context.getString(R.string.key_symbols), KeyboardAction.ShowSymbols,
                1.2f, KeyStyle.MODIFIER)) + values.drop(20) +
                KeySpec("⌫", context.getString(R.string.key_backspace), KeyboardAction.Backspace, 1.2f, KeyStyle.MODIFIER),
            symbolBottomRow(context).toMutableList().apply {
                this[2] = KeySpec(context.getString(SymbolPages.groups[entry.group].label),
                    context.getString(R.string.symbol_groups), KeyboardAction.ShowSymbolGroups, 2.6f, KeyStyle.MODIFIER)
                this[1] = KeySpec(context.getString(R.string.symbol_page, entry.number, entry.count),
                    context.getString(R.string.key_more_symbols), KeyboardAction.ShowMoreSymbols, 1.6f, KeyStyle.MODIFIER)
            },
        )
    }

    private fun characterRow(characters: String, shifted: Boolean): List<KeySpec> = characters.map { character ->
        val value = if (shifted) character.uppercase() else character.toString()
        KeySpec(value, value, KeyboardAction.Text(value))
    }

    private fun symbolRow(characters: String): List<KeySpec> = characters.map { symbolKey(it.toString()) }

    private fun symbolBottomRow(context: android.content.Context) = listOf(
        KeySpec("ABC", context.getString(R.string.key_letters), KeyboardAction.ShowLetters, 1.35f, KeyStyle.MODIFIER),
        KeySpec("123", context.getString(R.string.key_numbers), KeyboardAction.ShowNumbers, 1.1f, KeyStyle.MODIFIER),
        KeySpec(",", context.getString(R.string.key_comma), KeyboardAction.LiteralText(","), 0.9f),
        KeySpec(context.getString(R.string.key_space), context.getString(R.string.key_space), KeyboardAction.Space, 3.8f),
        KeySpec(".", context.getString(R.string.key_period), KeyboardAction.LiteralText("."), 0.9f),
        KeySpec("↵", context.getString(R.string.key_enter), KeyboardAction.Enter, 1.35f, KeyStyle.PRIMARY),
    )

    private fun symbolKey(value: String) = KeySpec(value, value, symbolAction(value))

    private fun symbolAction(value: String): KeyboardAction = when (value) {
        "(" -> KeyboardAction.PairedText("(", ")")
        "[" -> KeyboardAction.PairedText("[", "]")
        "{" -> KeyboardAction.PairedText("{", "}")
        "<" -> KeyboardAction.PairedText("<", ">")
        "（" -> KeyboardAction.PairedText("（", "）")
        "【" -> KeyboardAction.PairedText("【", "】")
        "《" -> KeyboardAction.PairedText("《", "》")
        "「" -> KeyboardAction.PairedText("「", "」")
        "『" -> KeyboardAction.PairedText("『", "』")
        "“" -> KeyboardAction.PairedText("“", "”")
        "‘" -> KeyboardAction.PairedText("‘", "’")
        "\"" -> KeyboardAction.PairedText("\"", "\"")
        "'" -> KeyboardAction.PairedText("'", "'")
        else -> KeyboardAction.LiteralText(value)
    }
}
