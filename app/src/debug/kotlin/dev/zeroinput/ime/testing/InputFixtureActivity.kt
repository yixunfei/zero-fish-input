package dev.zeroinput.ime.testing

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Instrumentation-only editor, with no saved text or exported entry point. */
class InputFixtureActivity : Activity() {
    lateinit var editor: EditText
        private set
    val editorActions = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        editor = EditText(this).apply {
            inputType = intent.getIntExtra("input_type", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            imeOptions = intent.getIntExtra("ime_options", 0)
            if (!intent.getBooleanExtra("ordinary_fixture", false)) {
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            isSaveEnabled = false
            setOnEditorActionListener { _, action, _ ->
                editorActions += action
                action != EditorInfo.IME_NULL
            }
        }
        setContentView(editor)
        if (intent.getBooleanExtra("fullscreen_fixture", false)) {
            editor.setBackgroundColor(android.graphics.Color.rgb(24, 168, 96))
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
        editor.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // The editor must be served by Android before requesting its IME on a cold launch.
        editor.post {
            if (hasWindowFocus() && !isFinishing && !isDestroyed) {
                getSystemService(InputMethodManager::class.java).showSoftInput(editor, 0)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { /* Fixture text is never saved. */ }
}
