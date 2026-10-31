package dev.zeroinput.copyprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Coordinates only select pixels in the already acquired in-memory bitmap. */
final class CropView extends View {
    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF fitted = new RectF();
    private float startX, startY, endX, endY, scale;
    private boolean selected;
    public CropView(Context context) { this(context, null); }
    CropView(Context context, Bitmap source) {
        super(context); bitmap = source; setSaveEnabled(false);
        setContentDescription(context.getString(R.string.crop_hint));
    }
    void detach() { bitmap = null; selected = false; invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) return;
        scale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        fitted.set((getWidth() - width) / 2, (getHeight() - height) / 2, (getWidth() + width) / 2, (getHeight() + height) / 2);
        paint.setStyle(Paint.Style.FILL); canvas.drawBitmap(bitmap, null, fitted, paint);
        if (selected) {
            paint.setColor(0xff009688); paint.setStrokeWidth(3); paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(Math.min(startX, endX), Math.min(startY, endY), Math.max(startX, endX), Math.max(startY, endY), paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || !isEnabled()) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (!fitted.contains(event.getX(), event.getY())) return false;
            startX = event.getX(); startY = event.getY(); selected = true;
        }
        endX = Math.max(fitted.left, Math.min(fitted.right, event.getX()));
        endY = Math.max(fitted.top, Math.min(fitted.bottom, event.getY()));
        invalidate();
        if (event.getAction() == MotionEvent.ACTION_UP) performClick();
        if (event.getAction() == MotionEvent.ACTION_CANCEL) selected = false;
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    Bitmap crop() {
        if (!selected || bitmap == null) return null;
        int[] bounds = CropGeometry.bounds(startX, startY, endX, endY, fitted.left, fitted.top, scale, bitmap.getWidth(), bitmap.getHeight());
        if (bounds == null) return null;
        Bitmap result = Bitmap.createBitmap(bounds[2] - bounds[0], bounds[3] - bounds[1], Bitmap.Config.ARGB_8888);
        new Canvas(result).drawBitmap(bitmap, -bounds[0], -bounds[1], null);
        return result;
    }
}
