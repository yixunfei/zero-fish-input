package dev.zeroinput.copyprobe;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.Arrays;

/** Baseline plus one experimental geometric normalization; no lexical repair. */
final class LinePreprocessing {
    enum Mode { BASELINE_640, INK_HEIGHT_1280 }
    static final int HEIGHT = 48;
    static final class Input implements AutoCloseable {
        final int width; final float[] values;
        Input(int width, float[] values) { this.width = width; this.values = values; }
        @Override public void close() { Arrays.fill(values, 0); }
    }
    private LinePreprocessing() { }
    static Input prepare(Bitmap source, boolean invert, Mode mode) throws InterruptedException {
        if ((long) source.getWidth() * source.getHeight() > 4_194_304) throw new IllegalArgumentException("Crop limit");
        Bitmap line = mode == Mode.BASELINE_640 ? source : verticalInkCrop(source, invert);
        try {
            int limit = mode == Mode.BASELINE_640 ? 640 : 1280;
            int width = Math.min(limit, Math.max(32,
                    ((int) Math.ceil((double) line.getWidth() * HEIGHT / line.getHeight()) + 31) / 32 * 32));
            return new Input(width, tensor(line, invert, width));
        } finally { if (line != source) ProbeApp.erase(line); }
    }
    private static Bitmap verticalInkCrop(Bitmap source, boolean invert) throws InterruptedException {
        int[] row = new int[source.getWidth()]; int top = source.getHeight(), bottom = -1;
        try {
            for (int y = 0; y < source.getHeight(); y++) {
                LocalLineRecognizer.checkCancelled();
                source.getPixels(row, 0, row.length, 0, y, row.length, 1);
                for (int color : row) {
                    int grey = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3;
                    if ((invert ? 255 - grey : grey) < 180) { top = Math.min(top, y); bottom = y; break; }
                }
            }
            if (bottom < top) return source;
            int margin = Math.max(2, (bottom - top + 8) / 8);
            top = Math.max(0, top - margin); bottom = Math.min(source.getHeight() - 1, bottom + margin);
            // Preserve horizontal whitespace. Multiline crops remain an unsupported boundary.
            return Bitmap.createBitmap(source, 0, top, source.getWidth(), bottom - top + 1);
        } finally { Arrays.fill(row, 0); }
    }
    private static float[] tensor(Bitmap source, boolean invert, int targetWidth) throws InterruptedException {
        int width = Math.max(1, Math.min(targetWidth, (int) Math.ceil((double) source.getWidth() * HEIGHT / source.getHeight())));
        Bitmap resized = Bitmap.createScaledBitmap(source, width, HEIGHT, true);
        int[] colors = new int[width * HEIGHT]; float[] values = new float[3 * targetWidth * HEIGHT];
        boolean complete = false;
        try {
            resized.getPixels(colors, 0, width, 0, 0, width, HEIGHT);
            for (int y = 0; y < HEIGHT; y++) {
                LocalLineRecognizer.checkCancelled();
                for (int x = 0; x < width; x++) {
                    int color = colors[y * width + x];
                    for (int channel = 0; channel < 3; channel++) {
                        int pixel = channel == 0 ? Color.red(color) : channel == 1 ? Color.green(color) : Color.blue(color);
                        if (invert) pixel = 255 - pixel;
                        values[channel * targetWidth * HEIGHT + y * targetWidth + x] = pixel / 127.5f - 1f;
                    }
                }
            }
            complete = true; return values;
        } finally {
            if (!complete) Arrays.fill(values, 0);
            if (resized != source) ProbeApp.erase(resized);
            Arrays.fill(colors, 0);
        }
    }
}
