package com.chaomi.app;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.squareup.picasso.Picasso;

import java.util.List;

/**
 * 把文章 blocks 渲染成原生 View，完全不使用 WebView。
 * 好处：字体/间距跟随系统、图片可点击放大、滚动流畅、无脚本与追踪。
 */
class ArticleRenderer {

    static final int COL_TEXT = 0xFFe6edf3;
    static final int COL_DIM = 0xFF8b949e;
    static final int COL_ACCENT = 0xFF58a6ff;
    static final int COL_BORDER = 0xFF30363d;
    static final int COL_SURFACE = 0xFF161b22;
    static final int COL_CODE_BG = 0xFF0d1117;
    static final int COL_QUOTE_BG = 0xFF1c2128;

    /** 把 blocks 渲染进容器（会先清空） */
    static void render(Context ctx, LinearLayout box, List<Block> blocks) {
        box.removeAllViews();
        if (blocks == null || blocks.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText("（正文为空）");
            tv.setTextColor(COL_DIM);
            tv.setTextSize(14);
            box.addView(tv, lp(0, dp(ctx, 4)));
            return;
        }
        for (Block b : blocks) {
            if (b == null || b.type == null) continue;
            View v = build(ctx, b);
            if (v != null) box.addView(v);
        }
    }

    private static View build(Context ctx, Block b) {
        switch (b.type) {
            case "h2":
                return heading(ctx, b.text, 19, true);
            case "h3":
                return heading(ctx, b.text, 16, true);
            case "quote":
                return quote(ctx, b.text);
            case "code":
                return code(ctx, b.text);
            case "li":
                return listItem(ctx, b.text);
            case "img":
                return image(ctx, b.src, b.alt);
            case "p":
            default:
                if (TextUtils.isEmpty(b.text)) return null;
                return paragraph(ctx, b.text);
        }
    }

    private static LinearLayout.LayoutParams lp(int topMarginPx, int extra) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = topMarginPx;
        return p;
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    private static TextView heading(Context ctx, String text, int sp, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(sp);
        tv.setTextColor(COL_TEXT);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLineSpacing(dp(ctx, 4), 1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(ctx, 18);
        p.bottomMargin = dp(ctx, 4);
        tv.setLayoutParams(p);
        return tv;
    }

    private static TextView paragraph(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(15.5f);
        tv.setTextColor(COL_TEXT);
        tv.setLineSpacing(dp(ctx, 8), 1f);
        tv.setTextIsSelectable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(ctx, 10);
        tv.setLayoutParams(p);
        return tv;
    }

    private static View listItem(Context ctx, String text) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(lp(dp(ctx, 6), 0));

        TextView dot = new TextView(ctx);
        dot.setText("•");
        dot.setTextColor(COL_ACCENT);
        dot.setTextSize(15);
        dot.setPadding(0, 0, dp(ctx, 8), 0);
        row.addView(dot);

        TextView tv = new TextView(ctx);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(15);
        tv.setTextColor(COL_TEXT);
        tv.setLineSpacing(dp(ctx, 6), 1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(p);
        row.addView(tv);
        return row;
    }

    private static View quote(Context ctx, String text) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setLayoutParams(lp(dp(ctx, 12), 0));

        View bar = new View(ctx);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(ctx, 3),
                ViewGroup.LayoutParams.MATCH_PARENT);
        bar.setBackgroundColor(COL_ACCENT);
        bar.setLayoutParams(bp);
        wrap.addView(bar);

        TextView tv = new TextView(ctx);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(14.5f);
        tv.setTextColor(0xFFc9d1d9);
        tv.setLineSpacing(dp(ctx, 6), 1f);
        tv.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        tv.setBackgroundColor(COL_QUOTE_BG);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(p);
        wrap.addView(tv);
        return wrap;
    }

    private static View code(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(13);
        tv.setTextColor(0xFFa5d6ff);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLineSpacing(dp(ctx, 4), 1f);
        tv.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        tv.setBackgroundColor(COL_CODE_BG);
        tv.setTextIsSelectable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(ctx, 12);
        tv.setLayoutParams(p);
        return tv;
    }

    private static View image(Context ctx, String src, String alt) {
        if (TextUtils.isEmpty(src)) return null;
        ImageView iv = new ImageView(ctx);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(ctx, 12);
        iv.setLayoutParams(p);
        iv.setContentDescription(alt == null ? "" : alt);
        // Picasso 自带内存/磁盘缓存；公众号图片依赖 referer，这里不强加，失败就留空
        Picasso.get()
                .load(Utils.fullUrl(src))
                .placeholder(android.R.color.transparent)
                .into(iv);
        return iv;
    }
}
