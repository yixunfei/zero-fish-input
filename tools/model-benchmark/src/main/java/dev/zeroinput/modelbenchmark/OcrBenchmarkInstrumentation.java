package dev.zeroinput.modelbenchmark;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;

/** Research only: generated public text, no screen capture, editor or clipboard API. */
public final class OcrBenchmarkInstrumentation extends Instrumentation {
    private static final String[] TEXTS = {
        "你好，明天上午十点开会。", "这是一条公开的测试消息", "请查看群公告中的活动安排",
        "Hello world 123", "Meeting at 10:30 tomorrow", "微信 QQ LINE 私密复制",
        "订单编号：ABC20261031", "价格 128.50 元", "https://example.com/test",
        "今天的天气很好", "不要使用系统剪贴板", "Public fixture, not user data"
    };

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        try {
            result.putString("stream", "\n" + benchmark().toString(2) + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception failure) {
            result.putString("stream", "OCR research failed: " + failure.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private JSONObject benchmark() throws Exception {
        JSONObject report = new JSONObject().put("api", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS[0]).put("threads", 1)
            .put("baseline_pss_kb", pss());
        File rec = verified("rec.onnx", "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092", 16534782);
        File det = verified("det.onnx", "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae", 4819576);
        File dictionary = verified("characters.txt", "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b", 74012);
        List<String> chars = new ArrayList<>();
        chars.add("");
        chars.addAll(Arrays.asList(new String(java.nio.file.Files.readAllBytes(dictionary.toPath()), StandardCharsets.UTF_8).trim().split("\n")));
        chars.add(" ");
        Probe baseline = new Probe(); baseline.start(); Thread.sleep(500); baseline.stop();
        report.put("idle_main_queue_delay_ms", baseline.summary());
        OrtEnvironment env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1);
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            long start = System.nanoTime();
            try (OrtSession session = env.createSession(rec.getAbsolutePath(), options)) {
                report.put("recognizer_load_ms", millis(start));
                report.put("recognizer_loaded_pss_kb", pss());
                JSONArray quality = new JSONArray();
                double[] times = new double[60];
                Probe active = new Probe(); active.start();
                try {
                    for (int i = 0; i < times.length; i++) {
                        start = System.nanoTime();
                        String actual = recognize(env, session, chars, TEXTS[i % TEXTS.length], i % 2 == 1);
                        times[i] = millis(start);
                        if (i < TEXTS.length) quality.put(new JSONObject().put("expected", TEXTS[i])
                            .put("actual", actual).put("exact", TEXTS[i].equals(actual)));
                    }
                } finally { active.stop(); }
                report.put("single_line_first_ms", times[0]).put("single_line_60_samples_ms", stats(times));
                report.put("recognizer_active_pss_kb", pss()).put("recognizer_main_queue_delay_ms", active.summary());
                report.put("public_synthetic_quality", quality);
            }
            start = System.nanoTime();
            try (OrtSession session = env.createSession(det.getAbsolutePath(), options)) {
                report.put("detector_load_ms", millis(start));
                report.put("detector_480x800", detection(env, session, 480, 800));
                report.put("detector_736x1280", detection(env, session, 736, 1280));
            } catch (ai.onnxruntime.OrtException failure) {
                report.put("detector_supported", false).put("detector_error_code", failure.getCode().toString());
            }
        }
        return report.put("closed_pss_kb", pss()).put("production_integration", false);
    }

    private JSONObject detection(OrtEnvironment env, OrtSession session, int width, int height) throws Exception {
        float[] pixels = new float[3 * width * height];
        Arrays.fill(pixels, 1f);
        double[] times = new double[12];
        Probe probe = new Probe(); probe.start();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), new long[] {1, 3, height, width})) {
            for (int i = 0; i < times.length; i++) {
                long start = System.nanoTime();
                try (OrtSession.Result output = session.run(Collections.singletonMap("x", tensor))) {
                    if (output.size() == 0) throw new IllegalStateException();
                }
                times[i] = millis(start);
            }
        } finally { probe.stop(); Arrays.fill(pixels, 0); }
        return new JSONObject().put("kernel_only_ms", stats(times)).put("pss_kb", pss())
            .put("main_queue_delay_ms", probe.summary()).put("fixture", "uniform tensor; no detection postprocessing");
    }

    private String recognize(OrtEnvironment env, OrtSession session, List<String> chars, String text, boolean dark) throws Exception {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(30);
        int sourceWidth = (int) Math.ceil(paint.measureText(text)) + 16;
        Bitmap source = Bitmap.createBitmap(sourceWidth, 48, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(source); canvas.drawColor(dark ? Color.BLACK : Color.WHITE);
        paint.setColor(dark ? Color.WHITE : Color.BLACK); canvas.drawText(text, 8, 34, paint);
        Bitmap scaled = Bitmap.createScaledBitmap(source, 640, 48, true);
        int[] colors = new int[640 * 48]; scaled.getPixels(colors, 0, 640, 0, 0, 640, 48);
        scaled.recycle(); if (source != scaled) source.recycle();
        float[] values = new float[colors.length * 3];
        for (int i = 0; i < colors.length; i++) {
            int value = Color.red(colors[i]);
            if (dark) value = 255 - value;
            for (int channel = 0; channel < 3; channel++) values[channel * colors.length + i] = value / 127.5f - 1f;
        }
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(values), new long[] {1, 3, 48, 640});
             OrtSession.Result output = session.run(Collections.singletonMap("x", tensor))) {
            OnnxTensor result = (OnnxTensor) output.get(0);
            long[] shape = result.getInfo().getShape();
            if (shape[2] != chars.size()) throw new IllegalStateException();
            FloatBuffer scores = result.getFloatBuffer(); StringBuilder decoded = new StringBuilder(); int last = -1;
            for (int step = 0; step < shape[1]; step++) {
                int best = 0; float maximum = -Float.MAX_VALUE;
                for (int c = 0; c < chars.size(); c++) { float score = scores.get(); if (score > maximum) { maximum = score; best = c; } }
                if (best != 0 && best != last) decoded.append(chars.get(best));
                last = best;
            }
            return decoded.toString();
        } finally { Arrays.fill(values, 0); Arrays.fill(colors, 0); }
    }

    private File verified(String name, String expected, int size) throws Exception {
        File file = new File(getTargetContext().getCacheDir(), "ocr-" + name);
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); int total = 0;
        try (InputStream in = getContext().getAssets().open("ocr/" + name); FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                total += count; if (total > size) throw new IllegalStateException();
                out.write(buffer, 0, count); digest.update(buffer, 0, count);
            }
        }
        StringBuilder actual = new StringBuilder();
        for (byte b : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (total != size || !expected.equals(actual.toString())) throw new IllegalStateException();
        return file;
    }

    private static double millis(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static int pss() { Debug.MemoryInfo info = new Debug.MemoryInfo(); Debug.getMemoryInfo(info); return info.getTotalPss(); }
    private static JSONObject stats(double[] values) throws Exception {
        double[] sorted = values.clone(); Arrays.sort(sorted);
        return new JSONObject().put("count", sorted.length).put("p50", sorted[sorted.length / 2])
            .put("p95", sorted[(int) Math.ceil(sorted.length * .95) - 1]).put("max", sorted[sorted.length - 1]);
    }

    private static final class Probe implements Runnable {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final List<Double> delays = new ArrayList<>(); private boolean active; private long due;
        synchronized void start() { active = true; schedule(); }
        private void schedule() { due = SystemClock.uptimeMillis() + 8; main.postAtTime(this, due); }
        @Override public synchronized void run() {
            if (!active) return; delays.add((double) (SystemClock.uptimeMillis() - due)); schedule();
        }
        synchronized void stop() { active = false; main.removeCallbacks(this); }
        synchronized JSONObject summary() throws Exception {
            if (delays.isEmpty()) return new JSONObject().put("count", 0);
            double[] values = new double[delays.size()]; for (int i = 0; i < values.length; i++) values[i] = delays.get(i);
            return stats(values);
        }
    }
}
