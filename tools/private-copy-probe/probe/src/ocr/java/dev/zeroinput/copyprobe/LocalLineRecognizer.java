package dev.zeroinput.copyprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;

/** Per-request native session; only fixed public model data may be written to disk. */
final class LocalLineRecognizer implements LineRecognizer {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 48;
    @Override public char[] recognize(Context context, Bitmap line, boolean invert) throws Exception {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        if (Thread.currentThread().isInterrupted()) return null;
        File model = verified(context, "rec.onnx", 16534782, "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092");
        File dictionary = verified(context, "characters.txt", 74012, "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b");
        List<String> chars = new ArrayList<>(); chars.add("");
        String table = new String(Files.readAllBytes(dictionary.toPath()), StandardCharsets.UTF_8);
        chars.addAll(Arrays.asList(table.split("\n"))); chars.add(" ");
        OrtEnvironment env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL);
        int width = Math.min(WIDTH, Math.max(32, ((int) Math.ceil((double) line.getWidth() * HEIGHT / line.getHeight()) + 31) / 32 * 32));
        float[] input = tensor(line, invert, width);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1);
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            if (Thread.currentThread().isInterrupted()) return null;
            try (OrtSession session = env.createSession(model.getAbsolutePath(), options);
                 OnnxTensor value = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[] {1, 3, HEIGHT, width});
                 OrtSession.Result output = session.run(Collections.singletonMap("x", value))) {
                if (Thread.currentThread().isInterrupted()) return null;
                return decode((OnnxTensor) output.get(0), chars);
            }
        } finally { Arrays.fill(input, 0); }
    }
    private static float[] tensor(Bitmap source, boolean invert, int targetWidth) {
        int width = Math.max(1, Math.min(targetWidth, (int) Math.ceil((double) source.getWidth() * HEIGHT / source.getHeight())));
        Bitmap resized = Bitmap.createScaledBitmap(source, width, HEIGHT, true);
        int[] colors = new int[width * HEIGHT];
        float[] values = new float[3 * targetWidth * HEIGHT];
        // PP-OCR normalized padding is zero; preserve aspect except overlong lines.
        try {
            resized.getPixels(colors, 0, width, 0, 0, width, HEIGHT);
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < width; x++) {
                int color = colors[y * width + x];
                for (int channel = 0; channel < 3; channel++) {
                    int pixel = channel == 0 ? Color.red(color) : channel == 1 ? Color.green(color) : Color.blue(color);
                    if (invert) pixel = 255 - pixel;
                    values[channel * targetWidth * HEIGHT + y * targetWidth + x] = pixel / 127.5f - 1f;
                }
            }
            return values;
        } finally { if (resized != source) ProbeApp.erase(resized); Arrays.fill(colors, 0); }
    }
    private static char[] decode(OnnxTensor output, List<String> chars) {
        long[] shape = output.getInfo().getShape();
        if (shape.length != 3 || shape[0] != 1 || shape[2] != chars.size() || shape[1] > 2048) return null;
        FloatBuffer scores = output.getFloatBuffer();
        StringBuilder decoded = new StringBuilder();
        int previous = -1;
        try {
            for (int step = 0; step < shape[1]; step++) {
                int best = 0; float maximum = -Float.MAX_VALUE;
                for (int c = 0; c < chars.size(); c++) { float score = scores.get(); if (score > maximum) { maximum = score; best = c; } }
                if (best != 0 && best != previous) decoded.append(chars.get(best));
                previous = best;
            }
            return TextGate.copy(decoded);
        } finally {
            scores.clear(); while (scores.hasRemaining()) scores.put(0);
            for (int i = 0; i < decoded.length(); i++) decoded.setCharAt(i, '\0');
        }
    }
    private static File verified(Context context, String name, int size, String expected) throws Exception {
        File target = new File(context.getNoBackupFilesDir(), name);
        // A bounded model-only extraction. No frame, crop, draft or result is serialized.
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int total = 0;
        try (InputStream in = context.getAssets().open("ocr/" + name); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                total += count; if (total > size) throw new IllegalStateException("Model size mismatch");
                out.write(buffer, 0, count); digest.update(buffer, 0, count);
            }
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (total != size || !expected.contentEquals(hash)) throw new IllegalStateException("Model checksum mismatch");
        return target;
    }
}
