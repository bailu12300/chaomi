package com.chaomi.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 原生价格趋势图：折线 + 渐变填充 + 极值标注。
 * 不引第三方图表库，保持 APK 体积与离线可用性。
 */
public class TrendChartView extends View {

    private final List<HwPoint> pts = new ArrayList<>();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    private static final int COL_UP = 0xFFf85149;    // 涨=红
    private static final int COL_DOWN = 0xFF3fb950;  // 跌=绿
    private static final int COL_FLAT = 0xFF58a6ff;

    public TrendChartView(Context c) { this(c, null); }

    public TrendChartView(Context c, AttributeSet a) {
        super(c, a);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2f));
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(dp(10f));
        textPaint.setColor(0xFF8b949e);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.7f));
        gridPaint.setColor(0xFF30363d);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setPoints(List<HwPoint> p) {
        pts.clear();
        if (p != null) {
            for (HwPoint x : p) if (x != null && x.ref > 0) pts.add(x);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padL = dp(44), padR = dp(12), padT = dp(16), padB = dp(22);

        if (pts.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(dp(13f));
            textPaint.setColor(0xFF8b949e);
            cv.drawText("暂无历史价格，采集积累后自动生成走势", w / 2f, h / 2f, textPaint);
            textPaint.setTextSize(dp(10f));
            return;
        }

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (HwPoint p : pts) {
            double v = p.ref;
            if (p.lo > 0) v = Math.min(v, p.lo);
            if (p.hi > 0) v = Math.max(v, p.hi);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (max - min < 1) { // 单点或完全持平：给一点纵向余量
            max = max + Math.max(1, max * 0.02);
            min = Math.max(0, min - Math.max(1, min * 0.02));
        }
        double span = max - min;

        float plotW = w - padL - padR;
        float plotH = h - padT - padB;

        // 横向网格 + 纵轴价格刻度
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTextSize(dp(10f));
        textPaint.setColor(0xFF8b949e);
        int rows = 3;
        for (int i = 0; i <= rows; i++) {
            float y = padT + plotH * i / rows;
            cv.drawLine(padL, y, w - padR, y, gridPaint);
            double v = max - span * i / rows;
            cv.drawText(String.format(Locale.CHINA, "%.0f", v), padL - dp(6), y + dp(3.5f), textPaint);
        }

        // 数据点坐标
        int n = pts.size();
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? padL + plotW / 2f : padL + plotW * i / (n - 1);
            ys[i] = (float) (padT + plotH * (max - pts.get(i).ref) / span);
        }

        double first = pts.get(0).ref, last = pts.get(n - 1).ref;
        int color = last > first ? COL_UP : (last < first ? COL_DOWN : COL_FLAT);
        linePaint.setColor(color);

        linePath.reset();
        fillPath.reset();
        linePath.moveTo(xs[0], ys[0]);
        fillPath.moveTo(xs[0], padT + plotH);
        fillPath.lineTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            // 用简单折线（点数少、间隔不均，曲线平滑反而失真）
            linePath.lineTo(xs[i], ys[i]);
            fillPath.lineTo(xs[i], ys[i]);
        }
        fillPath.lineTo(xs[n - 1], padT + plotH);
        fillPath.close();

        fillPaint.setShader(new LinearGradient(0, padT, 0, padT + plotH,
                (color & 0x00FFFFFF) | 0x55000000, 0x00000000, Shader.TileMode.CLAMP));
        cv.drawPath(fillPath, fillPaint);
        cv.drawPath(linePath, linePaint);

        // 数据点
        dotPaint.setColor(color);
        for (int i = 0; i < n; i++) {
            float r = (i == n - 1) ? dp(3.6f) : dp(2.2f);
            cv.drawCircle(xs[i], ys[i], r, dotPaint);
        }

        // 首末时间标注
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(0xFF8b949e);
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd", Locale.CHINA);
        cv.drawText(sdf.format(new Date(pts.get(0).ts * 1000)), padL, h - dp(6), textPaint);
        if (n > 1) {
            textPaint.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(sdf.format(new Date(pts.get(n - 1).ts * 1000)), w - padR, h - dp(6), textPaint);
        }

        // 最新价标注
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(color);
        textPaint.setTextSize(dp(11f));
        cv.drawText(String.format(Locale.CHINA, "%.0f", last), w - padR, Math.max(padT + dp(10), ys[n - 1] - dp(8)), textPaint);
        textPaint.setTextSize(dp(10f));
    }
}
