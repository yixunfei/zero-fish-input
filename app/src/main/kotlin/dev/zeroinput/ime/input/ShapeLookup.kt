package dev.zeroinput.ime.input

import android.content.res.AssetManager
import android.os.Handler
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.ShapeChoice
import dev.zeroinput.ime.ui.ZeroInputView
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

internal class ShapeLookup(
    assets: AssetManager,
    private val main: Handler,
    private val commit: (ZeroInputView, String) -> Boolean,
) : AutoCloseable {
    private val worker = BoundedExecutors.singleThread("zeroinput-han-shapes", 1)
    private val index by lazy {
        assets.open("shape/han-shapes.tsv").bufferedReader().use(::HanShapeIndex)
    }
    private var view: ZeroInputView? = null
    private var task: Future<*>? = null
    private var generation = 0L
    private var query = ""
    private var seed: String? = null
    private var choices = RADICALS

    fun open(owner: ZeroInputView) {
        hide()
        view = owner
        render()
    }

    fun hide() {
        cancel()
        view = null
        query = ""
        seed = null
        choices = RADICALS
    }

    fun type(action: KeyboardAction) {
        if (view == null || seed != null) return
        val next = when (action) {
            KeyboardAction.Backspace -> query.dropLast(1)
            is KeyboardAction.Text -> (query + action.value.lowercase()).takeIf { value ->
                value.length <= 8 && value.all { it in 'a'..'z' }
            }
            else -> null
        }
        if (next != null && next != query) {
            query = next
            showSeeds()
        }
    }

    fun resetSelection() {
        if (seed == null) query = ""
        showSeeds()
    }

    private fun showSeeds() {
        if (view == null) return
        seed = null
        if (query.isEmpty()) {
            cancel()
            choices = RADICALS
            render()
        } else {
            val reading = query
            load { it.seeds(reading) }
        }
    }

    fun choose(position: Int, value: String) {
        val owner = view ?: return
        if (choices.getOrNull(position)?.character != value) return
        if (seed != null) {
            if (commit(owner, value)) owner.returnToKeyboard()
            return
        }
        val radical = query.isEmpty()
        seed = value
        load { it.similar(value, radical) }
    }

    private fun load(search: (HanShapeIndex) -> List<HanShapeIndex.Match>) {
        cancel()
        val revision = generation
        choices = emptyList()
        render(loading = true)
        try {
            task = worker.submit {
                val result = runCatching { search(index).map { ShapeChoice(it.character, it.pinyin) } }
                main.post {
                    if (generation != revision || view == null) return@post
                    task = null
                    choices = result.getOrDefault(emptyList())
                    render(failed = result.isFailure)
                }
            }
        } catch (_: RejectedExecutionException) {
            render(failed = true)
        }
    }

    private fun render(loading: Boolean = false, failed: Boolean = false) {
        view?.renderShapeLookup(query, seed, choices, loading, failed)
    }

    private fun cancel() {
        generation++
        task?.cancel(false)
        task = null
        BoundedExecutors.purge(worker)
    }

    override fun close() {
        hide()
        worker.shutdownNow()
    }

    private companion object {
        val RADICALS = (
            "人水木口手心言金火土日月目女竹艸虫石雨門辵糸貝食" +
            "一丨丶丿乙亅二亠儿入八冂冖冫几凵刀力勹匕匚匸十卜卩厂厶又囗士夂夊夕大子宀寸小尢尸屮山巛工己巾干幺广廴廾弋弓彐彡彳" +
            "戈戶支攴文斗斤方无曰欠止歹殳毋比毛氏气爪父爻爿片牙牛犬玄玉瓜瓦甘生用田疋疒癶白皮皿矛矢示禸禾穴立米缶网羊羽老而耒耳聿肉臣自至臼舌舛舟艮色虍血行衣襾見角谷豆豕豸赤走足身車辛辰邑酉釆里長阜隶隹青非面革韋韭音頁風飛首香馬骨高髟鬥鬯鬲鬼魚鳥鹵鹿麥麻黃黍黑黹黽鼎鼓鼠鼻齊齒龍龜龠"
        ).map { ShapeChoice(it.toString()) }.distinct()
    }
}
