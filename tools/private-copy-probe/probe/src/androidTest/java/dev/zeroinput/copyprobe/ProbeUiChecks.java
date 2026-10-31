package dev.zeroinput.copyprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import org.json.JSONObject;

/** Operates only the probe's own Views. System consent remains a real UI action. */
final class ProbeUiChecks {
    private final Instrumentation test;
    private final ProbeApp app;
    private int assertions;
    ProbeUiChecks(Instrumentation test) { this.test = test; app = (ProbeApp) test.getTargetContext().getApplicationContext(); }

    JSONObject tileRoundTrip() throws Exception {
        Instrumentation.ActivityMonitor consentMonitor = test.addMonitor(ConsentActivity.class.getName(), null, false);
        Instrumentation.ActivityMonitor reviewMonitor = test.addMonitor(ReviewActivity.class.getName(), null, false);
        Activity entry = test.startActivitySync(new Intent(test.getTargetContext(), EntryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.runOnMainSync(() -> click(entry.getWindow().getDecorView(), R.string.add_tile));
        status("Add the tile normally, open the public fixture, and tap the tile. Waiting 60 seconds.");
        Activity consent = test.waitForMonitorWithTimeout(consentMonitor, 60_000);
        require(consent != null);
        test.runOnMainSync(() -> click(consent.getWindow().getDecorView(), R.string.system_consent));
        status("Choose the public fixture in the system sharing dialog. Then reopen the draft using the tile.");
        Activity review = test.waitForMonitorWithTimeout(reviewMonitor, 60_000);
        require(review != null);
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (review.getWindow().getDecorView().getWidth() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20);
        require(app.gate.state(ProbeApp.now()) == SessionGate.State.REVIEW);
        test.runOnMainSync(() -> cropPublicLine(review));
        if (RecognizerFactory.create() != null) {
            deadline = SystemClock.uptimeMillis() + 10_000;
            boolean[] ready = {false};
            do {
                test.runOnMainSync(() -> ready[0] = app.text != null);
                if (!ready[0]) SystemClock.sleep(25);
            } while (!ready[0] && SystemClock.uptimeMillis() < deadline);
            test.runOnMainSync(() -> {
                require(app.text != null && java.util.Arrays.equals("Hello world 123".toCharArray(), app.text));
                require(app.frame == null);
                EditText editor = find(review.getWindow().getDecorView(), EditText.class);
                require(editor != null && !editor.onTextContextMenuItem(android.R.id.copy));
                require(!editor.isSaveEnabled());
                click(review.getWindow().getDecorView(), R.string.confirm_discard);
            });
        } else test.runOnMainSync(() -> click(review.getWindow().getDecorView(), R.string.confirm_discard));
        require(app.text == null && app.frame == null);
        test.removeMonitor(consentMonitor); test.removeMonitor(reviewMonitor);
        return new JSONObject().put("tile_launch_and_return", true).put("manual_system_consent", true)
            .put("crop_gesture", true).put("captured_line_exact", RecognizerFactory.create() != null)
            .put("confirm_discards", true).put("ui_assertions", assertions);
    }
    JSONObject cancellation() throws Exception {
        Activity consent = test.startActivitySync(new Intent(test.getTargetContext(), ConsentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.runOnMainSync(() -> click(consent.getWindow().getDecorView(), R.string.system_consent));
        status("Cancel the system sharing dialog, or lock the emulator. Waiting 60 seconds.");
        long deadline = SystemClock.uptimeMillis() + 60_000;
        while (!consent.isDestroyed() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25);
        require(consent.isDestroyed()); require(app.frame == null && app.text == null);
        require(app.gate.state(ProbeApp.now()) == SessionGate.State.EMPTY);
        return new JSONObject().put("consent_cancel_or_expiry", true).put("no_draft", true).put("ui_assertions", assertions);
    }
    JSONObject drag() throws Exception {
        test.getTargetContext().startActivity(new Intent().setClassName("dev.zeroinput.copyfixture", "dev.zeroinput.copyfixture.FixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("mode", "canvas"));
        SystemClock.sleep(300);
        test.startActivitySync(new Intent(test.getTargetContext(), DropActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        status("Pair the empty receiver and public fixture in split screen, then drag its public payload. Waiting 120 seconds.");
        long deadline = SystemClock.uptimeMillis() + 120_000;
        while (app.gate.state(ProbeApp.now()) != SessionGate.State.REVIEW && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25);
        String fixture;
        try (java.io.InputStream in = test.getTargetContext().getAssets().open("public-fixture.txt")) {
            fixture = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        test.runOnMainSync(() -> {
            require("drag".equals(app.route));
            require(java.util.Arrays.equals(fixture.toCharArray(), app.text));
            app.clear();
        });
        return new JSONObject().put("cross_app_drag_exact", true).put("cleared", true).put("ui_assertions", assertions);
    }
    JSONObject draftInvalidation(boolean expiry) throws Exception {
        test.runOnMainSync(() -> app.acceptText("Public lifetime fixture".toCharArray(), "test"));
        long start = ProbeApp.now();
        status(expiry ? "Waiting for the real 60 second draft deadline." : "Turn the emulator screen off to invalidate the public draft.");
        long deadline = SystemClock.uptimeMillis() + 65_000;
        boolean[] retained = {true};
        do {
            test.runOnMainSync(() -> retained[0] = app.text != null);
            if (retained[0]) SystemClock.sleep(25);
        } while (retained[0] && SystemClock.uptimeMillis() < deadline);
        require(!retained[0]); require(app.gate.state(ProbeApp.now()) == SessionGate.State.EMPTY);
        long elapsed = ProbeApp.now() - start;
        if (expiry) require(elapsed >= SessionGate.DRAFT_MS && elapsed < SessionGate.DRAFT_MS + 5000);
        return new JSONObject().put("draft_cleared", true).put("reason", expiry ? "deadline" : "screen-off")
            .put("elapsed_ms", elapsed).put("ui_assertions", assertions);
    }
    private void cropPublicLine(Activity review) {
        CropView view = find(review.getWindow().getDecorView(), CropView.class);
        require(view != null && view.getWidth() > 0 && view.getHeight() > 0 && app.frame != null);
        Bitmap frame = app.frame;
        int left = frame.getWidth(), top = frame.getHeight(), bottom = 0;
        for (int y = 0; y < frame.getHeight(); y++) for (int x = 0; x < frame.getWidth(); x++) {
            int color = frame.getPixel(x, y);
            if (Color.red(color) < 10 && Color.green(color) < 10 && Color.blue(color) > 240) {
                left = Math.min(left, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
        }
        require(bottom > top);
        float sourceScale = (bottom - top + 1) / 20f;
        float previewScale = Math.min((float) view.getWidth() / frame.getWidth(), (float) view.getHeight() / frame.getHeight());
        float offsetX = (view.getWidth() - frame.getWidth() * previewScale) / 2;
        float offsetY = (view.getHeight() - frame.getHeight() * previewScale) / 2;
        float x = offsetX + (left + 8 * sourceScale) * previewScale;
        float y = offsetY + (top + 330 * sourceScale) * previewScale;
        gesture(view, x, y, x + 260 * sourceScale * previewScale, y + 48 * sourceScale * previewScale);
        Bitmap selected = view.crop(); require(selected != null); ProbeApp.erase(selected);
    }
    private static void gesture(View view, float x, float y, float endX, float endY) {
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent move = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_MOVE, endX, endY, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 100, MotionEvent.ACTION_UP, endX, endY, 0);
        try { view.dispatchTouchEvent(down); view.dispatchTouchEvent(move); view.dispatchTouchEvent(up); }
        finally { down.recycle(); move.recycle(); up.recycle(); }
    }
    static <T> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            T result = find(group.getChildAt(i), type); if (result != null) return result;
        }
        return null;
    }
    private boolean click(View view, int resource) {
        if (view instanceof Button button && button.getText().toString().equals(test.getTargetContext().getString(resource)))
            return button.performClick();
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++)
            if (click(group.getChildAt(i), resource)) return true;
        return false;
    }
    private void status(String text) { Bundle value = new Bundle(); value.putString("stream", text + "\n"); test.sendStatus(0, value); }
    private void require(boolean value) { assertions++; if (!value) throw new AssertionError(); }
}
