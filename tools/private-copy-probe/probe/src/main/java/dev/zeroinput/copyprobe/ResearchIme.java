package dev.zeroinput.copyprobe;

import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Public-fixture navigation only. Application code never reads editor content. */
public final class ResearchIme extends InputMethodService {
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true; // The explicit research controls stay available with an emulator hardware keyboard.
    }
    @Override public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        // Fullscreen=false prevents the framework's extract-text path. Do not retain EditorInfo here.
        setExtractViewShown(false);
    }
    @Override public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);
        TextView notice = new TextView(this); notice.setText(R.string.ime_notice); root.addView(notice);
        Button capture = new Button(this); capture.setText(R.string.start_capture); root.addView(capture);
        capture.setOnClickListener(view -> startActivity(new Intent(this, ConsentActivity.class)
            .putExtra("automatic", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        Button picker = new Button(this); picker.setText(R.string.choose_ime); root.addView(picker);
        picker.setOnClickListener(view -> getSystemService(InputMethodManager.class).showInputMethodPicker());
        return root;
    }
}
