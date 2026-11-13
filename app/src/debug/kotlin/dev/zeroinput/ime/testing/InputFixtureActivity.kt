package dev.zeroinput.ime.testing

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.widget.AppCompatEditText

/** Instrumentation-only editor, with no saved text or exported entry point. */
class InputFixtureActivity : Activity() {
    lateinit var editor: EditText
        private set
    val editorActions = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        editor = createEditor().apply {
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

    private fun createEditor(): EditText {
        // Ordinary platform fixtures retain the standard widget and connection.
        if (!intent.getBooleanExtra("allow_multiline_ime_action", false)) return EditText(this)
        return object : AppCompatEditText(this) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val connection = super.onCreateInputConnection(outAttrs)
                // Observe restartInput delivery without changing multiline input
                // or any personalization flags in the dedicated learning fixture.
                outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
                return connection
            }
        }
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
