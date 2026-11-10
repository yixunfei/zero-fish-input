package dev.zeroinput.ime.management

import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.R
import dev.zeroinput.ime.dictionaries.dictionarySourceLabel
import dev.zeroinput.ime.expressions.ExpressionManagerAdapter
import dev.zeroinput.ime.settings.SecureClipboardManagerView
import dev.zeroinput.ime.settings.SecureClipboardAdapter
import dev.zeroinput.ime.settings.UserDictionaryAdapter
import dev.zeroinput.userdata.PersonalExpression
import dev.zeroinput.userdata.SecureClipboardMetadata
import dev.zeroinput.userdata.UserTerm
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ManagementFeedbackTest {
    @Test fun failureRetryIsRemovedAsSoonAsAnotherReadStartsOrThePageClears() {
        val view = ManagementStatusView(context("en"))
        var reads = 0
        val retry = view.findViewById<View>(R.id.management_status_retry)
        view.failure(R.string.management_load_failed) { reads++ }
        retry.performClick()
        assertEquals(1, reads)
        view.loading()
        retry.performClick()
        assertEquals(1, reads)
        assertEquals(View.GONE, retry.visibility)
        view.clear()
        assertEquals(View.GONE, view.visibility)
        assertEquals("", view.findViewById<TextView>(R.id.management_status_message).text.toString())
    }

    @Test fun feedbackAndSourceNamesFollowTheSelectedLanguage() {
        for (language in listOf("en", "zh")) {
            val ctx = context(language)
            val view = ManagementStatusView(ctx)
            view.failure(R.string.management_operation_failed) {}
            val text = view.findViewById<TextView>(R.id.management_status_message).text.toString()
            assertEquals(language == "en", text.startsWith("The operation"))
            assertEquals(ctx.getString(R.string.public_dictionary_wanxiang), dictionarySourceLabel(ctx, "WANXIANG"))
            assertEquals(ctx.getString(R.string.dictionary_source_other), dictionarySourceLabel(ctx, "untrusted fixture"))
        }
    }

    @Test fun unchangedExpressionRowsRemainVisibleAfterRefreshAndOldClicksAreRevoked() {
        val parent = androidx.recyclerview.widget.RecyclerView(context("en"))
        parent.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(parent.context)
        var edits = 0
        val adapter = ExpressionManagerAdapter({ edits++ }, {})
        parent.adapter = adapter
        val entries = listOf(PersonalExpression("fixture", "(public)", "Public fixture", "hello", "happy"))
        adapter.submit(entries)
        measure(parent)
        val holder = parent.findViewHolderForAdapterPosition(0) as ExpressionManagerAdapter.Holder
        assertEquals("(public)", holder.value.text.toString())
        adapter.submit(entries)
        measure(parent)
        val refreshed = parent.findViewHolderForAdapterPosition(0) as ExpressionManagerAdapter.Holder
        assertEquals("(public)", refreshed.value.text.toString())
        adapter.submit(emptyList())
        refreshed.texts.performClick()
        assertEquals(0, edits)
        assertEquals("", refreshed.value.text.toString())
    }

    @Test fun personalPhraseRowsWipeImmediatelyWhenTheirSnapshotIsRetired() {
        val parent = LinearLayout(context("en"))
        var deletes = 0
        val adapter = UserDictionaryAdapter(onDelete = { deletes++ })
        adapter.submit(listOf(UserTerm("fixture", "cs", "Public fixture", InputLanguage.CHINESE, 3, 0)))
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        adapter.submit(emptyList())
        holder.remove.performClick()
        assertEquals(0, deletes)
        assertEquals("", holder.value.text.toString())
        assertNull(holder.remove.contentDescription)
    }

    @Test fun clipboardFailureHidesOldMetadataAndRetryRequiresTheAuthenticationEntry() {
        val ctx = context("en")
        val view = SecureClipboardManagerView(ctx)
        view.renderEntries(listOf(SecureClipboardMetadata("fixture", "Public label", 4, 0)))
        measure(view)
        var reauthentications = 0
        view.onReloadRequested = { reauthentications++ }
        view.renderFailure(R.string.secure_clipboard_load_failed)
        measure(view)
        val descendants = descendants(view)
        assertFalse(descendants.filterIsInstance<TextView>().any { it.text == "Public label" })
        descendants.filterIsInstance<TextView>().single { it.text == ctx.getString(R.string.management_reload) }.performClick()
        assertEquals(1, reauthentications)
    }

    @Test fun clipboardDatesUseThePageLanguageEvenWhenTheSystemLanguageDiffers() {
        val texts = listOf("en", "zh").map { language ->
            val adapter = SecureClipboardAdapter {}
            adapter.submitList(listOf(SecureClipboardMetadata("fixture", "Public label", 4, 0)))
            val holder = adapter.onCreateViewHolder(LinearLayout(context(language)), 0)
            adapter.onBindViewHolder(holder, 0)
            holder.details.text.toString()
        }
        assertTrue(texts[0].contains("Jan"))
        assertTrue(texts[1].contains("年"))
        assertFalse(texts[1].contains("Jan"))
    }

    private fun context(language: String): ContextThemeWrapper {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = 2f }
        return ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_ZeroInput)
    }
    private fun measure(view: View) {
        val density = view.resources.displayMetrics.density
        view.forceLayout()
        view.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((600 * density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is android.view.ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
