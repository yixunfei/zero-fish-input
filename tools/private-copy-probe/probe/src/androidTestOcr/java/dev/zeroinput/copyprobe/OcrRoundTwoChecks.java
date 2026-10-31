package dev.zeroinput.copyprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Debug;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** One fixed corpus, one candidate, paired pixels and alternating order. Never serializes payload. */
final class OcrRoundTwoChecks {
    private final Instrumentation instrumentation;
    OcrRoundTwoChecks(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    JSONObject run() throws Exception {
        OcrDifference.verifyMetric();
        Context context = instrumentation.getTargetContext();
        List<PublicOcrCorpus.Sample> corpus = PublicOcrCorpus.samples();
        HashSet<Integer> alphabet = alphabet(context);
        int baselinePss = pss();
        JSONArray warmup = new JSONArray();
        Bitmap first = render(corpus.get(0));
        try {
            warmup.put(recognize(context, corpus.get(0), first, false, false));
            warmup.put(recognize(context, corpus.get(0), first, true, false));
        } finally { ProbeApp.erase(first); }
        JSONArray paired = new JSONArray(); Map<String, Aggregate> groups = new LinkedHashMap<>();
        Aggregate overall = new Aggregate();
        for (PublicOcrCorpus.Sample sample : corpus) {
            Bitmap line = render(sample); int generation = line.getGenerationId();
            JSONObject baseline, candidate;
            try {
                if (sample.index % 2 == 0) {
                    baseline = recognize(context, sample, line, false, false);
                    candidate = recognize(context, sample, line, true, false);
                } else {
                    candidate = recognize(context, sample, line, true, false);
                    baseline = recognize(context, sample, line, false, false);
                }
                if (line.isRecycled() || line.getGenerationId() != generation) throw new AssertionError("Input modified");
                int missing = (int) sample.text.codePoints().filter(point -> !alphabet.contains(point) && point != '\n').count();
                boolean multiline = sample.text.indexOf('\n') >= 0;
                JSONObject pair = new JSONObject().put("fixture_index", sample.index).put("category", sample.category)
                        .put("text_size_px", sample.textSize).put("extra_vertical_padding_px", sample.padding)
                        .put("dark", sample.dark).put("source_width", line.getWidth()).put("source_height", line.getHeight())
                        .put("missing_table_points", missing).put("multiline_boundary", multiline)
                        .put("candidate_first", sample.index % 2 != 0).put("input_unmodified", true)
                        .put("baseline", baseline).put("candidate", candidate);
                paired.put(pair); overall.add(baseline, candidate);
                group(groups, "category_" + sample.category, baseline, candidate);
                group(groups, "size_" + sample.textSize, baseline, candidate);
                group(groups, "padding_" + sample.padding, baseline, candidate);
                group(groups, "dark_" + sample.dark, baseline, candidate);
                group(groups, "table_or_layout_boundary_" + (missing > 0 || multiline), baseline, candidate);
                group(groups, "over_640_" + ((double) line.getWidth() * 48 / line.getHeight() > 640), baseline, candidate);
            } finally { ProbeApp.erase(line); }
            if (sample.index % 16 == 15) progress(sample.index + 1);
        }
        JSONObject grouped = new JSONObject();
        for (Map.Entry<String, Aggregate> entry : groups.entrySet()) grouped.put(entry.getKey(), entry.getValue().json());
        JSONObject cost = cacheCost(context, corpus.get(0));
        return new JSONObject().put("corpus_version", 1).put("different_strings", corpus.size())
                .put("repeat_per_mode", 1).put("warmup_excluded_from_quality", warmup)
                .put("overall", overall.json()).put("groups", grouped).put("pairs", paired).put("cache_cost", cost)
                .put("baseline_pss_kb", baselinePss).put("closed_pss_kb", pss())
                .put("pss_is_post_request_sample_not_peak", true).put("native_sessions_close_each_request", true)
                .put("default_preprocessing_remains_baseline", true);
    }
    private static JSONObject recognize(Context context, PublicOcrCorpus.Sample sample, Bitmap line,
            boolean candidate, boolean rewrite) throws Exception {
        LocalLineRecognizer.Timing[] timing = new LocalLineRecognizer.Timing[1];
        LineRecognizer recognizer = new LocalLineRecognizer(candidate ? LinePreprocessing.Mode.INK_HEIGHT_1280
                : LinePreprocessing.Mode.BASELINE_640, rewrite, value -> timing[0] = value);
        char[] output = null; String failure = "none";
        try {
            try { output = recognizer.recognize(context, line, sample.dark); }
            catch (Exception error) { failure = error.getClass().getSimpleName(); }
            JSONObject result = OcrDifference.compare(sample.text, output).put("error_class", failure)
                    .put("null_output", output == null).put("post_request_pss_kb", pss());
            LocalLineRecognizer.Timing t = timing[0];
            if (t != null) result.put("prepare_ms", t.prepareNs / 1e6).put("preprocess_ms", t.preprocessNs / 1e6)
                    .put("load_ms", t.loadNs / 1e6).put("inference_ms", t.inferenceNs / 1e6)
                    .put("decode_ms", t.decodeNs / 1e6).put("total_ms", t.totalNs / 1e6)
                    .put("model_bytes_written", t.modelBytesWritten).put("input_width", t.inputWidth);
            return result;
        } finally { if (output != null) Arrays.fill(output, '\0'); }
    }
    private static JSONObject cacheCost(Context context, PublicOcrCorpus.Sample sample) throws Exception {
        JSONArray pairs = new JSONArray(); Bitmap line = render(sample);
        File model = new File(context.getNoBackupFilesDir(), "rec.onnx");
        File table = new File(context.getNoBackupFilesDir(), "characters.txt");
        try {
            for (int index = 0; index < 8; index++) {
                JSONObject rewrite, reuse; boolean stable;
                if (index % 2 == 0) {
                    rewrite = recognize(context, sample, line, false, true);
                    long modelBefore = model.lastModified(), tableBefore = table.lastModified();
                    reuse = recognize(context, sample, line, false, false);
                    stable = modelBefore == model.lastModified() && tableBefore == table.lastModified();
                } else {
                    long modelBefore = model.lastModified(), tableBefore = table.lastModified();
                    reuse = recognize(context, sample, line, false, false);
                    stable = modelBefore == model.lastModified() && tableBefore == table.lastModified();
                    rewrite = recognize(context, sample, line, false, true);
                }
                if (!stable || reuse.getLong("model_bytes_written") != 0
                        || rewrite.getLong("model_bytes_written") != 16_608_794L) throw new AssertionError("Cache reuse");
                pairs.put(new JSONObject().put("iteration", index).put("reuse_first", index % 2 != 0)
                        .put("reuse_mtime_stable", stable).put("rewrite", rewrite).put("reuse", reuse));
            }
        } finally { ProbeApp.erase(line); }
        return new JSONObject().put("pairs", pairs).put("atomic_sync_rewrite_comparator", true)
                .put("repeats_not_additional_quality_samples", true);
    }
    private static Bitmap render(PublicOcrCorpus.Sample sample) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(sample.textSize);
        paint.setColor(sample.dark ? Color.WHITE : Color.BLACK);
        String[] lines = sample.text.split("\n", -1); float width = 0;
        for (String line : lines) width = Math.max(width, paint.measureText(line));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        int step = (int) Math.ceil((metrics.descent - metrics.ascent) * 1.2);
        int height = sample.regression ? 48 : 16 + 2 * sample.padding + lines.length * step;
        Bitmap bitmap = Bitmap.createBitmap((int) Math.ceil(width) + 16, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(sample.dark ? Color.BLACK : Color.WHITE);
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], 8,
                sample.regression ? 34 : 8 + sample.padding - metrics.ascent + i * step, paint);
        return bitmap;
    }
    private static HashSet<Integer> alphabet(Context context) throws Exception {
        HashSet<Integer> result = new HashSet<>();
        try (InputStream in = context.getAssets().open("ocr/characters.txt")) {
            String table = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            table.codePoints().forEach(result::add); result.add((int) ' ');
        }
        return result;
    }
    private static void group(Map<String, Aggregate> groups, String key, JSONObject baseline, JSONObject candidate) throws Exception {
        Aggregate aggregate = groups.get(key);
        if (aggregate == null) { aggregate = new Aggregate(); groups.put(key, aggregate); }
        aggregate.add(baseline, candidate);
    }
    private void progress(int count) {
        Bundle status = new Bundle(); status.putString("stream", "OCR paired fixtures completed: " + count + "\n");
        instrumentation.sendStatus(0, status);
    }
    private static int pss() { Debug.MemoryInfo info = new Debug.MemoryInfo(); Debug.getMemoryInfo(info); return info.getTotalPss(); }
    private static final class Aggregate {
        int samples, reference, baseExact, candidateExact, baseEdits, candidateEdits, improved, regressed, unchanged;
        int exactGained, exactLost;
        final Map<String, List<Double>> timings = new LinkedHashMap<>();
        void add(JSONObject base, JSONObject candidate) throws Exception {
            samples++; reference += base.getInt("reference_points");
            boolean b = base.getBoolean("exact"), c = candidate.getBoolean("exact");
            if (b) baseExact++; if (c) candidateExact++; if (!b && c) exactGained++; if (b && !c) exactLost++;
            int be = base.getInt("edits"), ce = candidate.getInt("edits"); baseEdits += be; candidateEdits += ce;
            if (ce < be) improved++; else if (ce > be) regressed++; else unchanged++;
            collect("baseline", base); collect("candidate", candidate);
        }
        private void collect(String mode, JSONObject result) throws Exception {
            for (String metric : new String[] {"prepare_ms", "preprocess_ms", "load_ms", "inference_ms", "decode_ms", "total_ms"}) {
                String key = mode + "_" + metric;
                List<Double> values = timings.get(key);
                if (values == null) { values = new ArrayList<>(); timings.put(key, values); }
                values.add(result.optDouble(metric, 0));
            }
        }
        JSONObject json() throws Exception {
            JSONObject time = new JSONObject();
            for (Map.Entry<String, List<Double>> entry : timings.entrySet()) {
                List<Double> values = entry.getValue(); values.sort(Double::compareTo);
                double median = (values.get((values.size() - 1) / 2) + values.get(values.size() / 2)) / 2;
                time.put(entry.getKey(), new JSONObject().put("p50", median)
                        .put("p95", values.get((int) Math.ceil(values.size() * .95) - 1))
                        .put("max", values.get(values.size() - 1)));
            }
            return new JSONObject().put("samples", samples).put("reference_points", reference)
                    .put("baseline_exact", baseExact).put("candidate_exact", candidateExact)
                    .put("baseline_edits", baseEdits).put("candidate_edits", candidateEdits)
                    .put("baseline_cer", (double) baseEdits / reference).put("candidate_cer", (double) candidateEdits / reference)
                    .put("fewer_edits", improved).put("more_edits", regressed).put("same_edits", unchanged)
                    .put("exact_gained", exactGained).put("exact_lost", exactLost).put("timing", time);
        }
    }
}
