package dev.zeroinput.copyprobe;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.LinearLayout;

public final class ConsentActivity extends ProtectedActivity {
    private boolean awaiting;
    private boolean handedOff;
    private long token = -1;
    private long consentGeneration = -1;
    private long returnDelay = -1;
    private final Runnable expire = () -> { if (!handedOff) { app().clear(); finish(); } };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null || app().locked()) { app().clear(); finish(); return; }
        LinearLayout root = column();
        label(root, R.string.capture_disclosure);
        android.widget.RadioGroup choices = new android.widget.RadioGroup(this);
        root.addView(choices);
        int manual = returnOption(choices, R.string.return_manual, -1);
        int immediate = returnOption(choices, R.string.return_now, 0);
        returnOption(choices, R.string.return_delayed, 15_000);
        choices.check(getIntent().getBooleanExtra("automatic", false) ? immediate : manual);
        button(root, R.string.system_consent, this::request);
        button(root, R.string.cancel, this::finish);
        app().main.postDelayed(expire, 60_000);
    }
    private int returnOption(android.widget.RadioGroup group, int resource, long delay) {
        android.widget.RadioButton choice = new android.widget.RadioButton(this);
        choice.setId(android.view.View.generateViewId()); choice.setText(resource); group.addView(choice);
        choice.setOnCheckedChangeListener((button, checked) -> { if (checked) returnDelay = delay; });
        return choice.getId();
    }
    @SuppressWarnings("deprecation") private void request() {
        if (awaiting) return;
        awaiting = true;
        app().clear();
        consentGeneration = app().gate.token();
        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 1);
    }
    @Override @SuppressWarnings("deprecation") protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || !awaiting || isFinishing() || app().locked() || app().gate.token() != consentGeneration) { finish(); return; }
        awaiting = false;
        if (result != RESULT_OK || data == null) { app().clear(); finish(); return; }
        token = app().begin();
        if (token < 0) { finish(); return; }
        app().prepareReturn(token, returnDelay);
        Intent service = new Intent(this, CaptureService.class)
            .putExtra("token", token).putExtra("consent", data).putExtra("result", result);
        try { startForegroundService(service); handedOff = true; }
        catch (RuntimeException unavailable) { app().clear(); }
        finish();
    }
    @Override protected void onDestroy() {
        app().main.removeCallbacks(expire);
        if (!handedOff) app().clear();
        super.onDestroy();
    }
}
