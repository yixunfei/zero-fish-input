package dev.zeroinput.copyprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import org.json.JSONObject;

/** Public synthetic buffers test app guards, never Android's BAL exception. */
final class ProbeReturnChecks {
    private final Instrumentation test;
    private final ProbeApp app;
    private int assertions;
    ProbeReturnChecks(Instrumentation test) {
        this.test = test;
        app = (ProbeApp) test.getTargetContext().getApplicationContext();
    }
    JSONObject run() throws Exception {
        long[] token = new long[2];
        Bitmap[] owned = new Bitmap[2];
        test.runOnMainSync(() -> {
            token[0] = app.begin();
            app.prepareReturn(token[0], 15_000);
            owned[0] = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            app.acceptFrame(token[0], owned[0], 0);
            require(app.canReturn(token[0]));
            token[1] = app.begin();
            require(!app.canReturn(token[0]) && owned[0].isRecycled());
            app.prepareReturn(token[1], 15_000);
            owned[1] = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            app.acceptFrame(token[1], owned[1], 0);
            require(app.canReturn(token[1]));
        });
        Activity stale = test.startActivitySync(new Intent(test.getTargetContext(), ReviewActivity.class)
            .putExtra("return_token", token[0]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync();
        test.runOnMainSync(() -> {
            require(stale.isFinishing() || stale.isDestroyed());
            require(app.canReturn(token[1]) && !owned[1].isRecycled());
            app.clear();
            require(!app.canReturn(token[1]) && owned[1].isRecycled());
            cropBusyGuard();
        });
        return new JSONObject().put("new_request_cancels_return", true)
            .put("stale_review_preserves_new_draft", true).put("clear_revokes_return", true)
            .put("disabled_crop_rejects_second_gesture", true)
            .put("guard_assertions", assertions).put("platform_bal_claim", false);
    }
    private void cropBusyGuard() {
        Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Bitmap display = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        CropView view = new CropView(test.getTargetContext(), source);
        int[] clicks = {0};
        view.setOnClickListener(ignored -> clicks[0]++);
        view.layout(0, 0, 100, 100); view.draw(new android.graphics.Canvas(display));
        gesture(view, 10, 30);
        Bitmap first = view.crop();
        require(first != null && first.getWidth() == 20 && clicks[0] == 1);
        view.setEnabled(false);
        gesture(view, 40, 80);
        Bitmap second = view.crop();
        require(second != null && second.getWidth() == 20 && clicks[0] == 1);
        view.detach(); ProbeApp.erase(first); ProbeApp.erase(second);
        ProbeApp.erase(source); ProbeApp.erase(display);
    }
    private static void gesture(CropView view, int start, int end) {
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, 0, start, start, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 50, 1, end, end, 0);
        try { view.onTouchEvent(down); view.onTouchEvent(up); }
        finally { down.recycle(); up.recycle(); }
    }
    private void require(boolean value) { assertions++; if (!value) throw new AssertionError(); }
}
