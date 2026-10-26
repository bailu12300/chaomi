package com.chaomi.app;

import androidx.core.content.ContextCompat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class HwAdapter extends RecyclerView.Adapter<HwAdapter.VH> {

    public interface OnItemClick { void onClick(HwModel m); }

    private final List<HwModel> items;
    private final OnItemClick click;

    public HwAdapter(List<HwModel> items) { this(items, null); }

    public HwAdapter(List<HwModel> items, OnItemClick click) {
        this.items = items;
        this.click = click;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hw, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        HwModel m = items.get(pos);
        h.cat.setText(m.catLabel == null ? "" : m.catLabel);
        h.model.setText(m.model == null ? (m.title == null ? "" : m.title) : m.model);

        String spec = m.spec == null ? "" : m.spec.trim();
        String shop = (m.platform == null ? "" : m.platform) + " · " + (m.shop == null ? "" : m.shop);
        h.shop.setText(spec.isEmpty() ? shop : spec + "  ·  " + shop);

        if (m.noMatch || "nomatch".equals(m.priceStatus)) {
            h.price.setText("暂无准确报价");
            h.price.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.chaomi_text_dim));
        } else {
            h.price.setText(Utils.priceStr(m.price));
            h.price.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.chaomi_text));
        }

        // 涨跌徽标：涨=红、跌=绿（国内习惯）。同时显示实际差价与百分比
        String pct = m.hasChange ? String.format(java.util.Locale.US, "%+.2f%%", m.changePct) : "";
        if (m.hasChange && m.changeAbs != 0) {
            String abs = Math.abs(m.changeAbs) >= 100
                    ? String.format(java.util.Locale.US, "%+.0f", m.changeAbs)
                    : String.format(java.util.Locale.US, "%+.2f", m.changeAbs);
            pct = abs + " / " + pct;
        }
        if ("up".equals(m.trend)) {
            h.trend.setText(pct.isEmpty() ? "↑ 涨" : "↑ " + pct);
            h.trend.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.chaomi_up));
        } else if ("down".equals(m.trend)) {
            h.trend.setText(pct.isEmpty() ? "↓ 跌" : "↓ " + pct);
            h.trend.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.chaomi_down));
        } else if (m.hasChange) {
            h.trend.setText(pct);
            h.trend.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.chaomi_text_dim));
        } else {
            h.trend.setText("— 平");
            h.trend.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.chaomi_text_dim));
        }

        h.itemView.setOnClickListener(v -> { if (click != null) click.onClick(m); });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView cat, model, shop, price, trend;

        VH(View v) {
            super(v);
            cat = v.findViewById(R.id.hwCat);
            model = v.findViewById(R.id.hwModel);
            shop = v.findViewById(R.id.hwShop);
            price = v.findViewById(R.id.hwPrice);
            trend = v.findViewById(R.id.hwTrend);
        }
    }
}
