package dev.zeroinput.copyprobe;

final class CropGeometry {
    private CropGeometry() { }
    static int[] bounds(float x1, float y1, float x2, float y2, float left, float top, float scale, int width, int height) {
        if (!(scale > 0) || width < 1 || height < 1 || !Float.isFinite(x1 + y1 + x2 + y2 + left + top + scale)) return null;
        int x = Math.max(0, Math.min(width, (int) Math.floor((Math.min(x1, x2) - left) / scale)));
        int y = Math.max(0, Math.min(height, (int) Math.floor((Math.min(y1, y2) - top) / scale)));
        int right = Math.max(0, Math.min(width, (int) Math.ceil((Math.max(x1, x2) - left) / scale)));
        int bottom = Math.max(0, Math.min(height, (int) Math.ceil((Math.max(y1, y2) - top) / scale)));
        return right - x >= 4 && bottom - y >= 4 ? new int[] {x, y, right, bottom} : null;
    }
}
