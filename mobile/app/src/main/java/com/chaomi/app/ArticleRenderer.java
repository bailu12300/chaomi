package com.chaomi.app;

import android.content.Context;
import androidx.core.content.ContextCompat;
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

    /** 把 blocks 渲染进容器（会先清空） */
    static void render(Context ctx, LinearLayout box, List<Block> blocks) {
        box.removeAllViews();
        if (blocks == null || blocks.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText("（正文为空）");
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_text_dim));
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

    /** 有序列表序号：整篇文章累计（跨相邻 ol 块连续） */
    private static int olCounter = 0;

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
                olCounter = 0;
                return listItem(ctx, b.text, "•");
            case "ol":
                return listItem(ctx, b.text, String.valueOf(++olCounter) + ".");
            case "img":
                return image(ctx, b.src, b.alt);
            case "topic":
                return topic(ctx, b.text);
            case "video":
                return video(ctx, b);
            case "hr":
                return divider(ctx);
            case "link":
                return linkCard(ctx, b);
            case "p":
            default:
                olCounter = 0;
                if (TextUtils.isEmpty(b.text)) return null;
                return paragraph(ctx, b.text, "center".equals(b.style));
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
        // 标题也支持行内标记（如整句加粗 / 局部高亮），但默认已经是粗体
        InlineRenderer.apply(tv, text == null ? "" : text);
        tv.setTextSize(sp);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_text));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLineSpacing(dp(ctx, 4), 1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(ctx, 18);
        p.bottomMargin = dp(ctx, 4);
        tv.setLayoutParams(p);
        return tv;
    }

    private static TextView paragraph(Context ctx, String text, boolean center) {
        TextView tv = new TextView(ctx);
        InlineRenderer.apply(tv, text);
        tv.setTextSize(15.5f);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_text));
        tv.setLineSpacing(dp(ctx, 8), 1f);
        tv.setTextIsSelectable(true);
        if (center) tv.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(ctx, 10);
        tv.setLayoutParams(p);
        return tv;
    }

    private static View listItem(Context ctx, String text, String marker) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(lp(dp(ctx, 6), 0));

        TextView dot = new TextView(ctx);
        dot.setText(marker == null ? "•" : marker);
        dot.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_accent));
        dot.setTextSize(15);
        dot.setMinWidth(dp(ctx, 18));
        dot.setPadding(0, 0, dp(ctx, 8), 0);
        row.addView(dot);

        TextView tv = new TextView(ctx);
        InlineRenderer.apply(tv, text == null ? "" : text);
        tv.setTextSize(15);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_text));
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
        bar.setBackgroundColor(ContextCompat.getColor(ctx, R.color.chaomi_accent));
        bar.setLayoutParams(bp);
        wrap.addView(bar);

        TextView tv = new TextView(ctx);
        InlineRenderer.apply(tv, text == null ? "" : text);
        tv.setTextSize(14.5f);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_text));
        tv.setLineSpacing(dp(ctx, 6), 1f);
        tv.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        tv.setBackgroundColor(ContextCompat.getColor(ctx, R.color.chaomi_quote_bg));
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
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_syntax_str));
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLineSpacing(dp(ctx, 4), 1f);
        tv.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        tv.setBackgroundColor(ContextCompat.getColor(ctx, R.color.chaomi_code_bg));
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

        // 有图注时包一层：图片 + 居中小字说明（对标公众号的图片说明）
        if (alt == null || alt.trim().isEmpty()) return iv;

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutParams(p);
        iv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(iv);

        TextView cap = new TextView(ctx);
        cap.setText(alt.trim());
        cap.setTextSize(12);
        cap.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_text_dim));
        cap.setGravity(android.view.Gravity.CENTER);
        cap.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), 0);
        wrap.addView(cap);
        return wrap;
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
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_accent));
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
        card.setBackgroundColor(ContextCompat.getColor(ctx, R.color.chaomi_surface));
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
        site.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_text_dim));
        card.addView(site);

        // 标题
        TextView title = new TextView(ctx);
        title.setText(b.text == null ? "" : b.text);
        title.setTextSize(14.5f);
        title.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_text));
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
            desc.setTextColor(ContextCompat.getColor(ctx, R.color.chaomi_text_dim));
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


    /** 视频块：封面 + 播放按钮，点击用系统播放器/浏览器打开 */
    private static View video(Context ctx, Block b) {
        if (TextUtils.isEmpty(b.src) && TextUtils.isEmpty(b.alt)) return null;

        android.widget.FrameLayout box = new android.widget.FrameLayout(ctx);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 200));
        bp.topMargin = dp(ctx, 12);
        box.setLayoutParams(bp);
        box.setBackgroundColor(ContextCompat.getColor(ctx, R.color.chaomi_surface_2));

        // 封面
        if (!TextUtils.isEmpty(b.alt)) {
            ImageView cover = new ImageView(ctx);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Picasso.get().load(Utils.fullUrl(b.alt)).into(cover);
            box.addView(cover);
        }

        // 播放按钮
        TextView play = new TextView(ctx);
        play.setText("▶");
        play.setTextSize(30);
        play.setTextColor(0xFFFFFFFF);
        play.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0x99000000);
        play.setBackground(g);
        int sz = dp(ctx, 60);
        android.widget.FrameLayout.LayoutParams pp =
                new android.widget.FrameLayout.LayoutParams(sz, sz, android.view.Gravity.CENTER);
        play.setLayoutParams(pp);
        box.addView(play);

        // 角标：来源类型
        if (!TextUtils.isEmpty(b.text)) {
            TextView tag = new TextView(ctx);
            tag.setText("视频");
            tag.setTextSize(10);
            tag.setTextColor(0xFFFFFFFF);
            tag.setBackgroundColor(0xCC000000);
            tag.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
            android.widget.FrameLayout.LayoutParams tp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.START | android.view.Gravity.TOP);
            tp.setMargins(dp(ctx, 8), dp(ctx, 8), 0, 0);
            tag.setLayoutParams(tp);
            box.addView(tag);
        }

        final String url = b.src;
        box.setOnClickListener(v -> {
            if (TextUtils.isEmpty(url)) return;
            try {
                android.content.Intent i = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {
            }
        });
        return box;
    }

    /** 分割线 */
    private static View divider(Context ctx) {
        View line = new View(ctx);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        p.topMargin = dp(ctx, 16);
        p.bottomMargin = dp(ctx, 6);
        line.setLayoutParams(p);
        line.setBackgroundColor(ContextCompat.getColor(ctx, R.color.chaomi_border));
        return line;
    }


}
