package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlin.math.ceil
import dev.zeroinput.engine.api.PageDirection
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class EmojiPanelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var onEmojiSelected: (EmojiEntry) -> Unit = {}
    var onFavoriteRequested: (EmojiEntry, Boolean) -> Unit = { _, _ -> }
    var onManageRequested: (String?) -> Unit = {}
    var onSearchModeChanged: (Boolean) -> Unit = {}
    var onUserInteraction: () -> Unit = {}

    private val state = ExpressionBrowserState()
    private var preparation: Closeable? = null
    private var preparationFailed = false
    private var filterWorker: ThreadPoolExecutor? = null
    private var filterTask: Future<*>? = null
    private var renderGeneration = 0L
    val isSearchActive: Boolean get() = state.searchActive
    private val categoryButtons = linkedMapOf<EmojiCategory, MaterialButton>()
    private val groupButtons = linkedMapOf<KaomojiGroup?, MaterialButton>()
    private var colorsResolved = false
    private var primaryTextColor = 0
    private var onSurfaceTextColor = 0
    private var selectedBackgroundColor = 0
    private val iconButtons = mutableListOf<AppCompatImageButton>()
    private val adapter = EmojiAdapter({ onEmojiSelected(it) }, { entry, selected -> onFavoriteRequested(entry, selected) },
        { onManageRequested(it) }, { onUserInteraction() })
    private val queryLabel = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        isSaveEnabled = false
        layoutParams = LayoutParams(0, dp(48), 1f)
    }
    private val searchButton = icon(R.drawable.ic_expression_search, R.string.expression_search) { toggleSearch() }
    private val manageButton = icon(R.drawable.ic_expression_add, R.string.expression_manage) { onManageRequested(null) }
    private val gridLayout = GridLayoutManager(context, 8)
    private val grid = RecyclerView(context).apply {
        layoutManager = gridLayout
        adapter = this@EmojiPanelView.adapter
        itemAnimator = null
        overScrollMode = OVER_SCROLL_NEVER
        isSaveEnabled = false
    }
    private val emptyLabel = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        setPadding(dp(12), 0, dp(12), 0)
    }
    private val groups = createGroups()
    private val categories = createCategories()
    private val browser = HorizontalSwipeFrameLayout(context).apply {
        canSwipe = { direction -> adjacentCategory(direction) != null }
        onSwipe = { direction ->
            onUserInteraction()
            adjacentCategory(direction)?.let(::selectCategory)
        }
    }

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        addView(createSearchRow())
        addView(groups)
        addView(browser.apply {
            addView(grid, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(emptyLabel, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(categories)
        gridLayout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            private val paint = Paint().apply { textSize = 18 * resources.displayMetrics.scaledDensity }
            override fun getSpanSize(position: Int): Int {
                val entry = adapter.entry(position) ?: return 1
                if (!entry.isWide) return 1
                val cell = (grid.width / gridLayout.spanCount).coerceAtLeast(dp(48))
                return ceil((paint.measureText(entry.value) + dp(40)) / cell).toInt()
                    .coerceIn(minOf(3, gridLayout.spanCount), gridLayout.spanCount)
            }
        }
        refresh()
    }

    fun renderPersonal(allowed: Boolean, data: PersonalExpressionsUi, recent: List<String>) {
        state.renderPersonal(allowed, data, recent)
        refresh()
    }

    fun clearSession() {
        browser.cancelSwipe()
        state.clearSession()
        refresh()
        onSearchModeChanged(false)
    }

    fun appendQuery(value: String) { state.append(value); refresh() }
    fun removeQueryCharacter() { state.backspace(); refresh() }
    fun clearQuery() { onUserInteraction(); state.clearQuery(); refresh() }

    fun closeSearch(): Boolean {
        if (!state.searchActive) return false
        onUserInteraction()
        state.searchActive = false
        state.clearQuery()
        onSearchModeChanged(false)
        refresh()
        return true
    }

    private fun adjacentCategory(direction: PageDirection): EmojiCategory? = EmojiCategory.entries.getOrNull(
        state.category.ordinal + if (direction == PageDirection.NEXT) 1 else -1,
    )

    private fun selectCategory(category: EmojiCategory) {
        state.category = category
        refresh()
        grid.scrollToPosition(0)
        categoryButtons[category]?.let { button ->
            (categories as HorizontalScrollView).smoothScrollTo((button.left - (categories.width - button.width) / 2).coerceAtLeast(0), 0)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gridLayout.spanCount = (w / dp(48)).coerceIn(1, 16)
    }

    private fun createSearchRow() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(searchButton)
        addView(queryLabel)
        addView(icon(R.drawable.ic_expression_close, R.string.expression_clear_search) { clearQuery() })
        addView(manageButton)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
    }

    private fun createCategories(): View {
        val row = LinearLayout(context)
        EmojiCategory.entries.forEach { category ->
            val button = tab(category.marker, context.getString(category.label), compact = true) {
                selectCategory(category)
            }
            categoryButtons[category] = button
            row.addView(button)
        }
        return scrolling(row)
    }

    private fun createGroups(): View {
        val row = LinearLayout(context)
        (listOf<KaomojiGroup?>(null) + KaomojiGroup.entries).forEach { group ->
            val label = context.getString(group?.label ?: R.string.expression_all)
            val button = tab(label, label, compact = false) {
                state.group = group
                refresh()
                grid.scrollToPosition(0)
            }
            groupButtons[group] = button
            row.addView(button)
        }
        return scrolling(row)
    }

    private fun scrolling(row: View) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
    }

    private fun toggleSearch() {
        state.searchActive = !state.searchActive
        if (!state.searchActive) state.clearQuery()
        onSearchModeChanged(state.searchActive)
        refresh()
    }

    private fun refresh() {
        queryLabel.text = if (state.searchActive) state.query.ifEmpty { context.getString(R.string.expression_search_hint) }
            else context.getString(state.category.label)
        searchButton.isSelected = state.searchActive
        searchButton.contentDescription = context.getString(if (state.searchActive) R.string.expression_close_search else R.string.expression_search)
        manageButton.isEnabled = state.personalizationAllowed
        groups.visibility = if (state.category == EmojiCategory.KAOMOJI) VISIBLE else GONE
        categoryButtons.forEach { (category, button) -> select(button, category == state.category) }
        groupButtons.forEach { (group, button) -> select(button, group == state.group) }
        filterEntries()
    }

    private fun filterEntries() {
        val generation = ++renderGeneration
        filterTask?.cancel(false)
        (filterTask as? Runnable)?.let { filterWorker?.remove(it) }
        filterTask = null
        // Revocation removes old personal rows synchronously before any new background result.
        adapter.submit(emptyList(), emptySet(), false)
        emptyLabel.visibility = VISIBLE
        val privateCategory = state.category in setOf(EmojiCategory.RECENT, EmojiCategory.FAVORITES, EmojiCategory.CUSTOM)
        if (!state.personalizationAllowed && privateCategory) {
            updateEmptyLabel()
            return
        }
        if (!EmojiCatalog.isReady && state.category !in setOf(EmojiCategory.KAOMOJI, EmojiCategory.CUSTOM)) {
            emptyLabel.setText(if (preparationFailed) R.string.expression_unavailable else R.string.expression_loading)
            return
        }
        val request = state.request()
        val allowed = state.personalizationAllowed
        if (!state.searchActive || state.query.isBlank()) {
            // Public categories are pre-indexed; personal and kaomoji lists are bounded small snapshots.
            val entries = request.resolve()
            adapter.submit(entries, request.personal.favorites, allowed)
            emptyLabel.visibility = if (entries.isEmpty()) VISIBLE else GONE
            updateEmptyLabel()
            return
        }
        val reference = WeakReference(this)
        try {
            filterTask = filterWorker?.submit {
                val entries = request.resolve()
                reference.get()?.post {
                    val panel = reference.get()
                    if (panel != null && panel.isAttachedToWindow && panel.renderGeneration == generation) {
                        panel.adapter.submit(entries, request.personal.favorites, allowed)
                        panel.emptyLabel.visibility = if (entries.isEmpty()) VISIBLE else GONE
                        panel.updateEmptyLabel()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            emptyLabel.setText(R.string.expression_unavailable)
        }
        updateEmptyLabel()
    }

    private fun updateEmptyLabel() {
        emptyLabel.setText(when {
            !state.personalizationAllowed && (state.category == EmojiCategory.RECENT || state.category == EmojiCategory.FAVORITES || state.category == EmojiCategory.CUSTOM) -> R.string.expression_private
            state.searchActive && state.query.isNotEmpty() -> R.string.expression_no_results
            state.category == EmojiCategory.FAVORITES -> R.string.expression_no_favorites
            state.category == EmojiCategory.CUSTOM -> R.string.expression_no_custom
            else -> R.string.expression_no_recent
        })
    }

    private fun ensureColors() {
        if (colorsResolved) return
        primaryTextColor = color(com.google.android.material.R.attr.colorPrimary)
        onSurfaceTextColor = color(com.google.android.material.R.attr.colorOnSurface)
        selectedBackgroundColor = color(com.google.android.material.R.attr.colorPrimaryContainer)
        colorsResolved = true
    }

    override fun onAttachedToWindow() {
        // A re-attach can carry a new theme; resolve the tab colors again.
        colorsResolved = false
        super.onAttachedToWindow()
        filterWorker = ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "emoji-search").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
        preparationFailed = false
        val reference = WeakReference(this)
        preparation = EmojiCatalog.prepare(context) { ready ->
            reference.get()?.let { panel ->
                if (panel.isAttachedToWindow) { panel.preparationFailed = !ready; panel.refresh() }
            }
        }
        ensureColors()
        iconButtons.forEach { it.imageTintList = android.content.res.ColorStateList.valueOf(onSurfaceTextColor) }
        categoryButtons.forEach { (category, button) -> select(button, category == state.category) }
        groupButtons.forEach { (group, button) -> select(button, group == state.group) }
        refresh()
    }

    override fun onDetachedFromWindow() {
        renderGeneration++
        preparation?.close()
        preparation = null
        filterTask?.cancel(false)
        filterTask = null
        filterWorker?.shutdownNow()
        filterWorker = null
        adapter.submit(emptyList(), emptySet(), false)
        super.onDetachedFromWindow()
    }

    private fun select(button: MaterialButton, selected: Boolean) {
        ensureColors()
        button.isSelected = selected
        button.setTextColor(if (selected) primaryTextColor else onSurfaceTextColor)
        button.setBackgroundColor(if (selected) selectedBackgroundColor else Color.TRANSPARENT)
    }

    private fun tab(label: String, description: String, compact: Boolean, action: () -> Unit) = MaterialButton(context).apply {
        text = label
        contentDescription = description
        tooltipText = description
        textSize = if (compact) 17f else 14f
        letterSpacing = 0f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        insetTop = 0
        insetBottom = 0
        cornerRadius = 0
        elevation = 0f
        stateListAnimator = null
        setPadding(if (compact) 0 else dp(12), 0, if (compact) 0 else dp(12), 0)
        setSingleLine()
        layoutParams = LayoutParams(if (compact) dp(48) else LayoutParams.WRAP_CONTENT, dp(48))
        setOnClickListener { onUserInteraction(); action() }
    }

    private fun icon(drawable: Int, label: Int, action: () -> Unit) = AppCompatImageButton(context).apply {
        ensureColors()
        setImageResource(drawable)
        contentDescription = context.getString(label)
        tooltipText = contentDescription
        imageTintList = android.content.res.ColorStateList.valueOf(onSurfaceTextColor)
        iconButtons += this
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LayoutParams(dp(48), dp(48))
        setOnClickListener { onUserInteraction(); action() }
    }

    private fun color(attribute: Int): Int = context.obtainStyledAttributes(intArrayOf(attribute)).let {
        try { it.getColor(0, Color.BLACK) } finally { it.recycle() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
