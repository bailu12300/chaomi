package com.chaomi.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * 行内富文本：把轻量标记渲染成原生样式，效果对标公众号的「加粗 / 斜体 / 高亮 / 删除线 / 行内代码」。
 *
 * 标记语法（工具栏按钮会自动插入，用户无需记忆）：
 *   **粗体**      → 加粗
 *   *斜体*        → 斜体
 *   ==高亮==      → 背景高亮
 *   ~~删除线~~    → 删除线
 *   `行内代码`    → 等宽 + 浅底
 *
 * 设计要点：
 *   · 解析在渲染时进行，数据库里存的仍是**带标记的纯文本**，
 *     所以网页端 / 老客户端都能照常显示，不会出现样式丢失导致的内容缺失。
 *   · 严格成对匹配：找不到闭合标记就原样保留，绝不吞掉用户文字。
 */
class InlineRenderer {

    private InlineRenderer() {}

    /** 把带标记的文本渲染进 TextView（自动清空旧 span） */
    static void apply(TextView tv, String raw) {
        if (tv == null) return;
        Context ctx = tv.getContext();
        if (raw == null || raw.isEmpty()) {
            tv.setText("");
            return;
        }
        tv.setText(build(ctx, raw));
    }

    /** 生成带样式的 CharSequence */
    static CharSequence build(Context ctx, String raw) {
        if (raw == null || raw.isEmpty()) return "";
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        int n = raw.length();

        while (i < n) {
            // 依次尝试各类标记。顺序有讲究：** 必须在 * 之前判断，
            // 否则 "**粗**" 会被 * 先吃掉，变成空加粗。
            int[] hit = null;
            String open = null;
            String close = null;

            if (raw.startsWith("**", i)) {
                open = "**"; close = "**";
            } else if (raw.startsWith("==", i)) {
                open = "=="; close = "==";
            } else if (raw.startsWith("~~", i)) {
                open = "~~"; close = "~~";
            } else if (raw.startsWith("`", i)) {
                open = "`"; close = "`";
            } else if (raw.charAt(i) == '*') {
                open = "*"; close = "*";
            }

            if (open == null) {
                out.append(raw.charAt(i));
                i++;
                continue;
            }

            int contentStart = i + open.length();
            int end = raw.indexOf(close, contentStart);
            // 闭合必须存在、且中间有内容、且不为空串
            if (end < 0 || end == contentStart) {
                out.append(raw.charAt(i));
                i++;
                continue;
            }

            String inner = raw.substring(contentStart, end);
            int spanStart = out.length();
            out.append(inner);
            int spanEnd = out.length();

            applyStyle(ctx, out, open, spanStart, spanEnd);
            i = end + close.length();
        }
        return out;
    }

    /** 去掉标记，得到纯文本（用于摘要 / 列表预览 / 字数统计） */
    static String plain(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw;
        s = s.replace("**", "");
        s = s.replace("==", "");
        s = s.replace("~~", "");
        s = s.replace("`", "");
        // 单个 * 只在成对时才去掉，避免误删正文里的星号
        s = s.replaceAll("\\*([^*\\n]+)\\*", "$1");
        return s;
    }

    private static void applyStyle(Context ctx, SpannableStringBuilder sb,
                                   String open, int start, int end) {
        if (end <= start) return;
        CharacterStyle span;
        switch (open) {
            case "**":
                span = new StyleSpan(Typeface.BOLD);
                break;
            case "*":
                span = new StyleSpan(Typeface.ITALIC);
                break;
            case "==":
                span = new BackgroundColorSpan(highlightColor(ctx));
                break;
            case "~~":
                span = new StrikethroughSpan();
                break;
            case "`":
                span = new InlineCodeSpan(ctx);
                break;
            default:
                return;
        }
        sb.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 高亮底色：浅色主题用暖黄，保证黑字可读 */
    static int highlightColor(Context ctx) {
        return 0xFFFFE58F;
    }

    /** 行内代码：等宽字体 + 浅灰底 + 强调色文字 */
    private static class InlineCodeSpan extends CharacterStyle {
        private final int bg;
        private final int fg;
        private final float size;

        InlineCodeSpan(Context ctx) {
            this.bg = ContextCompat.getColor(ctx, R.color.chaomi_surface_2);
            this.fg = ContextCompat.getColor(ctx, R.color.chaomi_accent);
            this.size = 0;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.bgColor = bg;
            tp.setColor(fg);
            tp.setTypeface(Typeface.MONOSPACE);
            if (size > 0) tp.setTextSize(size);
        }
    }
}
