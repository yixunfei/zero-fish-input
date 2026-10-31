package dev.zeroinput.copyprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.WindowManager;
import android.widget.Toast;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** One frame only. Projection is stopped before any optional protected-review launch. */
public final class CaptureService extends Service {
    private HandlerThread thread;
    private Handler worker;
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private long token;
    private long started;
    private boolean terminal;
    private final MediaProjection.Callback callback = new MediaProjection.Callback() {
        @Override public void onStop() { end(null); }
    };

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || worker != null) { stopSelf(); return START_NOT_STICKY; }
        token = intent.getLongExtra("token", -1);
        started = ProbeApp.now();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("capture", getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, "capture").setSmallIcon(R.drawable.ic_probe)
            .setContentTitle(getString(R.string.capturing)).setContentText(getString(R.string.capture_notification))
            .setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET).build();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        thread = new HandlerThread("single-frame", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start(); worker = new Handler(thread.getLooper());
        Intent consent = consent(intent);
        int result = intent.getIntExtra("result", 0);
        intent.removeExtra("consent");
        // Transition settling is an interaction heuristic, never source authentication.
        worker.postDelayed(() -> initialize(result, consent), 400);
        worker.postDelayed(() -> end(null), SessionGate.CAPTURE_MS - 500);
        return START_NOT_STICKY;
    }
    private static Intent consent(Intent intent) {
        return intent.getParcelableExtra("consent", Intent.class);
    }
    private ProbeApp app() { return (ProbeApp) getApplication(); }
    private void initialize(int result, Intent consent) {
        if (terminal || consent == null || !app().gate.capturing(token, ProbeApp.now()) || app().locked()) { end(null); return; }
        try {
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(result, consent);
            if (projection == null) { end(null); return; }
            projection.registerCallback(callback, worker);
            Rect size = size();
            long pixels = (long) size.width() * size.height();
            if (pixels <= 0 || pixels > 4_500_000) { end(null); return; }
            reader = ImageReader.newInstance(size.width(), size.height(), PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::acquire, worker);
            display = projection.createVirtualDisplay("PrivateCopyProbe", size.width(), size.height(),
                getResources().getConfiguration().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, worker);
        } catch (RuntimeException unavailable) { end(null); }
    }
    private Rect size() {
        WindowManager manager = getSystemService(WindowManager.class);
        return manager.getMaximumWindowMetrics().getBounds();
    }
    private void acquire(ImageReader source) {
        if (terminal || !app().gate.capturing(token, ProbeApp.now()) || app().locked()) { end(null); return; }
        Bitmap bitmap = null;
        try (Image image = source.acquireNextImage()) {
            if (image == null) return;
            bitmap = pixels(image);
        } catch (RuntimeException invalidFrame) { ProbeApp.erase(bitmap); bitmap = null; }
        end(bitmap);
    }
    private static Bitmap pixels(Image image) {
        int width = image.getWidth(), height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        if (plane.getPixelStride() != 4) throw new IllegalStateException("Unsupported pixel layout");
        ByteBuffer buffer = plane.getBuffer();
        int[] colors = new int[width * height];
        try {
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int offset = y * plane.getRowStride() + x * 4;
                colors[y * width + x] = ((buffer.get(offset + 3) & 255) << 24) |
                    ((buffer.get(offset) & 255) << 16) | ((buffer.get(offset + 1) & 255) << 8) | (buffer.get(offset + 2) & 255);
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(colors, 0, width, 0, 0, width, height);
            return bitmap;
        } finally { Arrays.fill(colors, 0); }
    }
    private void end(Bitmap bitmap) {
        if (terminal) { ProbeApp.erase(bitmap); return; }
        terminal = true;
        worker.removeCallbacksAndMessages(null);
        if (reader != null) reader.setOnImageAvailableListener(null, null);
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (projection != null) { projection.unregisterCallback(callback); projection.stop(); projection = null; }
        long elapsed = ProbeApp.now() - started;
        app().main.post(() -> {
            boolean current = app().gate.capturing(token, ProbeApp.now());
            if (bitmap != null) app().acceptFrame(token, bitmap, elapsed);
            else if (current) app().clear();
            if (current) Toast.makeText(this, bitmap != null ? R.string.frame_ready : R.string.capture_failed, Toast.LENGTH_SHORT).show();
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        });
        thread.quitSafely();
    }
    @Override public void onDestroy() {
        if (worker != null && thread.isAlive()) worker.post(() -> end(null));
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
