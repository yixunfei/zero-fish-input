package dev.zeroinput.copyprobe;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Arrays;
import java.lang.ref.WeakReference;

public final class ReviewActivity extends ProtectedActivity {
    private long token;
    private CropView crop;
    private EditText editor;
    private TextView status;
    private CheckBox invert;
    private boolean recognizing;
    private final ActionMode.Callback noActions = new ActionMode.Callback() {
        @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) { return false; }
        @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
        @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
        @Override public void onDestroyActionMode(ActionMode mode) { }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        token = getIntent().getLongExtra("return_token", app().gate.token());
        if (getIntent().hasExtra("return_token") && !app().canReturn(getIntent().getLongExtra("return_token", -1))) {
            finish(); return;
        }
        if (state != null || app().locked() || !app().gate.claim(token, ProbeApp.now())) {
            app().clear(); empty(); return;
        }
        app().invalidated = () -> { detach(); finish(); };
        app().diagnostic("review_claimed");
        LinearLayout root = column();
        status = label(root, R.string.review_hint);
        if (app().frame != null) frameControls(root);
        else textControls(root, app().text);
        button(root, R.string.confirm_discard, this::confirm);
        button(root, R.string.cancel, () -> { app().clear(); finish(); });
    }
    private void empty() {
        LinearLayout root = column(); label(root, R.string.no_draft);
        button(root, R.string.close, this::finish);
    }
    private void frameControls(LinearLayout root) {
        status.setText(getString(R.string.frame_metrics, app().frame.getWidth(), app().frame.getHeight(), app().acquisitionMs));
        crop = new CropView(this, app().frame);
        crop.setOnClickListener(view -> recognize());
        root.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));
        label(root, R.string.crop_hint);
        invert = new CheckBox(this); invert.setText(R.string.dark_text); root.addView(invert);
        button(root, R.string.recognize, this::recognize);
        if (RecognizerFactory.create() == null) label(root, R.string.no_ocr);
    }
    private void textControls(LinearLayout root, char[] text) {
        editor = new EditText(this) {
            @Override public boolean onTextContextMenuItem(int id) { return false; }
        };
        editor.setHint(R.string.review_hint);
        editor.setTextSize(17);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        editor.setFilters(new InputFilter[] {new InputFilter.LengthFilter(TextGate.MAX_UNITS)});
        editor.setSaveEnabled(false); editor.setFreezesText(false);
        editor.setCustomSelectionActionModeCallback(noActions); editor.setCustomInsertionActionModeCallback(noActions);
        // Editing remains possible; suppress framework copy/cut/paste controls in this probe.
        editor.setLongClickable(false);
        if (text != null) editor.setText(text, 0, text.length);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private void recognize() {
        LineRecognizer recognizer = RecognizerFactory.create();
        if (recognizer == null || crop == null || recognizing || !current()) return;
        Bitmap line = crop.crop();
        if (line == null) { status.setText(R.string.choose_region); return; }
        recognizing = true;
        crop.setEnabled(false);
        boolean dark = invert.isChecked();
        status.setText(R.string.recognizing);
        WeakReference<ReviewActivity> owner = new WeakReference<>(this);
        boolean accepted = app().recognition.submit(token, line, dark, recognizer, (output, elapsed) -> {
            ReviewActivity activity = owner.get();
            if (activity == null || !activity.current()) { if (output != null) Arrays.fill(output, '\0'); return; }
            activity.recognitionReady(output, elapsed);
        });
        if (!accepted) { recognizing = false; crop.setEnabled(true); status.setText(R.string.recognizer_busy); }
    }
    private void recognitionReady(char[] output, long elapsed) {
        recognizing = false;
        if (crop != null) crop.setEnabled(true);
        if (output == null) { status.setText(R.string.recognition_failed); return; }
        LinearLayout root = (LinearLayout) crop.getParent();
        crop.detach(); root.removeView(crop); crop = null;
        ProbeApp.erase(app().frame); app().frame = null;
        app().text = output;
        status.setText(getString(R.string.ocr_metrics, elapsed));
        textControls(root, output);
    }
    private boolean current() { return !isFinishing() && !app().locked() && app().gate.reviewing(token, ProbeApp.now()); }
    private void confirm() {
        if (!current()) { app().clear(); return; }
        if (app().frame != null) {
            Bitmap selected = crop == null ? null : crop.crop();
            if (selected == null) { status.setText(R.string.choose_region); return; }
            ProbeApp.erase(selected);
        }
        if (editor != null) {
            char[] valid = TextGate.copy(editor.getText());
            if (valid == null) { status.setText(R.string.invalid_payload); return; }
            Arrays.fill(valid, '\0');
        }
        app().clear();
        Toast.makeText(this, R.string.confirmed_discarded, Toast.LENGTH_SHORT).show();
        finish();
    }
    private void detach() {
        if (crop != null) crop.detach();
        if (editor != null) editor.getText().clear();
    }
    @Override protected void onStop() {
        if (app().gate.token() == token) app().clear();
        super.onStop();
    }
    @Override protected void onDestroy() {
        detach();
        if (app().gate.token() == token) { app().invalidated = null; app().clear(); }
        super.onDestroy();
    }
}
