package com.chaomi.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Movie;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

/**
 * GIF 播放器。
 *
 * API 28+ 用 ImageDecoder 逐帧解码（画质好）；
 * 低版本退回 android.graphics.Movie（轻量、够用）。
 * 播放 1~2 个循环后停在首帧，避免列表里一堆动图同时播耗电。
 */
class GifPlayer {

    private static final int MAX_LOOPS = 2;

    static void play(ImageView iv, byte[] data) {
        if (iv == null || data == null || data.length == 0) return;
        if (Build.VERSION.SDK_INT >= 28) {
            playModern(iv, data);
        } else {
            playLegacy(iv, data);
        }
    }

    // ---- API 28+ ----
    private static void playModern(ImageView iv, byte[] data) {
        try {
            if (Build.VERSION.SDK_INT < 28) return;
            final android.graphics.drawable.AnimatedImageDrawable d =
                    (android.graphics.drawable.AnimatedImageDrawable)
                            android.graphics.ImageDecoder.decodeDrawable(
                                    android.graphics.ImageDecoder.createSource(
                                            java.nio.ByteBuffer.wrap(data)));
            d.setRepeatCount(MAX_LOOPS - 1);
            iv.setImageDrawable(d);
            d.start();
        } catch (Throwable t) {
            playLegacy(iv, data);
        }
    }

    // ---- 低版本：Movie 逐帧 ----
    private static void playLegacy(ImageView iv, byte[] data) {
        final Movie movie;
        try {
            movie = Movie.decodeByteArray(data, 0, data.length);
        } catch (Throwable t) {
            fallbackFirstFrame(iv, data);
            return;
        }
        if (movie == null || movie.width() <= 0) {
            fallbackFirstFrame(iv, data);
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final long start = android.os.SystemClock.uptimeMillis();
        final int dur = movie.duration() <= 0 ? 1000 : movie.duration();
        final long endAt = start + (long) dur * MAX_LOOPS;

        // 用一张等比缩放的 Bitmap 承载，避免内存抖动
        final int w = movie.width();
        final int hgt = movie.height();
        final Bitmap bmp = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);

        Runnable r = new Runnable() {
            @Override
            public void run() {
                long now = android.os.SystemClock.uptimeMillis();
                if (now > endAt) {
                    // 播完停在首帧
                    movie.setTime(0);
                    canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                    movie.draw(canvas, 0, 0);
                    iv.setImageBitmap(bmp);
                    return;
                }
                int t = (int) ((now - start) % dur);
                movie.setTime(t);
                canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                movie.draw(canvas, 0, 0);
                iv.setImageBitmap(bmp);
                h.postDelayed(this, 60);
            }
        };
        h.post(r);
    }

    private static void fallbackFirstFrame(ImageView iv, byte[] data) {
        try {
            Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bm != null) iv.setImageBitmap(bm);
        } catch (Throwable ignored) {
        }
    }
}
