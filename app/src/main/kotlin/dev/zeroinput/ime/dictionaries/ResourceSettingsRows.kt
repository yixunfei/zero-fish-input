package dev.zeroinput.ime.dictionaries

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ime.R
import dev.zeroinput.languagepack.PublicResourceCatalog
import dev.zeroinput.languagepack.PublicResourcePack
import dev.zeroinput.languagepack.PublicResourceStatus
import dev.zeroinput.languagepack.PublicResourceStore
import java.io.File

/** Settings-only resource operations share the page's cancellable, bounded worker. */
internal class ResourceSettingsRows(
    private val context: Context,
    private val store: PublicResourceStore,
    private val transport: DictionaryDownloadTransport,
    private val content: LinearLayout,
    private val command: (String, () -> Unit) -> Unit,
    private val run: (() -> Unit, () -> Unit) -> Unit,
    private val refresh: () -> Unit,
    private val downloadProgress: () -> (Long, Long) -> Unit,
) {
    fun show(items: List<PublicResourceStatus>) {
        content.addView(TextView(context).apply { setText(R.string.public_resource_title); setPadding(0, 24, 0, 8) })
        items.forEach { item ->
            val title = title(item.pack.id)
            val state = when {
                item.downloaded -> R.string.public_resource_downloaded
                item.bundled -> R.string.public_resource_bundled
                else -> R.string.public_resource_missing
            }
            content.addView(TextView(context).apply {
                text = context.getString(R.string.public_resource_item, title, context.getString(state),
                    item.pack.files.sumOf { it.bytes } / 1024 / 1024)
                setPadding(0, 16, 0, 8)
            })
            content.addView(MaterialSwitch(context).apply {
                setText(R.string.public_dictionary_enable)
                isChecked = item.enabled && (item.downloaded || item.bundled)
                isEnabled = item.downloaded || item.bundled
                setOnCheckedChangeListener { _, enabled -> run({ store.setEnabled(item.pack.id, enabled) }, refresh) }
            })
            command(context.getString(R.string.public_resource_check)) { check(item) }
            if (item.downloaded) command(context.getString(R.string.public_dictionary_remove)) {
                MaterialAlertDialogBuilder(context).setMessage(R.string.public_resource_remove_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> run({ store.remove(item.pack.id) }, refresh) }.show()
            }
        }
    }

    private fun check(item: PublicResourceStatus) {
        MaterialAlertDialogBuilder(context).setTitle(title(item.pack.id))
            .setMessage(R.string.public_resource_network_notice).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.public_resource_check) { _, _ ->
                var available: PublicResourcePack? = null
                run({
                    val file = File.createTempFile("resource-catalog-", ".json", context.cacheDir)
                    try {
                        transport.download(PublicResourceCatalog.ADDRESS, file, PublicResourceCatalog.MAX_BYTES.toLong())
                        available = PublicResourceCatalog.parse(file.readText()).single { it.id == item.pack.id }
                    } finally { file.delete() }
                }, { confirmDownload(requireNotNull(available), item) })
            }.show()
    }

    private fun confirmDownload(pack: PublicResourcePack, previous: PublicResourceStatus) {
        if (pack.version == previous.pack.version && (previous.downloaded || previous.bundled)) {
            MaterialAlertDialogBuilder(context).setMessage(R.string.public_resource_current)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        MaterialAlertDialogBuilder(context).setTitle(title(pack.id))
            .setMessage(context.getString(R.string.public_resource_download_notice,
                pack.bytes / 1024 / 1024, pack.license))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.public_dictionary_download) { _, _ ->
                val progress = downloadProgress()
                run({
                    val file = File.createTempFile("resource-download-", ".zip", context.cacheDir)
                    try {
                        transport.download(pack.url, file, pack.bytes, timeoutMillis = 1_800_000, progress = progress)
                        store.install(pack, file)
                    } finally { file.delete() }
                }, refresh)
            }.show()
    }

    private fun title(id: String): String = context.getString(when (id) {
        "wanxiang-lts" -> R.string.public_resource_lts
        else -> R.string.public_resource_handwriting
    })
}
