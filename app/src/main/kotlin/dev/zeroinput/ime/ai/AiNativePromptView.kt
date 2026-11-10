package dev.zeroinput.ime.ai

import android.content.Context
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import dev.zeroinput.ime.R

internal class AiNativePromptView(context: Context) : LinearLayout(context) {
    var onSend: (String) -> Unit = {}
    var onStop: () -> Unit = {}
    var onFull: () -> Unit = {}
    private val status = TextView(context).apply { setText(R.string.ai_native_notice) }
    private val answer = TextView(context).apply { setTextIsSelectable(true) }
    private val prompt = EditText(context).apply {
        id = R.id.ai_native_prompt; setHint(R.string.ai_tool_prompt); maxLines = 3
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        filters = arrayOf(InputFilter.LengthFilter(8_192)); isSaveEnabled = false
    }
    private val send = Button(context).apply { setText(R.string.ai_native_send); setOnClickListener { onSend(prompt.text.toString().trim()) } }
    private val stop = Button(context).apply { setText(R.string.ai_run_stop); isEnabled = false; setOnClickListener { onStop() } }
    init {
        orientation = VERTICAL; isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        val padding = (12 * resources.displayMetrics.density).toInt(); setPadding(padding, padding, padding, padding)
        addView(status)
        addView(ScrollView(context).apply { addView(answer) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(prompt)
        addView(LinearLayout(context).apply {
            listOf(send, stop, Button(context).apply { setText(R.string.ai_native_full); setOnClickListener { onFull() } }).forEach {
                it.maxLines = 2; addView(it, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        })
    }
    fun ready(ready: Boolean) { send.isEnabled = ready }
    fun running(value: Boolean) { send.isEnabled = !value; prompt.isEnabled = !value; stop.isEnabled = value }
    fun result(text: String) { answer.text = text.take(8_192) }
    fun status(label: Int) { status.setText(label) }
    fun clear() { prompt.text?.clear(); answer.text = ""; status.setText(R.string.ai_native_notice); running(false) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val height = minOf(available, (360 * resources.displayMetrics.density).toInt())
        prompt.maxLines = if (height < 300 * resources.displayMetrics.density) 1 else 3
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
