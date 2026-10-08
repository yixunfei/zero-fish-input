package dev.zeroinput.ime.ai.page;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Public, fixed data only. This component runs in the separate test APK UID. */
public final class ExternalPageFixture extends Activity {
    private EditText editor;

    @Override public void onCreate(Bundle state) {
        super.onCreate(null);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 70, 24, 24);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(bars.left + 24, bars.top + 24, bars.right + 24, bars.bottom + 24);
            }
            return insets;
        });
        content.addView(label("Public first reference"));
        content.addView(label("Public unchecked reference"));
        EditText password = new EditText(this);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText("Public password fixture");
        password.setSaveEnabled(false);
        content.addView(password);
        TextView hidden = label("Public invisible fixture");
        hidden.setVisibility(View.GONE);
        content.addView(hidden);
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            TextView sensitive = label("Public sensitive fixture");
            sensitive.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES);
            content.addView(sensitive);
        }
        editor = new EditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setText("Public editable fixture");
        editor.setSaveEnabled(false);
        content.addView(editor);
        setContentView(content);
        editor.requestFocus();
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18);
        view.setSaveEnabled(false);
        return view;
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && editor != null) editor.post(() -> {
            if (!isFinishing() && hasWindowFocus())
                getSystemService(InputMethodManager.class).showSoftInput(editor, 0);
        });
    }

    @Override public void onSaveInstanceState(Bundle state) {}
}
