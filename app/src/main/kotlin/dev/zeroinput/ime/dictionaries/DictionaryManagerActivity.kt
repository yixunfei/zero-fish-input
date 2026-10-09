package dev.zeroinput.ime.dictionaries

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.engine.dictionary.importer.CellDictionaryParser
import dev.zeroinput.engine.dictionary.importer.RimeTextDictionaryParser
import dev.zeroinput.languagepack.InstalledPublicDictionary
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.security.MessageDigest

/** Foreground native catalog UI. Leaving the page cancels its outstanding network/parse request. */
class DictionaryManagerActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private val worker = BoundedExecutors.singleThread("public-dictionaries", 1)
    private val transport = DictionaryDownloadTransport()
    private val catalog by lazy { DictionaryCatalog(cacheDir, transport) }
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var pending: Future<*>? = null
    private var generation = 0L
    private var dirty = false
    private var currentSource: DictionarySource? = null
    private var currentPage: String? = null
    private val history = java.util.ArrayDeque<Pair<DictionarySource, String?>>()
    private var refreshAfterStop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.public_dictionary_title)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addView(MaterialToolbar(this).apply {
            setTitle(R.string.public_dictionary_title)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = getString(R.string.navigate_up)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        status = TextView(this).apply { setPadding(24, 16, 24, 16) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(status)
        root.addView(progress)
        val controls = LinearLayout(this)
        controls.addView(button(R.string.public_dictionary_home) { showInstalled() })
        controls.addView(button(R.string.public_dictionary_cancel) { cancel(); status.setText(R.string.public_dictionary_cancelled) })
        root.addView(controls)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 24) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (history.isNotEmpty()) {
                    val (source, page) = history.removeLast()
                    browse(source, page, remember = false)
                } else if (currentSource != null) showInstalled() else finish()
            }
        })
        showInstalled()
    }

    private fun showInstalled() {
        history.clear()
        currentSource = null
        currentPage = null
        runTask({ graph.publicDictionaries.list() to graph.publicResources.list() }) { (installed, resources) ->
            content.removeAllViews()
            label(getString(R.string.public_dictionary_builtin))
            label(getString(R.string.public_dictionary_builtin_detail))
            ResourceSettingsRows(this, graph.publicResources, transport, content, ::command,
                { work, complete -> runTask(work) { complete() } }, { changed(); showInstalled() },
                ::downloadProgress).show(resources)
            command(getString(R.string.public_dictionary_restore)) {
                MaterialAlertDialogBuilder(this).setMessage(R.string.public_dictionary_restore_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runTask({ graph.publicDictionaries.disableAll() }) {
                            changed(); showInstalled()
                        }
                    }.show()
            }
            label(getString(R.string.public_dictionary_sources))
            DictionarySource.entries.forEach { source ->
                command(sourceLabel(source)) { confirmBrowse(source) }
            }
            label(getString(R.string.public_dictionary_installed))
            installed.forEach(::installedRow)
            status.setText(if (dirty) R.string.public_dictionary_pending else R.string.public_dictionary_offline)
        }
    }

    private fun installedRow(item: InstalledPublicDictionary) {
        label(getString(R.string.public_dictionary_item, item.title, item.source, item.entries,
            item.bytes / 1024, item.version.take(12)))
        content.addView(MaterialSwitch(this).apply {
            setText(R.string.public_dictionary_enable)
            isChecked = item.enabled
            setOnCheckedChangeListener { _, enabled ->
                runTask({ graph.publicDictionaries.setEnabled(item.id, enabled) }) { changed(); showInstalled() }
            }
        })
        command(getString(R.string.public_dictionary_update)) { confirmBrowse(DictionarySource.valueOf(item.source)) }
        command(getString(R.string.public_dictionary_remove)) {
            MaterialAlertDialogBuilder(this).setMessage(getString(R.string.public_dictionary_remove_confirm, item.title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runTask({ graph.publicDictionaries.remove(item.id) }) { changed(); showInstalled() }
                }.show()
        }
    }

    private fun confirmBrowse(source: DictionarySource) {
        MaterialAlertDialogBuilder(this).setTitle(sourceLabel(source)).setMessage(R.string.public_dictionary_network_notice)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.public_dictionary_browse) { _, _ -> browse(source, null) }.show()
    }

    private fun browse(source: DictionarySource, page: String?, remember: Boolean = true) {
        if (remember && currentSource == source && currentPage != page) history.addLast(source to currentPage)
        currentSource = source
        currentPage = page
        runTask({ catalog.list(source, page) }) { rows ->
            content.removeAllViews()
            label(sourceLabel(source))
            label(getString(when (source) {
                DictionarySource.WANXIANG -> R.string.public_dictionary_license_wanxiang
                DictionarySource.ICE -> R.string.public_dictionary_license_ice
                DictionarySource.ZHWIKI -> R.string.public_dictionary_license_wiki
                else -> R.string.public_dictionary_license_website
            }))
            command(getString(R.string.public_dictionary_categories)) { history.clear(); browse(source, null, false) }
            rows.forEach { item -> command(item.title) {
                if (item.category) browse(source, item.address) else confirmInstall(source, item)
            } }
            status.setText(R.string.public_dictionary_choose)
        }
    }

    private fun confirmInstall(source: DictionarySource, item: CatalogItem) {
        MaterialAlertDialogBuilder(this).setTitle(item.title).setMessage(R.string.public_dictionary_install_notice)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.public_dictionary_download) { _, _ -> install(source, item) }.show()
    }

    private fun install(source: DictionarySource, item: CatalogItem) {
        val downloadProgress = downloadProgress()
        runTask({
            val address = catalog.resolve(source, item)
            val file = File.createTempFile("public-download-", ".tmp", cacheDir)
            try {
                transport.download(address, file, 128L * 1024 * 1024, progress = downloadProgress)
                val parser = when (item.format) {
                    "qq" -> CellDictionaryParser(CellDictionaryParser.Source.QQ)
                    "scel" -> CellDictionaryParser(CellDictionaryParser.Source.SOGOU)
                    else -> RimeTextDictionaryParser()
                }
                val id = source.name.lowercase() + "_" + MessageDigest.getInstance("SHA-256")
                    .digest(item.id.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
                file.inputStream().use {
                    graph.publicDictionaries.install(id, item.title, source.name, item.version, it, parser)
                }
            } finally { file.delete() }
        }) { changed(); showInstalled() }
    }

    private fun downloadProgress(): (Long, Long) -> Unit {
        val token = generation + 1
        var lastProgress = 0L
        return { received, total ->
            val now = System.nanoTime()
            if (now - lastProgress > 200_000_000L) {
                lastProgress = now
                runOnUiThread {
                    if (token == generation && !isDestroyed) {
                        status.text = getString(R.string.public_dictionary_progress, received / 1024)
                        progress.isIndeterminate = total <= 0
                        if (total > 0) progress.progress = (received * 100 / total).toInt()
                    }
                }
            }
        }
    }

    private fun <T> runTask(work: () -> T, complete: (T) -> Unit) {
        cancel()
        val token = generation
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.setText(R.string.public_dictionary_working)
        try {
            pending = worker.submit {
                val result = runCatching(work)
                runOnUiThread {
                    if (token == generation && !isDestroyed) {
                        pending = null
                        progress.visibility = View.GONE
                        result.fold(complete) { status.setText(R.string.public_dictionary_failed) }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            progress.visibility = View.GONE
            status.setText(R.string.public_dictionary_failed)
        }
    }

    private fun cancel() { generation++; pending?.cancel(true); pending = null; progress.visibility = View.GONE }
    private fun changed() { dirty = true }
    private fun label(text: String) { content.addView(TextView(this).apply { this.text = text; setPadding(0, 16, 0, 8) }) }
    private fun command(text: String, action: () -> Unit) {
        content.addView(TextView(this).apply {
            this.text = text
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            isFocusable = true
            val value = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            setBackgroundResource(value.resourceId)
            setOnClickListener { action() }
        })
    }
    private fun button(resource: Int, action: () -> Unit) = MaterialButton(this).apply {
        setText(resource); setOnClickListener { action() }
    }
    private fun sourceLabel(source: DictionarySource): String = getString(when (source) {
        DictionarySource.WANXIANG -> R.string.public_dictionary_wanxiang
        DictionarySource.ICE -> R.string.public_dictionary_ice
        DictionarySource.ZHWIKI -> R.string.public_dictionary_wiki
        DictionarySource.QQ -> R.string.public_dictionary_qq
        DictionarySource.SOGOU -> R.string.public_dictionary_sogou
    })

    override fun onStart() {
        super.onStart()
        if (refreshAfterStop) { refreshAfterStop = false; showInstalled() }
    }
    override fun onStop() { refreshAfterStop = true; cancel(); super.onStop() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
