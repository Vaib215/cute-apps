package com.vaib215.cutereads;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;
import android.util.LruCache;

import java.io.File;

/**
 * A picture in the text, scaled down to fit the page. Bitmaps are decoded only when drawn
 * and a few are cached, so an illustrated book doesn't sit in memory all at once.
 */
final class PageImage extends ReplacementSpan {
    /** The largest a picture may be drawn; set by PageView before each layout. */
    static volatile int maxWidth = 1000, maxHeight = 1500;
    static float alpha = 1f; // pictures are dimmed a little at night

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };

    private final File file;
    private final int width, height;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    PageImage(File file) {
        this.file = file;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        width = Math.max(1, o.outWidth);
        height = Math.max(1, o.outHeight);
    }

    private float scale() {
        return Math.min(1f, Math.min(maxWidth / (float) width, maxHeight / (float) height));
    }

    @Override
    public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        int h = Math.round(height * scale());
        if (fm != null) {
            fm.ascent = fm.top = -h;
            fm.descent = fm.bottom = 0;
        }
        return Math.round(width * scale());
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                     int top, int y, int bottom, Paint p) {
        Bitmap b = bitmap();
        if (b == null) return;
        float s = scale();
        paint.setAlpha(Math.round(255 * alpha));
        canvas.drawBitmap(b, null, new RectF(x, y - height * s, x + width * s, y), paint);
    }

    private Bitmap bitmap() {
        Bitmap b = CACHE.get(file.getPath());
        if (b != null) return b;
        int target = Math.round(width * scale());
        int sample = 1;
        while (width / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        b = BitmapFactory.decodeFile(file.getPath(), o);
        if (b != null) CACHE.put(file.getPath(), b);
        return b;
    }
}
