package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.*

/** Small, immediately available engine used while the public native dictionary warms up. */
internal class FallbackPinyinEngine(
    options: ChineseInputOptions = ChineseInputOptions(),
) : InputEngine, CompositionEditingEngine {
    private val pageSize = options.candidatePageSize
    private val readingIndex = buildReadings(options)
    private val completionIndex = buildCompletions(readingIndex)
    private val syllableIndex = readingIndex.mapValues { (_, entries) ->
        entries.filter { (reading, _) -> reading !in compoundReadings }
    }.filterValues { it.isNotEmpty() }
    private val input = StringBuilder()
    private val segments = ArrayDeque<Segment>()
    private var page = 0
    private var syllableOnly = false
    private var choices = emptyList<Choice>()
    override var snapshot = EngineSnapshot.Empty
        private set

    override val descriptor = EngineDescriptor(
        id = "zeroinput.pinyin-fallback",
        displayName = "内置全拼（降级）",
        version = "3",
        languages = setOf(InputLanguage.CHINESE),
        isFallback = true,
        capabilities = setOf(
            EngineCapability.SEGMENT_SELECTION,
            EngineCapability.CANDIDATE_PAGE_SIZE,
            EngineCapability.FUZZY_PINYIN,
        ),
    )

    override fun start(context: EditorContext): EngineSnapshot = reset()

    override fun handle(key: EngineKey): EngineUpdate = when (key) {
        is EngineKey.Character -> character(key.text)
        EngineKey.Backspace -> backspace()
        EngineKey.Space -> if (snapshot.candidates.isNotEmpty()) selectCandidate(0) else commitLiteral(" ")
        EngineKey.Enter -> if (input.isNotEmpty()) commitRawInput()
            else EngineUpdate(snapshot, consumed = false)
    }

    override fun selectCandidate(index: Int): EngineUpdate {
        if (index !in snapshot.candidates.indices) return EngineUpdate(snapshot, consumed = false)
        val choice = choices.getOrNull(page * pageSize + index) ?: return EngineUpdate(snapshot, consumed = false)
        segments.addLast(Segment(choice.text, choice.reading, choice.consumed, input.length))
        if (consumedLength() >= input.length) {
            val text = segments.joinToString("") { it.text }
            val reading = segments.joinToString("") { it.reading }
            return EngineUpdate(reset(), text, committedInput = reading)
        }
        syllableOnly = false
        return refresh()
    }

    override fun changePage(direction: PageDirection): EngineUpdate {
        val target = page + if (direction == PageDirection.NEXT) 1 else -1
        if (target < 0 || target * pageSize >= choices.size) return EngineUpdate(snapshot, consumed = false)
        page = target
        return render()
    }

    override fun restoreComposition(input: String): EngineUpdate {
        if (input.length !in 1..MAX_INPUT || input.any { it !in 'a'..'z' && it != '\'' }) {
            return EngineUpdate(snapshot, consumed = false)
        }
        reset()
        this.input.append(input)
        return refresh()
    }

    override fun undoSelection(): EngineUpdate {
        if (segments.isEmpty()) return EngineUpdate(snapshot, consumed = false)
        segments.removeLast()
        syllableOnly = false
        return refresh()
    }

    override fun selectSyllable(): EngineUpdate {
        if (input.isEmpty()) return EngineUpdate(snapshot, consumed = false)
        syllableOnly = true
        return refresh()
    }

    override fun reset(): EngineSnapshot {
        input.clear()
        segments.clear()
        choices = emptyList()
        page = 0
        syllableOnly = false
        snapshot = EngineSnapshot.Empty
        return snapshot
    }

    override fun close() { reset() }

    private fun character(text: String): EngineUpdate {
        val letter = text.singleOrNull()?.lowercaseChar()
        if (letter != null && (letter in 'a'..'z' || letter == '\'')) {
            if (input.length == MAX_INPUT) return EngineUpdate(snapshot, consumed = false)
            input.append(letter)
            syllableOnly = false
            return refresh()
        }
        return commitLiteral(text)
    }

    private fun backspace(): EngineUpdate {
        if (input.isEmpty()) return EngineUpdate(snapshot, consumed = false)
        if (segments.lastOrNull()?.inputLength == input.length) return undoSelection()
        input.deleteCharAt(input.lastIndex)
        syllableOnly = false
        return refresh()
    }

    private fun commitLiteral(suffix: String): EngineUpdate {
        val value = segments.joinToString("") { it.text } + input.substring(consumedLength()) + suffix
        return EngineUpdate(reset(), value, learnable = false)
    }

    private fun commitRawInput(): EngineUpdate {
        val raw = input.toString()
        return EngineUpdate(reset(), raw, learnable = false)
    }

    private fun consumedLength(): Int = segments.sumOf { it.consumed }

    private fun refresh(): EngineUpdate {
        page = 0
        val remaining = input.substring(consumedLength())
        val leading = remaining.takeWhile { it == '\'' }.length
        val raw = remaining.drop(leading)
        val found = ArrayList<Choice>()
        if (!syllableOnly) {
            val compact = raw.replace("'", "")
            for ((reading, words) in completionIndex[compact].orEmpty()) {
                words.forEach { found += Choice(it, reading, remaining.length) }
            }
        }
        // A completion may consume the whole input, but only a complete
        // reading may consume a segment and leave a suffix to be converted.
        val prefixIndex = if (syllableOnly) syllableIndex else readingIndex
        val prefixes = (1..raw.length).filter { raw.substring(0, it).replace("'", "") in prefixIndex }
        val lengths = if (syllableOnly) prefixes.take(1) else prefixes.reversed()
        for (length in lengths) {
            val prefix = raw.substring(0, length).replace("'", "")
            val trailing = raw.drop(length).takeWhile { it == '\'' }.length
            for ((reading, words) in prefixIndex.getValue(prefix)) {
                words.forEach { found += Choice(it, reading, leading + length + trailing) }
            }
        }
        choices = found.distinctBy { it.text to it.consumed }
        return render()
    }

    private fun render(): EngineUpdate {
        val raw = input.toString()
        snapshot = EngineSnapshot(
            rawInput = raw,
            composition = segments.joinToString("") { it.text } + raw.drop(consumedLength()),
            candidates = choices.drop(page * pageSize).take(pageSize).mapIndexed { index, choice ->
                Candidate("fallback:${page * pageSize + index}", choice.text, choice.reading, input = choice.reading)
            },
            hasPreviousPage = page > 0,
            hasNextPage = (page + 1) * pageSize < choices.size,
            canUndoSelection = segments.isNotEmpty(),
            canSelectSyllable = raw.isNotEmpty(),
        )
        return EngineUpdate(snapshot)
    }

    private data class Segment(val text: String, val reading: String, val consumed: Int, val inputLength: Int)
    private data class Choice(val text: String, val reading: String, val consumed: Int)

    private companion object {
        const val MAX_INPUT = 128
        val phrases = mapOf(
            "a" to listOf("啊", "阿"), "ai" to listOf("爱", "哎", "唉"),
            "an" to listOf("安", "按", "案"), "ba" to listOf("吧", "八", "把"),
            "bei" to listOf("被", "北", "杯"), "bu" to listOf("不", "部", "步"),
            "chi" to listOf("吃", "持", "迟"), "de" to listOf("的", "得", "德"),
            "dui" to listOf("对", "队"), "fang" to listOf("方", "放", "房"),
            "ge" to listOf("个", "各", "歌"), "gong" to listOf("公", "工", "共"),
            "hao" to listOf("好", "号", "浩"), "he" to listOf("和", "喝", "合"),
            "hen" to listOf("很", "恨"), "hui" to listOf("会", "回", "灰"),
            "ji" to listOf("机", "几", "及"), "jia" to listOf("家", "加", "假"),
            "jian" to listOf("见", "件", "间"), "jin" to listOf("进", "今", "近"),
            "ke" to listOf("可", "科", "客"), "lai" to listOf("来", "莱"),
            "le" to listOf("了", "乐"), "ma" to listOf("吗", "妈", "马"),
            "mei" to listOf("没", "每", "美"), "men" to listOf("们", "门"),
            "ming" to listOf("明", "名"), "ni" to listOf("你", "呢", "尼"),
            "ren" to listOf("人", "认", "任"), "shi" to listOf("是", "时", "事"),
            "shuo" to listOf("说", "硕"), "ta" to listOf("他", "她", "它"),
            "wo" to listOf("我", "握"), "xiang" to listOf("想", "向", "像"),
            "xie" to listOf("些", "写", "谢"), "yi" to listOf("一", "以", "已"),
            "you" to listOf("有", "又", "由"), "zai" to listOf("在", "再"),
            "zhe" to listOf("这", "着", "者"), "zhong" to listOf("中", "种", "重"),
            "nihao" to listOf("你好"), "xiexie" to listOf("谢谢"),
            "women" to listOf("我们"), "zhongguo" to listOf("中国"),
            "keyi" to listOf("可以"), "meiyou" to listOf("没有"),
            "zaijian" to listOf("再见"), "shijie" to listOf("世界"),
        )

        private val compoundReadings = setOf(
            "nihao", "xiexie", "women", "zhongguo", "keyi", "meiyou", "zaijian", "shijie",
        )

        private fun buildReadings(options: ChineseInputOptions): Map<String, List<Pair<String, List<String>>>> {
            val syllables = phrases.keys - compoundReadings
            return buildMap {
                for ((reading, values) in phrases) {
                    val entry = reading to values
                    for (variant in FuzzyPinyinMatcher.variants(reading, options, syllables)) {
                        val current = get(variant).orEmpty()
                        if (entry !in current) put(variant, current + entry)
                    }
                }
            }
        }

        private fun buildCompletions(
            readings: Map<String, List<Pair<String, List<String>>>>,
        ): Map<String, List<Pair<String, List<String>>>> = buildMap {
            for ((variant, entries) in readings) for (length in 1..variant.length) {
                val prefix = variant.take(length)
                put(prefix, (get(prefix).orEmpty() + entries).distinctBy { it.first })
            }
        }
    }
}
