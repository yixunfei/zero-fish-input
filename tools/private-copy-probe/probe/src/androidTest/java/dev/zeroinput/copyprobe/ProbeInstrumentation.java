package dev.zeroinput.copyprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Separate test APK. No UiAutomation, accessibility, shell grants or screenshot output. */
public final class ProbeInstrumentation extends Instrumentation {
    private Bundle arguments;
    private int assertions;
    @Override public void runOnMainSync(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        super.runOnMainSync(() -> {
            try { action.run(); }
            catch (RuntimeException | AssertionError error) { failure.set(error); }
        });
        if (failure.get() instanceof RuntimeException error) throw error;
        if (failure.get() instanceof AssertionError error) throw error;
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            JSONObject report;
            if ("capture".equals(arguments.getString("mode"))) report = capture();
            else if ("ocr".equals(arguments.getString("mode"))) report = syntheticOcr();
            else if ("tile".equals(arguments.getString("mode"))) report = new ProbeUiChecks(this).tileRoundTrip();
            else if ("cancel".equals(arguments.getString("mode"))) report = new ProbeUiChecks(this).cancellation();
            else if ("lock".equals(arguments.getString("mode"))) report = new ProbeUiChecks(this).draftInvalidation(false);
            else if ("expiry".equals(arguments.getString("mode"))) report = new ProbeUiChecks(this).draftInvalidation(true);
            else if ("drag".equals(arguments.getString("mode"))) report = new ProbeUiChecks(this).drag();
            else if ("return-guards".equals(arguments.getString("mode"))) report = new ProbeReturnChecks(this).run();
            else if ("ocr-round2".equals(arguments.getString("mode"))) report = new OcrRoundTwoChecks(this).run();
            else report = boundaries();
            report.put("assertions", assertions).put("api", android.os.Build.VERSION.SDK_INT);
            result.putString("stream", "\n" + report.toString(2) + "\n"); finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "Probe failed at assertion " + assertions + ": " + failure.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private ProbeApp app() { return (ProbeApp) getTargetContext().getApplicationContext(); }
    private void check(boolean condition) { assertions++; if (!condition) throw new AssertionError(); }
    private JSONObject boundaries() throws Exception {
        String fixture;
        try (InputStream in = getTargetContext().getAssets().open("public-fixture.txt")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fixture);
        check(Arrays.equals(fixture.toCharArray(), IncomingText.read(send)));
        Intent process = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, fixture);
        check(Arrays.equals(fixture.toCharArray(), IncomingText.read(process)));
        check(IncomingText.read(new Intent(send).setDataAndType(Uri.parse("content://fixture/item"), "text/plain")) == null);
        check(IncomingText.read(new Intent(send).putExtra(Intent.EXTRA_HTML_TEXT, "<b>fixture</b>")) == null);
        check(IncomingText.read(new Intent(send).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://fixture/item"))) == null);
        check(IncomingText.read(new Intent(send).setType("text/html")) == null);
        ClipData drag = ClipData.newPlainText("public", fixture);
        check(Arrays.equals(fixture.toCharArray(), IncomingText.readClip(drag)));
        Intent matching = new Intent(send); matching.setClipData(drag);
        check(Arrays.equals(fixture.toCharArray(), IncomingText.read(matching)));
        drag.addItem(new ClipData.Item("extra"));
        check(IncomingText.readClip(drag) == null);
        check(IncomingText.readClip(ClipData.newRawUri("uri", Uri.parse("content://fixture/item"))) == null);
        check(IncomingText.readClip(ClipData.newHtmlText("html", "text", "<b>text</b>")) == null);
        String target = getTargetContext().getPackageName();
        Intent source = new Intent().setClassName("dev.zeroinput.copyfixture", "dev.zeroinput.copyfixture.FixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("public_test_receiver", target);
        getTargetContext().startActivity(source);
        waitFor(() -> app().gate.state(ProbeApp.now()) == SessionGate.State.REVIEW, 10_000);
        runOnMainSync(() -> check(app().text != null && Arrays.equals(fixture.toCharArray(), app().text)));
        runOnMainSync(() -> app().clear());
        check(app().text == null && app().frame == null);
        ActivityMonitor reviewMonitor = addMonitor(ReviewActivity.class.getName(), null, false);
        getTargetContext().startActivity(new Intent(source).putExtra("public_test_process", true));
        Activity review = waitForMonitorWithTimeout(reviewMonitor, 10_000);
        check(review != null);
        waitFor(() -> app().gate.state(ProbeApp.now()) == SessionGate.State.REVIEW, 10_000);
        runOnMainSync(() -> check(Arrays.equals(fixture.toCharArray(), app().text) && "process-text".equals(app().route)));
        runOnMainSync(review::recreate);
        waitFor(() -> app().gate.state(ProbeApp.now()) == SessionGate.State.EMPTY, 5000);
        runOnMainSync(() -> check(app().text == null));
        removeMonitor(reviewMonitor);
        long[] token = new long[1];
        runOnMainSync(() -> { token[0] = app().begin(); app().clear(); });
        Bitmap late = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        runOnMainSync(() -> app().acceptFrame(token[0], late, 1));
        check(late.isRecycled()); check(app().frame == null);
        workerIsolation();
        return new JSONObject().put("payload_and_lifecycle", "passed").put("cross_app_send_exact", true)
            .put("cross_app_process_text_exact", true).put("recreation_clears_text", true);
    }
    private void workerIsolation() throws Exception {
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        char[] result = "public late output".toCharArray();
        LineRecognizer fake = (context, line, invert) -> {
            running.countDown();
            try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException cancelled) { /* Emulate a native call returning after cancellation. */ }
            return result;
        };
        Bitmap first = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        long[] token = new long[1];
        runOnMainSync(() -> {
            app().acceptText("public worker fixture".toCharArray(), "test"); token[0] = app().gate.token();
            check(app().gate.claim(token[0], ProbeApp.now()));
            check(app().recognition.submit(token[0], first, false, fake, (text, elapsed) -> delivered.set(true)));
        });
        check(running.await(2, java.util.concurrent.TimeUnit.SECONDS));
        runOnMainSync(() -> {
            check(!app().recognition.submit(token[0], second, false, fake, (text, elapsed) -> delivered.set(true)));
            app().clear();
        });
        release.countDown();
        waitOnMain(() -> app().recognition.isIdle(), 2000);
        check(first.isRecycled()); check(second.isRecycled()); check(!delivered.get());
        check(Arrays.equals(new char[result.length], result));
    }
    private JSONObject capture() throws Exception {
        boolean secure = "true".equals(arguments.getString("secure"));
        String expected = arguments.getString("source", "canvas");
        Intent source = new Intent().setClassName("dev.zeroinput.copyfixture", "dev.zeroinput.copyfixture.FixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("mode", expected).putExtra("secure", secure);
        getTargetContext().startActivity(source);
        SystemClock.sleep(500);
        Intent consent = new Intent(getTargetContext(), ConsentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity consentActivity = startActivitySync(consent);
        runOnMainSync(() -> clickConsent(consentActivity.getWindow().getDecorView()));
        sendStatus(0, status("Waiting for a normal user-operated system sharing dialog (60 seconds)"));
        waitFor(() -> app().gate.state(ProbeApp.now()) == SessionGate.State.READY, 60_000);
        JSONObject report = new JSONObject();
        runOnMainSync(() -> {
            try {
                Bitmap frame = app().frame;
                check(frame != null);
                report.put("width", frame.getWidth()).put("height", frame.getHeight()).put("post_consent_ms", app().acquisitionMs);
                int blue = 0, black = 0;
                int minX = frame.getWidth(), minY = frame.getHeight(), maxX = 0, maxY = 0;
                for (int y = 0; y < frame.getHeight(); y++) for (int x = 0; x < frame.getWidth(); x++) {
                    int color = frame.getPixel(x, y);
                    if (Color.red(color) < 10 && Color.green(color) < 10 && Color.blue(color) > 240) {
                        blue++; minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                    }
                    if (Color.red(color) < 10 && Color.green(color) < 10 && Color.blue(color) < 10) black++;
                }
                report.put("public_blue_marker_pixels", blue).put("black_fraction", (double) black / (frame.getWidth() * frame.getHeight()));
                if ("canvas".equals(expected)) check(secure ? blue == 0 : blue > 1000);
                report.put("marker_left", minX).put("marker_top", minY).put("marker_right", maxX).put("marker_bottom", maxY);
            } catch (org.json.JSONException invalidReport) { throw new AssertionError(invalidReport); }
        });
        report.put("pss_after_capture_kb", pss());
        if (!secure && "canvas".equals(expected) && RecognizerFactory.create() != null) capturedLine(report);
        runOnMainSync(() -> app().clear());
        check(app().frame == null);
        return report.put("cleared", true).put("secure_fixture", secure);
    }
    private void capturedLine(JSONObject report) throws Exception {
        float scale = (report.getInt("marker_bottom") - report.getInt("marker_top") + 1) / 20f;
        int x = report.getInt("marker_left") + Math.round(8 * scale);
        int y = report.getInt("marker_top") + Math.round(330 * scale);
        Bitmap[] crop = new Bitmap[1];
        runOnMainSync(() -> crop[0] = Bitmap.createBitmap(app().frame, x, y, Math.round(260 * scale), Math.round(48 * scale)));
        long start = ProbeApp.now();
        char[] result = RecognizerFactory.create().recognize(getTargetContext(), crop[0], false);
        report.put("captured_line_total_ocr_ms", ProbeApp.now() - start);
        boolean exact = Arrays.equals("Hello world 123".toCharArray(), result);
        report.put("captured_public_line_exact", exact);
        if (result != null) Arrays.fill(result, '\0');
        ProbeApp.erase(crop[0]);
    }
    private JSONObject syntheticOcr() throws Exception {
        LineRecognizer recognizer = RecognizerFactory.create(); check(recognizer != null);
        String[] samples = {"Hello world 123", "今天的天气很好", "订单编号：ABC20261031", "价格 128.50 元", "微信 QQ LINE 私密复制",
            "Hello  world", "https://example.com/a?x=1&y=2", "标点，。！？🙂"};
        JSONArray results = new JSONArray();
        int baseline = pss();
        int repeats = "stress".equals(arguments.getString("samples")) ? 4 : 1;
        for (int iteration = 0; iteration < samples.length * repeats; iteration++) {
            int i = iteration % samples.length;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(30); paint.setColor(Color.BLACK);
            Bitmap line = Bitmap.createBitmap((int) Math.ceil(paint.measureText(samples[i])) + 16, 48, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(line); canvas.drawColor(Color.WHITE); canvas.drawText(samples[i], 8, 34, paint);
            long start = ProbeApp.now();
            char[] actual = recognizer.recognize(getTargetContext(), line, false);
            long elapsed = ProbeApp.now() - start;
            results.put(new JSONObject().put("fixture_index", i).put("exact", Arrays.equals(samples[i].toCharArray(), actual))
                .put("total_ms", elapsed).put("pss_kb", pss()).put("output_units", actual == null ? 0 : actual.length));
            if (actual != null) Arrays.fill(actual, '\0'); ProbeApp.erase(line);
        }
        int closed = pss();
        System.gc(); SystemClock.sleep(300); // Diagnostic only; production never forces GC for cleanup.
        return new JSONObject().put("fixture_results", results).put("baseline_pss_kb", baseline)
            .put("closed_pss_kb", closed).put("diagnostic_after_gc_pss_kb", pss())
            .put("timing_scope", "asset verification, model extraction/load, preprocessing, inference, decoding, session close");
    }
    private static Bundle status(String value) { Bundle bundle = new Bundle(); bundle.putString("stream", value + "\n"); return bundle; }
    private boolean clickConsent(android.view.View view) {
        if (view instanceof android.widget.Button button && button.getText().toString().equals(getTargetContext().getString(R.string.system_consent)))
            return button.performClick();
        if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++)
            if (clickConsent(group.getChildAt(i))) return true;
        return false;
    }
    private static int pss() { Debug.MemoryInfo info = new Debug.MemoryInfo(); Debug.getMemoryInfo(info); return info.getTotalPss(); }
    private void waitFor(java.util.function.BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25);
        check(condition.getAsBoolean());
    }
    private void waitOnMain(java.util.function.BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        boolean[] ready = {false};
        do {
            runOnMainSync(() -> ready[0] = condition.getAsBoolean());
            if (!ready[0]) SystemClock.sleep(25);
        } while (!ready[0] && SystemClock.uptimeMillis() < deadline);
        check(ready[0]);
    }
}
