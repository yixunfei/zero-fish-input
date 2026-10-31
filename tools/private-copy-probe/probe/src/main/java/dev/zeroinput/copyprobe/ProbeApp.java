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
    private Runnable pendingReturn;
    private long returnToken = -1;
    private long returnDelay = -1;
    private String returnIme;
    private final android.database.ContentObserver imeObserver = new android.database.ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { clear(); }
    };
    private final BroadcastReceiver lockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { clear(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(lockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD), false, imeObserver);
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
        scheduleReturn(token);
    }
    private String currentIme() {
        return android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
    }
    void prepareReturn(long token, long delay) {
        if (!gate.capturing(token, now())) return;
        returnToken = token;
        returnDelay = delay;
        returnIme = currentIme();
    }
    private void scheduleReturn(long token) {
        diagnostic("frame_ready_delay_ms=" + returnDelay);
        if (token != returnToken || returnDelay < 0) return;
        long scheduled = now();
        pendingReturn = () -> {
            pendingReturn = null;
            diagnostic("return_timer_elapsed_ms=" + (now() - scheduled));
            if (!canReturn(token)) return;
            diagnostic("return_attempt");
            try {
                startActivity(new Intent(this, ReviewActivity.class).putExtra("return_token", token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            } catch (RuntimeException denied) { /* Manual tile remains available until the same deadline. */ }
        };
        main.postDelayed(pendingReturn, returnDelay);
    }
    // Debug research timing only: no acquired payload, source identity or authorization details.
    void diagnostic(String event) {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            android.util.Log.i("ProbeReturnTiming", event);
    }
    boolean canReturn(long token) {
        if (token != returnToken || token != gate.token() || locked() ||
            !java.util.Objects.equals(returnIme, currentIme())) return false;
        return gate.state(now()) == SessionGate.State.READY;
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
        if (pendingReturn != null) main.removeCallbacks(pendingReturn);
        pendingReturn = null; returnToken = -1; returnDelay = -1; returnIme = null;
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
