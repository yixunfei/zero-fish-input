package dev.zeroinput.ime.dictionaries

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.net.toUri
import com.google.android.material.appbar.MaterialToolbar
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.ime.management.ManagementStatusView
import dev.zeroinput.ime.management.ManagementTaskRunner
import dev.zeroinput.languagepack.InstalledPublicDictionary

/** Foreground native catalog UI. Leaving the page cancels its outstanding network/parse request. */
class DictionaryManagerActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private val worker = BoundedExecutors.singleThread("public-dictionaries", 1)
    private val transport = DictionaryDownloadTransport()
    private val catalog by lazy { DictionaryCatalog(cacheDir, transport) }
    private val downloads by lazy { DictionaryDownloads(this, transport, catalog, graph.publicDictionaries, graph.builtInDictionaries) }
    private val management by lazy { DictionaryManagementRows(this, graph, ::command,
        { work, complete -> runTask(work) { complete() } }, { changed(); showInstalled() }, ::confirmRedownload) }
    private lateinit var content: LinearLayout
    private lateinit var status: ManagementStatusView
    private lateinit var progress: ProgressBar
    private lateinit var cancelButton: MaterialButton
    private lateinit var scroll: ScrollView
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private var busy = false
    private var taskFailure = R.string.management_operation_failed
    private val tasks by lazy { ManagementTaskRunner(worker, { runOnUiThread(it) },
        { !isFinishing && !isDestroyed && !refreshAfterStop }, { value ->
            busy = value
            progress.visibility = if (value) View.VISIBLE else View.GONE
            cancelButton.visibility = if (value) View.VISIBLE else View.GONE
            content.visibility = if (value) View.GONE else View.VISIBLE
        }, {
            content.removeAllViews()
            status.failure(taskFailure) { showInstalled() }
            android.widget.Toast.makeText(this, taskFailure, android.widget.Toast.LENGTH_LONG).show()
        }) }
    private var generation = 0L
    private var dirty = false
    private var currentSource: DictionarySource? = null
    private val history = java.util.ArrayDeque<CatalogPage>()
    private var visibleCatalog: CatalogPage? = null
    private var refreshAfterStop = false
    private var revocationObserver: AutoCloseable? = null
    private var editEpoch = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
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
        status = ManagementStatusView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(progress)
        }
        val controls = LinearLayout(this)
        controls.addView(button(R.string.public_dictionary_home) { cancel(); showInstalled() })
        cancelButton = button(R.string.public_dictionary_cancel, ::cancelAndReloadInstalled).apply {
            visibility = View.GONE
        }
        controls.addView(cancelButton)
        body.addView(controls)
        content = LinearLayout(this).apply {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        body.addView(content)
        scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancel()
                val page = visibleCatalog
                if (page?.navigation == true && page.downloads.isNotEmpty()) {
                    renderCatalog(page.copy(navigation = false, offset = 0))
                } else if (history.isNotEmpty()) renderCatalog(history.removeLast())
                else if (currentSource != null) showInstalled() else finish()
            }
        })
        showInstalled()
    }

    private fun showInstalled(feedback: Int? = null) {
        history.clear()
        visibleCatalog = null
        currentSource = null
        runTask({ InstalledView(graph.publicDictionaries.list(), graph.publicResources.list(),
            graph.builtInDictionaries.list(), graph.dictionaryEdits.read().names) },
            failure = R.string.management_load_failed, loading = feedback ?: R.string.public_dictionary_working) { snapshot ->
            val installed = snapshot.downloaded
            val resources = snapshot.resources
            content.removeAllViews()
            label(getString(R.string.public_dictionary_builtin))
            label(getString(R.string.dictionary_management_notice))
            snapshot.builtin.forEach { management.builtIn(it, snapshot.names) }
            ResourceSettingsRows(this, graph.publicResources, transport, content, ::command,
                { work, complete -> runTask(work) { complete() } }, { changed(); showInstalled() },
                ::downloadProgress, ::showDialog).show(resources)
            command(getString(R.string.public_dictionary_restore)) {
                showDialog(MaterialAlertDialogBuilder(this).setMessage(R.string.public_dictionary_restore_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runTask({ graph.publicDictionaries.disableAll(); graph.builtInDictionaries.restoreAll() }) {
                            changed(); showInstalled()
                        }
                    }.create())
            }
            label(getString(R.string.public_dictionary_sources))
            DictionarySource.entries.forEach { source ->
                command(sourceLabel(source)) { confirmBrowse(source) }
            }
            label(getString(R.string.public_dictionary_installed))
            installed.filterNot { it.id.startsWith("builtin_") }.forEach { management.downloaded(it, snapshot.names) }
            val message = feedback ?: if (dirty) R.string.public_dictionary_pending else R.string.public_dictionary_offline
            status.show(getString(message))
        }
    }

    private fun confirmRedownload(table: String?, item: InstalledPublicDictionary?) {
        showDialog(MaterialAlertDialogBuilder(this).setMessage(R.string.dictionary_redownload_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.dictionary_redownload) { _, _ ->
                val progress = downloadProgress()
                runTask({
                    if (table != null) downloads.redownloadBuiltIn(table, progress)
                    else downloads.redownload(requireNotNull(item), progress)
                }, failure = R.string.public_dictionary_failed) { changed(); showInstalled() }
            }.create())
    }

    private fun confirmBrowse(source: DictionarySource) {
        showDialog(MaterialAlertDialogBuilder(this).setTitle(sourceLabel(source)).setMessage(R.string.public_dictionary_network_notice)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.public_dictionary_browse) { _, _ -> browse(source, null) }.create())
    }

    private fun browse(source: DictionarySource, page: String?) {
        val previous = visibleCatalog?.takeIf { it.source == source }
        if (previous != null && previous.address == page) return
        currentSource = source
        runTask({ catalog.list(source, page) }, failure = R.string.public_dictionary_failed) { rows ->
            if (previous != null) {
                if (history.size == MAX_CATALOG_HISTORY) history.removeFirst()
                history.addLast(previous)
            }
            val (navigation, downloads) = rows.partition(CatalogItem::category)
            renderCatalog(CatalogPage(source, page, downloads, navigation, navigation = downloads.isEmpty()))
        }
    }

    private fun renderCatalog(page: CatalogPage) {
        visibleCatalog = page
        currentSource = page.source
        val source = page.source
        val rows = if (page.navigation) page.links else page.downloads
        val offset = page.offset
        content.removeAllViews()
        label(sourceLabel(source))
        label(getString(when (source) {
            DictionarySource.WANXIANG -> R.string.public_dictionary_license_wanxiang
            DictionarySource.ICE -> R.string.public_dictionary_license_ice
            DictionarySource.ZHWIKI -> R.string.public_dictionary_license_wiki
            else -> R.string.public_dictionary_license_website
        }))
        command(getString(R.string.public_dictionary_visit_source)) { visitSource(source) }
        if (page.address != null) command(getString(R.string.public_dictionary_catalog_home)) { browse(source, null) }
        if (page.links.isNotEmpty() && page.downloads.isNotEmpty()) {
            val toggle = if (page.navigation) R.string.public_dictionary_show_downloads else R.string.public_dictionary_categories
            command(getString(toggle)) { renderCatalog(page.copy(navigation = !page.navigation, offset = 0)) }
        }
        if (offset > 0) command(getString(R.string.public_dictionary_previous_page)) {
            renderCatalog(page.copy(offset = offset - CATALOG_PAGE_SIZE))
        }
        rows.subList(offset, minOf(offset + CATALOG_PAGE_SIZE, rows.size)).forEach { item ->
            if (item.category) command(item.title) { browse(source, item.address) }
            else command(getString(R.string.public_dictionary_download_item, item.title)) { confirmInstall(source, item) }
        }
        if (offset + CATALOG_PAGE_SIZE < rows.size) command(getString(R.string.public_dictionary_next_page)) {
            renderCatalog(page.copy(offset = offset + CATALOG_PAGE_SIZE))
        }
        scroll.scrollTo(0, 0)
        status.show(getString(when {
            rows.isEmpty() -> R.string.public_dictionary_no_downloads
            page.navigation && page.downloads.isEmpty() -> R.string.public_dictionary_choose_category
            page.navigation -> R.string.public_dictionary_choose_navigation
            else -> R.string.public_dictionary_choose_download
        }))
    }

    private fun visitSource(source: DictionarySource) {
        val uri = DictionaryDownloadTransport.validate(source.website).toASCIIString().toUri()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            status.show(getString(R.string.public_dictionary_browser_unavailable))
        }
    }

    private fun confirmInstall(source: DictionarySource, item: CatalogItem) {
        showDialog(MaterialAlertDialogBuilder(this).setTitle(item.title).setMessage(R.string.public_dictionary_install_notice)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.public_dictionary_download) { _, _ -> install(source, item) }.create())
    }

    private fun install(source: DictionarySource, item: CatalogItem) {
        val progress = downloadProgress()
        runTask({ downloads.install(source, item, progress) }, failure = R.string.public_dictionary_failed,
            loading = R.string.public_dictionary_importing) { changed(); showInstalled() }
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
                        status.show(getString(R.string.public_dictionary_progress, received / 1024))
                        progress.isIndeterminate = total <= 0
                        if (total > 0) progress.progress = (received * 100 / total).toInt()
                    }
                }
            }
        }
    }

    private fun <T> runTask(work: () -> T, failure: Int = R.string.management_operation_failed,
        loading: Int = R.string.public_dictionary_working, complete: (T) -> Unit) {
        if (busy) {
            android.widget.Toast.makeText(this, R.string.public_dictionary_working, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        closeDialogs()
        cancel()
        taskFailure = failure
        progress.isIndeterminate = true
        status.show(getString(loading))
        tasks.execute({ work() }) { result ->
            status.clear()
            complete(result)
        }
    }

    private fun cancelAndReloadInstalled() {
        if (!busy) return
        cancel()
        management.close()
        showInstalled(R.string.public_dictionary_cancelled)
    }

    private fun cancel() {
        generation++
        tasks.cancel()
    }

    private fun showDialog(value: androidx.appcompat.app.AlertDialog) {
        closeDialogs()
        dialog = value
        value.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        value.show()
    }

    private fun closeDialogs() {
        dialog?.dismiss()
        dialog = null
        management.close()
    }
    private fun changed() { dirty = true }
    private fun label(text: String) { content.addView(TextView(this).apply { this.text = text; setPadding(0, dp(16), 0, dp(8)) }) }
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
            setOnClickListener { if (!busy) action() }
        })
    }
    private fun button(resource: Int, action: () -> Unit) = MaterialButton(this).apply {
        setText(resource)
        isAllCaps = false
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun sourceLabel(source: DictionarySource): String = getString(when (source) {
        DictionarySource.WANXIANG -> R.string.public_dictionary_wanxiang
        DictionarySource.ICE -> R.string.public_dictionary_ice
        DictionarySource.ZHWIKI -> R.string.public_dictionary_wiki
        DictionarySource.QQ -> R.string.public_dictionary_qq
        DictionarySource.SOGOU -> R.string.public_dictionary_sogou
    })

    private data class InstalledView(
        val downloaded: List<InstalledPublicDictionary>,
        val resources: List<dev.zeroinput.languagepack.PublicResourceStatus>,
        val builtin: List<dev.zeroinput.languagepack.BuiltInDictionary>,
        val names: Map<String, String>,
    )

    private data class CatalogPage(
        val source: DictionarySource,
        val address: String?,
        val downloads: List<CatalogItem>,
        val links: List<CatalogItem>,
        val navigation: Boolean,
        val offset: Int = 0,
    )

    override fun onStart() {
        super.onStart()
        editEpoch = graph.dictionaryEdits.generation()
        revocationObserver = graph.addPersonalizationListener {
            runOnUiThread {
                if (revocationObserver != null && editEpoch != graph.dictionaryEdits.generation()) {
                    editEpoch = graph.dictionaryEdits.generation()
                    cancel()
                    management.close(); content.removeAllViews(); showInstalled()
                }
            }
        }
        if (refreshAfterStop) { refreshAfterStop = false; showInstalled() }
    }
    override fun onStop() {
        revocationObserver?.close(); revocationObserver = null
        refreshAfterStop = true
        cancel()
        closeDialogs()
        content.removeAllViews()
        super.onStop()
    }
    override fun onDestroy() { tasks.close(); worker.shutdownNow(); super.onDestroy() }

    private companion object {
        const val CATALOG_PAGE_SIZE = 64
        const val MAX_CATALOG_HISTORY = 16
    }
}
