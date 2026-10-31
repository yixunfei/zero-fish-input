package dev.zeroinput.copyfixture;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Public constructed content only. This APK has no permissions. */
public final class FixtureActivity extends Activity {
    private String fixture;
    private boolean secure;
    private boolean pageB;
    private WebView web;
    private LinearLayout content;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try (InputStream in = getAssets().open("public-fixture.txt")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException unavailable) { finish(); return; }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        root.setPadding(20, 48, 20, 32);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(20 + bars.left, 20 + bars.top, 20 + bars.right, 20 + bars.bottom);
            return insets;
        });
        setContentView(root);
        TextView title = new TextView(this); title.setText(R.string.fixture_notice); title.setTextSize(18); root.addView(title);
        LinearLayout modes = new LinearLayout(this); root.addView(modes);
        add(modes, R.string.editor, () -> mode("editor"));
        add(modes, R.string.custom, () -> mode("custom"));
        add(modes, R.string.canvas, () -> mode("canvas"));
        add(modes, R.string.web, () -> mode("web"));
        LinearLayout actions = new LinearLayout(this); root.addView(actions);
        add(actions, R.string.share, () -> share(null));
        add(actions, R.string.secure, () -> {
            secure = !secure;
            if (secure) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            title.setText(secure ? R.string.secure_active : R.string.fixture_notice);
        });
        add(actions, R.string.change_page, () -> { pageB = !pageB; mode("canvas"); });
        TextView drag = new TextView(this); drag.setText(R.string.drag); drag.setTextSize(18); drag.setPadding(0, 20, 0, 20);
        drag.setOnLongClickListener(view -> view.startDragAndDrop(ClipData.newPlainText("public fixture", fixture),
            new View.DragShadowBuilder(view), null, View.DRAG_FLAG_GLOBAL));
        root.addView(drag);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        mode(getIntent().getStringExtra("mode"));
        if (getIntent().getBooleanExtra("secure", false)) {
            secure = true; getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); title.setText(R.string.secure_active);
        }
        String target = getIntent().getStringExtra("public_test_receiver");
        if (state == null && ("dev.zeroinput.copyprobe".equals(target) || "dev.zeroinput.copyprobe.ocr".equals(target))) {
            share(target);
            getIntent().removeExtra("public_test_receiver"); getIntent().removeExtra("public_test_process");
        }
    }
    private void add(LinearLayout root, int text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(12);
        root.addView(button, new LinearLayout.LayoutParams(0, -2, 1)); button.setOnClickListener(ignored -> action.run());
    }
    private void mode(String mode) {
        if (web != null) { web.destroy(); web = null; }
        content.removeAllViews();
        if ("editor".equals(mode)) {
            EditText editor = new EditText(this); editor.setText(fixture); editor.setTextSize(18);
            editor.setSaveEnabled(false); editor.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            content.addView(editor); return;
        }
        if ("custom".equals(mode)) {
            TextView text = new TextView(this); text.setText(fixture); text.setTextSize(19); text.setPadding(16, 32, 16, 32);
            text.setBackgroundColor(0xffdcefdc); content.addView(text);
            text.setOnLongClickListener(view -> {
                PopupMenu menu = new PopupMenu(this, view);
                menu.getMenu().add(R.string.quote); menu.getMenu().add(R.string.react); menu.show(); return true;
            }); return;
        }
        if ("web".equals(mode)) {
            web = new WebView(this); web.getSettings().setBlockNetworkLoads(true);
            web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
            String escaped = fixture.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            web.loadDataWithBaseURL(null, "<html><meta name='viewport' content='width=device-width'><body><h2>Public article A-001</h2><pre style='white-space:pre-wrap'>" + escaped + "</pre></body></html>", "text/html", "UTF-8", null);
            content.addView(web, new LinearLayout.LayoutParams(-1, -1)); return;
        }
        View canvas = new View(this) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas output) {
                super.onDraw(output); output.drawColor(pageB ? 0xffe4f9e8 : 0xffe5efff);
                paint.setColor(pageB ? Color.GREEN : Color.BLUE); output.drawRect(0, 0, getWidth(), 20, paint);
                paint.setColor(Color.BLACK); paint.setTextSize(30);
                output.drawText(pageB ? "PAGE B-002" : "PAGE A-001", 16, 60, paint);
                int row = 110;
                for (String line : fixture.split("\n")) { output.drawText(line, 16, row, paint); row += 55; }
                output.drawText("Hello world 123", 16, row + 35, paint);
                output.drawText("今天的天气很好", 16, row + 90, paint);
            }
        };
        canvas.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(canvas, new LinearLayout.LayoutParams(-1, -1));
    }
    private void share(String target) {
        boolean process = target != null && getIntent().getBooleanExtra("public_test_process", false);
        Intent intent = new Intent(process ? Intent.ACTION_PROCESS_TEXT : Intent.ACTION_SEND).setType("text/plain")
            .putExtra(process ? Intent.EXTRA_PROCESS_TEXT : Intent.EXTRA_TEXT, fixture);
        if (target != null) intent.setClassName(target, "dev.zeroinput.copyprobe.EntryActivity");
        startActivity(target == null ? Intent.createChooser(intent, getString(R.string.share)) : intent);
    }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
}
