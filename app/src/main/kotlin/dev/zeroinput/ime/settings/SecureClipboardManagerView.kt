package dev.zeroinput.ime.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.SecureClipboardMetadata

internal class SecureClipboardManagerView(context: Context) : LinearLayout(context) {
    var onBackRequested: () -> Unit = {}
    var onAddRequested: () -> Unit = {}
    var onEnableRequested: () -> Unit = {}
    var onDeleteRequested: (SecureClipboardMetadata) -> Unit = {}

    private val adapter = SecureClipboardAdapter { onDeleteRequested(it) }
    private val toolbar = createToolbar()
    private val list = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        adapter = this@SecureClipboardManagerView.adapter
        itemAnimator = null
        layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
    private val stateText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
    }
    private val enableButton = MaterialButton(context).apply {
        text = context.getString(R.string.enable_secure_clipboard)
        isAllCaps = false
        letterSpacing = 0f
        setOnClickListener { onEnableRequested() }
        filterTouchesWhenObscured = true
    }
    private val state = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(stateText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(enableButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(16) })
        layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        addView(FrameLayout(context).apply {
            addView(list)
            addView(state)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        renderLoading()
    }

    fun renderLoading() {
        adapter.submitList(emptyList())
        stateText.setText(R.string.authenticating)
        state.visibility = View.VISIBLE
        enableButton.visibility = View.GONE
        list.visibility = View.GONE
        toolbar.menu.findItem(MENU_ADD)?.isVisible = false
    }

    fun renderDisabled() {
        adapter.submitList(emptyList())
        stateText.setText(R.string.secure_clipboard_disabled)
        state.visibility = View.VISIBLE
        enableButton.visibility = View.VISIBLE
        list.visibility = View.GONE
        toolbar.menu.findItem(MENU_ADD)?.isVisible = false
    }

    fun renderEntries(items: List<SecureClipboardMetadata>) {
        adapter.submitList(items)
        val isEmpty = items.isEmpty()
        stateText.setText(R.string.secure_clipboard_empty)
        state.visibility = if (isEmpty) View.VISIBLE else View.GONE
        enableButton.visibility = View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
        toolbar.menu.findItem(MENU_ADD)?.isVisible = true
    }

    fun clearMetadata() {
        adapter.submitList(emptyList())
        stateText.setText(R.string.secure_clipboard_locked)
        state.visibility = View.VISIBLE
        enableButton.visibility = View.GONE
        list.visibility = View.GONE
    }

    fun setActionsEnabled(enabled: Boolean) {
        toolbar.menu.findItem(MENU_ADD)?.isEnabled = enabled
        enableButton.isEnabled = enabled
    }

    private fun createToolbar() = MaterialToolbar(context).apply {
        title = context.getString(R.string.secure_clipboard)
        setNavigationIcon(R.drawable.ic_arrow_back)
        navigationContentDescription = context.getString(R.string.navigate_up)
        setNavigationOnClickListener { onBackRequested() }
        menu.add(0, MENU_ADD, 0, R.string.add_secure_item).apply {
            setIcon(R.drawable.ic_add)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        setOnMenuItemClickListener {
            if (it.itemId == MENU_ADD) onAddRequested()
            it.itemId == MENU_ADD
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MENU_ADD = 1
    }
}
