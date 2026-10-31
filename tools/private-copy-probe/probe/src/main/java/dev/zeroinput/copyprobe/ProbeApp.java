package dev.zeroinput.copyprobe;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Arrays;

public final class ProbeApp extends Application {
    final SessionGate gate = new SessionGate();
    final Handler main = new Handler(Looper.getMainLooper());
    final RecognitionWorker recognition = new RecognitionWorker(this);
    Bitmap frame;
    char[] text;
    String route;
    long acquisitionMs;
    Runnable invalidated;
    private final Runnable expiry = this::clear;
    private final BroadcastReceiver lockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { clear(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(lockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }
    long begin() {
        clear();
        if (locked()) return -1;
        long token = gate.begin(now());
        main.postDelayed(expiry, SessionGate.CAPTURE_MS);
        return token;
    }
    void acceptText(char[] value, String source) {
        long token = begin();
        if (token < 0 || !gate.complete(token, now())) { Arrays.fill(value, '\0'); return; }
        text = value;
        route = source;
        draftDeadline();
    }
    void acceptFrame(long token, Bitmap value, long elapsedMs) {
        if (locked() || !gate.complete(token, now())) { erase(value); return; }
        frame = value;
        route = "projection";
        acquisitionMs = elapsedMs;
        draftDeadline();
    }
    private void draftDeadline() {
        main.removeCallbacks(expiry);
        main.postDelayed(expiry, SessionGate.DRAFT_MS);
    }
    boolean locked() { return getSystemService(KeyguardManager.class).isDeviceLocked(); }
    void clear() {
        gate.clear();
        recognition.cancel();
        main.removeCallbacks(expiry);
        stopService(new Intent(this, CaptureService.class));
        Runnable callback = invalidated;
        invalidated = null;
        if (callback != null) callback.run();
        erase(frame); frame = null;
        if (text != null) Arrays.fill(text, '\0');
        text = null; route = null; acquisitionMs = 0;
    }
    static long now() { return SystemClock.elapsedRealtime(); }
    static void erase(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) { if (bitmap.isMutable()) bitmap.eraseColor(0); bitmap.recycle(); }
    }
}
