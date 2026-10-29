package com.chaomi.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** 服务监控列表项：服务名 + 状态 + 延迟 */
public class WatchAdapter extends RecyclerView.Adapter<WatchAdapter.VH> {

    private final List<WatchResult> items;

    public WatchAdapter(List<WatchResult> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_watch, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        WatchResult w = items.get(pos);
        h.name.setText(w.name);

        if (w.ok) {
            h.status.setText("正常");
            h.status.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.chaomi_down));
            h.dot.setBackgroundResource(R.drawable.dot_ok);
            h.lat.setText(latText(w.latencyMs));
            h.lat.setTextColor(ContextCompat.getColor(h.itemView.getContext(), latColor(w.latencyMs)));
            h.err.setVisibility(View.GONE);
        } else {
            h.status.setText("异常" + (w.failStreak > 0 ? "（连续 " + w.failStreak + " 次）" : ""));
            h.status.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.chaomi_up));
            h.dot.setBackgroundResource(R.drawable.dot_bad);
            h.lat.setText("—");
            h.lat.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.chaomi_text_dim));
            if (w.error != null && !w.error.isEmpty()) {
                h.err.setVisibility(View.VISIBLE);
                h.err.setText("↳ " + w.error);
            } else {
                h.err.setVisibility(View.GONE);
            }
        }
        h.time.setText(Utils.formatTime(w.checkedAt));
    }

    private String latText(long ms) {
        return ms + " ms";
    }

    private int latColor(long ms) {
        if (ms < 200) return R.color.chaomi_down;
        if (ms < 800) return R.color.chaomi_gold;
        return R.color.chaomi_up;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView name, status, lat, err, time;
        View dot;

        VH(View v) {
            super(v);
            name = v.findViewById(R.id.wName);
            status = v.findViewById(R.id.wStatus);
            lat = v.findViewById(R.id.wLat);
            err = v.findViewById(R.id.wErr);
            time = v.findViewById(R.id.wTime);
            dot = v.findViewById(R.id.wDot);
        }
    }
}
