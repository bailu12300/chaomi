package com.chaomi.app;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.squareup.picasso.Picasso;

import java.util.List;

/**
 * 资讯列表适配器。
 *
 * 展示原则：
 *   · 标题优先显示中文译文（服务端已翻译落库，**断网也能看中文**）
 *   · 有译文时把原文作为副标题，方便对照
 *   · 点击卡片用系统浏览器打开原文
 */
class RssAdapter extends RecyclerView.Adapter<RssAdapter.H> {

    interface OnClick { void onClick(RssItem it); }

    private final List<RssItem> list;
    private final OnClick cb;

    RssAdapter(List<RssItem> list, OnClick cb) {
        this.list = list;
        this.cb = cb;
    }

    static class H extends RecyclerView.ViewHolder {
        ImageView img;
        TextView title, orig, summary, meta;

        H(View v) {
            super(v);
            img = v.findViewById(R.id.rssImg);
            title = v.findViewById(R.id.rssTitle);
            orig = v.findViewById(R.id.rssOrig);
            summary = v.findViewById(R.id.rssSummary);
            meta = v.findViewById(R.id.rssMeta);
        }
    }

    @NonNull
    @Override
    public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_rss, parent, false);
        return new H(v);
    }

    @Override
    public void onBindViewHolder(@NonNull H h, int position) {
        RssItem it = list.get(position);
        Context ctx = h.itemView.getContext();

        h.title.setText(it.displayTitle());

        // 原文副标题：只有译文存在且与原文不同才显示
        String orig = it.title == null ? "" : it.title;
        boolean showOrig = it.titleZh != null && !it.titleZh.isEmpty()
                && !TextUtils.equals(it.titleZh, orig);
        h.orig.setVisibility(showOrig ? View.VISIBLE : View.GONE);
        if (showOrig) h.orig.setText(orig);

        String sum = it.displaySummary();
        h.summary.setVisibility(TextUtils.isEmpty(sum) ? View.GONE : View.VISIBLE);
        if (!TextUtils.isEmpty(sum)) h.summary.setText(sum);

        StringBuilder meta = new StringBuilder();
        if (!TextUtils.isEmpty(it.source)) meta.append(it.source);
        String t = Utils.formatTime(it.pubAt);
        if (!TextUtils.isEmpty(t)) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(t);
        }
        h.meta.setText(meta.toString());

        if (!TextUtils.isEmpty(it.image)) {
            h.img.setVisibility(View.VISIBLE);
            Picasso.get()
                    .load(it.image)
                    .placeholder(android.R.color.transparent)
                    .into(h.img);
        } else {
            h.img.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> {
            if (cb != null) cb.onClick(it);
        });
        h.itemView.setTag(position);
    }

    @Override
    public int getItemCount() {
        return list == null ? 0 : list.size();
    }
}
