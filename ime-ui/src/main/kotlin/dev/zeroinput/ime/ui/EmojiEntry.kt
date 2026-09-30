package dev.zeroinput.ime.ui

import androidx.annotation.StringRes
import java.util.Locale

enum class EmojiCategory(val marker: String, @StringRes val label: Int) {
    RECENT("◷", R.string.expression_recent),
    FAVORITES("☆", R.string.expression_favorites),
    KAOMOJI("^_^", R.string.expression_kaomoji),
    CUSTOM("＋", R.string.expression_custom),
    SMILEYS("☺", R.string.expression_smileys),
    PEOPLE("☝", R.string.expression_people),
    NATURE("♣", R.string.expression_nature),
    FOOD("●", R.string.expression_food),
    ACTIVITY("★", R.string.expression_activity),
    TRAVEL("✈", R.string.expression_travel),
    OBJECTS("◆", R.string.expression_objects),
    SYMBOLS("♥", R.string.expression_symbols),
    FLAGS("⚑", R.string.expression_flags),
}

enum class KaomojiGroup(@StringRes val label: Int, val keywords: String) {
    HAPPY(R.string.expression_happy, "开心 開心 快乐 笑 happy joy smile kaixin kuaile xiao"),
    LOVE(R.string.expression_love, "喜欢 喜歡 爱 愛 害羞 love hug shy xihuan ai haixiu"),
    SAD(R.string.expression_sad, "伤心 傷心 难过 哭 sad cry shangxin nanguo ku"),
    ANGRY(R.string.expression_angry, "生气 生氣 愤怒 angry rage shengqi fennu"),
    SURPRISED(R.string.expression_surprised, "惊讶 驚訝 震惊 surprise shock jingya zhenjing"),
    SHRUG(R.string.expression_shrug, "无奈 無奈 无语 摊手 躺平 shrug whatever wunai wuyu tangping"),
    GREETINGS(R.string.expression_greetings, "问候 問候 你好 再见 hello hi bye greetings wenhou nihao zaijian"),
    CHEER(R.string.expression_cheer, "加油 鼓励 庆祝 cheer celebrate jiayou guli qingzhu"),
    SLEEP(R.string.expression_sleep, "睡觉 睡覺 晚安 困 sleep night shuijiao wanan kun"),
    OTHER(R.string.expression_other, "其他 other qita"),
}

data class EmojiEntry(
    val value: String,
    val category: EmojiCategory,
    val keywords: String,
    val group: KaomojiGroup? = null,
    val customId: String? = null,
    val name: String = keywords.substringBefore(' '),
    val englishName: String = name,
    val artworkKey: String? = null,
    val variantKey: String? = null,
    val isComponent: Boolean = false,
) {
    val isWide: Boolean get() = group != null || customId != null
    internal val searchText = "$value $name $keywords ${group?.keywords.orEmpty()}".lowercase(Locale.ROOT)
    internal fun displayName(locale: Locale): String = if (locale.language == "zh") name else englishName
}

data class PersonalExpressionsUi(
    val custom: List<EmojiEntry> = emptyList(),
    val favorites: Set<String> = emptySet(),
)
