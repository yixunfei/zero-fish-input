package dev.zeroinput.ime.clipboardguard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Selection;
import android.text.Spannable;
import android.view.View;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.textclassifier.TextClassifier;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Runs in the test APK UID without relying on the target APK's Kotlin runtime. */
public final class ExternalClipboardFixture extends Activity {
    private TextView focusStatus;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        TextView text = new TextView(this);
        text.setId(View.generateViewId());
        text.setText("public guard fixture", TextView.BufferType.SPANNABLE);
        text.setTextSize(22f);
        text.setTextIsSelectable(true);
        text.setTextClassifier(TextClassifier.NO_OP);
        text.setSaveEnabled(false);
        TextView selectionStatus = new TextView(this);
        focusStatus = new TextView(this);
        text.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                // Observe after the framework has appended its PROCESS_TEXT actions.
                text.post(() -> reportNativeAction(menu, selectionStatus));
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
            @Override public void onDestroyActionMode(ActionMode mode) { selectionStatus.setText("Native selection ended"); }
        });
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, 80, 32, 32);
        applySystemInsets(content);
        content.addView(text);
        EditText editor = new EditText(this);
        editor.setId(View.generateViewId());
        editor.setText("public-editable-fixture");
        editor.setSaveEnabled(false);
        editor.setShowSoftInputOnFocus(false);
        editor.setTextClassifier(TextClassifier.NO_OP);
        editor.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                editor.selectAll();
                editor.post(() -> {
                    if (Math.min(editor.getSelectionStart(), editor.getSelectionEnd()) == 0
                            && Math.max(editor.getSelectionStart(), editor.getSelectionEnd()) == editor.length()) {
                        reportNativeAction(menu, selectionStatus);
                    } else selectionStatus.setText("Native editable selection was partial");
                });
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
            @Override public void onDestroyActionMode(ActionMode mode) { selectionStatus.setText("Native selection ended"); }
        });
        content.addView(editor);
        content.addView(button("Select public text", () -> {
            text.requestFocus();
            Selection.selectAll((Spannable) text.getText());
            selectionStatus.setText("Native selection requested");
            if (!text.performLongClick()) selectionStatus.setText("Native selection rejected");
        }));
        content.addView(button("Copy public fixture", () -> {
            Selection.selectAll((Spannable) text.getText());
            text.onTextContextMenuItem(android.R.id.copy);
        }));
        content.addView(button("Share public fixture", () -> startActivity(Intent.createChooser(
            new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "public guard fixture"), null))));
        content.addView(button("Close public fixture", () -> {
            finishAndRemoveTask();
            // Reset framework selection state in this standalone, separate-UID test process.
            android.os.Process.killProcess(android.os.Process.myPid());
        }));
        content.addView(selectionStatus);
        content.addView(focusStatus);
        setContentView(content);
    }

    @SuppressWarnings("deprecation")
    private void applySystemInsets(View content) {
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.graphics.Insets safe = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(32 + safe.left, 80 + safe.top, 32 + safe.right, 32 + safe.bottom);
            } else {
                view.setPadding(32 + insets.getSystemWindowInsetLeft(), 80 + insets.getSystemWindowInsetTop(),
                    32 + insets.getSystemWindowInsetRight(), 32 + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (focusStatus != null) focusStatus.setText(hasFocus ? "Source ready" : "Source inactive");
    }

    private void reportNativeAction(Menu menu, TextView status) {
        for (int index = 0; index < menu.size(); index++) {
            Intent intent = menu.getItem(index).getIntent();
            if (intent != null && Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())
                    && "text/plain".equals(intent.getType()) && intent.getComponent() != null
                    && "dev.zeroinput.ime.debug".equals(intent.getComponent().getPackageName())
                    && "dev.zeroinput.ime.clipboard.ClipboardImportActivity".equals(intent.getComponent().getClassName())) {
                status.setText("Native ZeroInput text action available");
                return;
            }
        }
        status.setText("Native ZeroInput text action unavailable");
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {}
}
