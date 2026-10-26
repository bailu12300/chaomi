package com.chaomi.app;

import android.content.Context;
import androidx.core.content.ContextCompat;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** 延迟折线图（Canvas 自绘，无第三方依赖） */
public class LatencyChartView extends View {

    private int[] data = new int[0];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public LatencyChartView(Context c) { this(c, null); }

    public LatencyChartView(Context c, AttributeSet a) {
        super(c, a);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2f));
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        dot.setStyle(Paint.Style.FILL);
        text.setTextSize(dp(10f));
        text.setColor(ContextCompat.getColor(c, R.color.chaomi_text_dim));
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(dp(0.7f));
        grid.setColor(ContextCompat.getColor(c, R.color.chaomi_border));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    void setData(int[] d) {
        this.data = d == null ? new int[0] : d;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padL = dp(38), padR = dp(10), padT = dp(10), padB = dp(16);
        float pw = w - padL - padR, ph = h - padT - padB;

        if (data.length == 0) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(dp(12f));
            cv.drawText("等待心跳数据…", w / 2f, h / 2f, text);
            text.setTextSize(dp(10f));
            return;
        }

        int max = 1;
        for (int v : data) if (v > max) max = v;
        // 上方留 20% 余量，避免顶到边
        max = (int) Math.ceil(max * 1.2);
        if (max < 50) max = 50;

        // 网格 + 纵轴刻度
        text.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 3; i++) {
            float y = padT + ph * i / 3f;
            cv.drawLine(padL, y, w - padR, y, grid);
            cv.drawText(String.valueOf(max - max * i / 3), padL - dp(5), y + dp(3.5f), text);
        }

        int n = data.length;
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? padL + pw / 2f : padL + pw * i / (n - 1);
            ys[i] = padT + ph * (max - data[i]) / (float) max;
        }

        int color = qualityColor(getContext(), data[n - 1]);
        line.setColor(color);
        dot.setColor(color);

        linePath.reset();
        fillPath.reset();
        linePath.moveTo(xs[0], ys[0]);
        fillPath.moveTo(xs[0], padT + ph);
        fillPath.lineTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            linePath.lineTo(xs[i], ys[i]);
            fillPath.lineTo(xs[i], ys[i]);
        }
        fillPath.lineTo(xs[n - 1], padT + ph);
        fillPath.close();

        fill.setShader(new LinearGradient(0, padT, 0, padT + ph,
                (color & 0x00FFFFFF) | 0x55000000, 0x00000000, Shader.TileMode.CLAMP));
        cv.drawPath(fillPath, fill);
        cv.drawPath(linePath, line);

        for (int i = 0; i < n; i++) {
            cv.drawCircle(xs[i], ys[i], i == n - 1 ? dp(3.4f) : dp(1.8f), dot);
        }

        // 最新值
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(color);
        text.setTextSize(dp(11f));
        cv.drawText(data[n - 1] + " ms", w - padR,
                Math.max(padT + dp(10), ys[n - 1] - dp(8)), text);
        text.setTextSize(dp(10f));
    }

    /** 延迟分档配色：<60 绿、<150 蓝、<300 金、其余红 */
    static int qualityColor(Context c, int ms) {
        if (ms < 60) return ContextCompat.getColor(c, R.color.chaomi_down);
        if (ms < 150) return ContextCompat.getColor(c, R.color.chaomi_accent);
        if (ms < 300) return ContextCompat.getColor(c, R.color.chaomi_gold);
        return ContextCompat.getColor(c, R.color.chaomi_up);
    }
}
