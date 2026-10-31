package dev.zeroinput.copyprobe;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

abstract class ProtectedActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        android.view.contentcapture.ContentCaptureManager capture = getSystemService(android.view.contentcapture.ContentCaptureManager.class);
        if (capture != null) capture.setContentCaptureEnabled(false);
    }
    ProbeApp app() { return (ProbeApp) getApplication(); }
    LinearLayout column() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setSaveEnabled(false);
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom);
            return insets;
        });
        setContentView(root);
        return root;
    }
    TextView label(LinearLayout parent, int resource) {
        TextView view = new TextView(this);
        view.setText(resource); view.setTextSize(17); view.setPadding(0, 8, 0, 8);
        view.setSaveEnabled(false); parent.addView(view); return view;
    }
    Button button(LinearLayout parent, int resource, Runnable action) {
        Button view = new Button(this); view.setText(resource);
        view.setOnClickListener(ignored -> action.run()); parent.addView(view); return view;
    }
}
