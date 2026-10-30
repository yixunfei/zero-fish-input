package dev.zeroinput.ime.ui

/** Public offline symbols, grouped into bounded pages: common, punctuation, Chinese,
 * mathematics, arrows and shapes, then units and phonetic marks. */
internal object SymbolPages {
    val pages: List<List<String>> = listOf(
        "1234567890@#¥_&-+()/*\"':;!?",
        "[]{}<>|\\~^%=€£$¢©®°…‰•§¶¤¬¦",
        "，。！？；：、·—…（）【】《》「」『』“”‘’〈〉～",
        "±×÷≠≈≡≤≥∞√∑∏∫∂∆∇∈∉⊂⊃⊆⊇∪∩∅∧∨",
        "←↑→↓↔↕↖↗↘↙⇒⇔➜✓✗★☆●○■□▲△▼▽◆◇",
        "℃℉Ωμ㎡㎥㎝㎜㎞㎏㎎㏄㏑㏒²³⁰¹⁴⁵⁶⁷⁸⁹½¼¾",
        "αβγδεζηθικλμνξοπρστυφχψωāáǎ",
        "àēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹêɡ",
    ).map { row -> row.map(Char::toString) }
}
