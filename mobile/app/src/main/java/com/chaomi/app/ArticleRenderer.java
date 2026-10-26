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
            case "topic":
                return topic(ctx, b.text);
            case "link":
                return linkCard(ctx, b);
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

        String url = Utils.fullUrl(src);
        String lower = url == null ? "" : url.toLowerCase();

        // 动图（gif/webp）用 GifImageView 播放；静态图仍走 Picasso
        ImageView iv = new ImageView(ctx);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(ctx, 12);
        iv.setLayoutParams(p);
        iv.setContentDescription(alt == null ? "" : alt);

        if (lower.endsWith(".gif")) {
            // GIF：用 ImageDecoder / Movie 播放
            loadGif(ctx, iv, url);
        } else {
            Picasso.get()
                    .load(url)
                    .placeholder(android.R.color.transparent)
                    .into(iv);
        }
        return iv;
    }

    /** 播放 GIF：API 28+ 用 ImageDecoder，低版本用 Movie 逐帧 */
    private static void loadGif(Context ctx, ImageView iv, String url) {
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                java.io.InputStream is = c.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                final byte[] data = bos.toByteArray();
                if (data.length == 0) return;
                iv.post(() -> GifPlayer.play(iv, data));
            } catch (Exception ignored) {
            }
        }).start();
    }

    /** 话题块：渲染成小徽章 */
    private static View topic(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText("#" + (text == null ? "" : text.trim()));
        tv.setTextSize(12);
        tv.setTextColor(COL_ACCENT);
        tv.setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4));
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(ctx, 6));
        g.setColor(0x1F58A6FF);
        tv.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(ctx, 8);
        tv.setLayoutParams(p);
        return tv;
    }


    /** 链接卡片：标题 + 摘要 + 封面 + 域名，点击打开浏览器 */
    private static View linkCard(Context ctx, Block b) {
        if (TextUtils.isEmpty(b.src) && TextUtils.isEmpty(b.text)) return null;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(COL_SURFACE);
        card.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(ctx, 12);
        card.setLayoutParams(cp);

        // 封面
        if (!TextUtils.isEmpty(b.src) && b.src.toLowerCase().endsWith(".gif")) {
            // 图片链接本身是动图
        }
        if (!TextUtils.isEmpty(b.alt) && b.alt.startsWith("http")) {
            // alt 传封面（公众号链接时）
            ImageView iv = new ImageView(ctx);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 150));
            iv.setLayoutParams(ip);
            Picasso.get().load(Utils.fullUrl(b.alt)).into(iv);
            card.addView(iv);
        }

        // 域名小字
        TextView site = new TextView(ctx);
        String host = "";
        try {
            host = new java.net.URL(b.src).getHost();
        } catch (Exception ignored) {
            host = b.src == null ? "" : b.src;
        }
        site.setText(host);
        site.setTextSize(10);
        site.setTextColor(COL_DIM);
        card.addView(site);

        // 标题
        TextView title = new TextView(ctx);
        title.setText(b.text == null ? "" : b.text);
        title.setTextSize(14.5f);
        title.setTextColor(COL_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(ctx, 4);
        title.setLayoutParams(tp);
        card.addView(title);

        // 摘要
        if (!TextUtils.isEmpty(b.alt) && !b.alt.startsWith("http")) {
            TextView desc = new TextView(ctx);
            desc.setText(b.alt);
            desc.setTextSize(12.5f);
            desc.setTextColor(COL_DIM);
            desc.setMaxLines(3);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dp2.topMargin = dp(ctx, 3);
            desc.setLayoutParams(dp2);
            card.addView(desc);
        }

        // 点击打开
        final String url = b.src;
        card.setOnClickListener(v -> {
            if (TextUtils.isEmpty(url)) return;
            try {
                android.content.Intent i = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {
            }
        });
        return card;
    }

}
