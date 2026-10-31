package dev.zeroinput.copyprobe;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;

/** Per-request native session; only verified public model files survive requests. */
final class LocalLineRecognizer implements LineRecognizer {
    static final class Timing {
        long prepareNs, preprocessNs, loadNs, inferenceNs, decodeNs, totalNs, modelBytesWritten;
        int inputWidth;
    }
    private final LinePreprocessing.Mode mode;
    private final boolean rewriteForExperiment;
    private final Consumer<Timing> measured;
    LocalLineRecognizer() { this(LinePreprocessing.Mode.BASELINE_640, false, ignored -> { }); }
    LocalLineRecognizer(LinePreprocessing.Mode mode, boolean rewriteForExperiment, Consumer<Timing> measured) {
        this.mode = mode; this.rewriteForExperiment = rewriteForExperiment; this.measured = measured;
    }
    @Override public char[] recognize(Context context, Bitmap line, boolean invert) throws Exception {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        Timing timing = new Timing(); long started = System.nanoTime();
        try {
            checkCancelled();
            File model = verified(context, "rec.onnx", 16534782,
                    "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092", timing);
            File dictionary = verified(context, "characters.txt", 74012,
                    "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b", timing);
            List<String> chars = new ArrayList<>(); chars.add("");
            String table = new String(Files.readAllBytes(dictionary.toPath()), StandardCharsets.UTF_8);
            chars.addAll(Arrays.asList(table.split("\n"))); chars.add(" ");
            OrtEnvironment env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL);
            timing.prepareNs = System.nanoTime() - started;
            long preprocessStart = System.nanoTime();
            try (LinePreprocessing.Input input = LinePreprocessing.prepare(line, invert, mode);
                 OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                timing.preprocessNs = System.nanoTime() - preprocessStart; timing.inputWidth = input.width;
                options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1);
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL);
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                checkCancelled(); return infer(env, model, options, input, chars, timing);
            }
        } finally { timing.totalNs = System.nanoTime() - started; measured.accept(timing); }
    }
    private static char[] infer(OrtEnvironment env, File model, OrtSession.SessionOptions options,
            LinePreprocessing.Input input, List<String> chars, Timing timing) throws Exception {
        long start = System.nanoTime();
        char[] result = null;
        try {
            try (OrtSession session = env.createSession(model.getAbsolutePath(), options)) {
                timing.loadNs = System.nanoTime() - start; checkCancelled();
                try (OnnxTensor value = OnnxTensor.createTensor(env, FloatBuffer.wrap(input.values),
                        new long[] {1, 3, LinePreprocessing.HEIGHT, input.width})) {
                    long inferenceStart = System.nanoTime();
                    try (OrtSession.Result output = session.run(Collections.singletonMap("x", value))) {
                        timing.inferenceNs = System.nanoTime() - inferenceStart; checkCancelled();
                        long decodeStart = System.nanoTime();
                        result = decode((OnnxTensor) output.get(0), chars);
                        timing.decodeNs = System.nanoTime() - decodeStart;
                    }
                }
            }
            checkCancelled();
            char[] delivered = result; result = null; return delivered;
        } finally { if (result != null) Arrays.fill(result, '\0'); }
    }
    private static char[] decode(OnnxTensor output, List<String> chars) throws InterruptedException {
        long[] shape = output.getInfo().getShape();
        if (shape.length != 3 || shape[0] != 1 || shape[2] != chars.size() || shape[1] > 2048) return null;
        FloatBuffer scores = output.getFloatBuffer();
        StringBuilder decoded = new StringBuilder();
        int previous = -1;
        try {
            for (int step = 0; step < shape[1]; step++) {
                checkCancelled();
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
    private File verified(Context context, String name, int size, String expected, Timing timing) throws Exception {
        PublicModelFiles.Prepared prepared = PublicModelFiles.prepare(new File(context.getNoBackupFilesDir(), name),
                size, expected, () -> context.getAssets().open("ocr/" + name),
                LocalLineRecognizer::checkCancelled, rewriteForExperiment);
        timing.modelBytesWritten += prepared.bytesWritten; return prepared.file;
    }
    static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
}
