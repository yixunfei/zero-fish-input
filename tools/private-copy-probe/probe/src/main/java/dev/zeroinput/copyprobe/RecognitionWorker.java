package dev.zeroinput.copyprobe;

import android.graphics.Bitmap;
import java.util.Arrays;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One native operation process-wide, with no queue; idle worker exits after one second. */
final class RecognitionWorker {
    interface Completion { void ready(char[] text, long elapsedMs); }
    private final ProbeApp app;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS,
        new SynchronousQueue<>(), runnable -> new Thread(runnable, "local-line-recognition"));
    private Job active;
    RecognitionWorker(ProbeApp app) { this.app = app; }

    boolean submit(long token, Bitmap line, boolean invert, LineRecognizer recognizer, Completion completion) {
        if (active != null) { ProbeApp.erase(line); return false; }
        Job job = new Job(token, line, invert, recognizer, completion);
        active = job;
        try { executor.execute(job); return true; }
        catch (java.util.concurrent.RejectedExecutionException busy) { active = null; ProbeApp.erase(line); return false; }
    }
    void cancel() { if (active != null) active.cancel(); }
    boolean isIdle() { return active == null; }

    private final class Job implements Runnable {
        final long token;
        final Bitmap line;
        final boolean invert;
        final LineRecognizer recognizer;
        final Completion completion;
        private boolean cancelled;
        private Thread runner;
        Job(long token, Bitmap line, boolean invert, LineRecognizer recognizer, Completion completion) {
            this.token = token; this.line = line; this.invert = invert; this.recognizer = recognizer; this.completion = completion;
        }
        synchronized void cancel() { cancelled = true; if (runner != null) runner.interrupt(); }
        private synchronized boolean start() { runner = Thread.currentThread(); return !cancelled; }
        private synchronized void stopped() { runner = null; }
        @Override public void run() {
            char[] result = null;
            long start = ProbeApp.now();
            try {
                if (start() && app.gate.reviewing(token, ProbeApp.now())) {
                    result = recognizer.recognize(app, line, invert);
                }
            } catch (Exception unavailable) { /* Only a fixed failure status reaches the UI. */ }
            finally { ProbeApp.erase(line); stopped(); }
            char[] output = result;
            long elapsed = ProbeApp.now() - start;
            app.main.post(() -> {
                if (active == this) active = null;
                if (app.gate.reviewing(token, ProbeApp.now()) && !app.locked()) completion.ready(output, elapsed);
                else if (output != null) Arrays.fill(output, '\0');
            });
        }
    }
}
