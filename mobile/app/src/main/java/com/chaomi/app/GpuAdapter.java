package com.chaomi.app;

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
import java.util.Locale;

/**
 * 显卡行情列表：零售卡与云 GPU 混排，各自标注来源。
 *
 * 价格展示规则：
 *   零售卡    $1,309.98   ≈ ¥8,747     单位 USD/张
 *   云 GPU    $0.70/小时  ≈ ¥4.67/小时  单位 USD/GPU/小时
 * 有争议的零售价照常展示，下方加一行红字说明溢价幅度。
 */
public class GpuAdapter extends RecyclerView.Adapter<GpuAdapter.VH> {

    public interface OnClick { void onClick(GpuModel m); }

    private final List<GpuModel> items;
    private final OnClick click;

    public GpuAdapter(List<GpuModel> items, OnClick c) {
        this.items = items;
        this.click = c;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_gpu, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        GpuModel m = items.get(pos);
        android.content.Context c = h.itemView.getContext();

        h.model.setText("RTX " + m.model);

        // 来源标签：零售卡 / 云GPU
        boolean cloud = m.isCloud();
        h.kind.setText(cloud ? "云GPU" : "零售卡");
        h.kind.setTextColor(ContextCompat.getColor(c,
                cloud ? R.color.chaomi_accent : R.color.chaomi_text_dim));

        // 规格 / 库存说明
        StringBuilder sub = new StringBuilder();
        if (m.spec != null && !m.spec.isEmpty()) sub.append(m.spec);
        if (cloud && m.extra != null && !m.extra.isEmpty()) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(m.extra);
        }
        h.spec.setText(sub.length() == 0 ? "—" : sub.toString());

        // 价格：云 GPU 带 /小时
        if (cloud) {
            h.usd.setText(String.format(Locale.US, "$%.2f/时", m.priceUsd));
            h.cny.setText(String.format(Locale.CHINA, "≈ ¥%.2f/时", m.priceCny));
        } else {
            h.usd.setText(String.format(Locale.US, "$%,.2f", m.priceUsd));
            h.cny.setText(String.format(Locale.CHINA, "≈ ¥%,.0f", m.priceCny));
        }

        // 库存：零售看 availability，云看 extra 里的卡数
        if (cloud) {
            h.avail.setText("租赁");
            h.avail.setTextColor(ContextCompat.getColor(c, R.color.chaomi_accent));
        } else {
            boolean inStock = m.availability == null || m.availability.isEmpty()
                    || m.availability.toLowerCase().contains("instock");
            h.avail.setText(inStock ? "有货" : "缺货");
            h.avail.setTextColor(ContextCompat.getColor(c,
                    inStock ? R.color.chaomi_down : R.color.chaomi_text_dim));
        }

        // 有争议：照常显示，但明确标出原因
        if (m.disputed && m.disputeReason != null && !m.disputeReason.isEmpty()) {
            h.dispute.setVisibility(View.VISIBLE);
            h.dispute.setText("⚠ 价格有争议 · " + m.disputeReason);
        } else {
            h.dispute.setVisibility(View.GONE);
        }

        if (m.image != null && !m.image.isEmpty()) {
            h.image.setVisibility(View.VISIBLE);
            Picasso.get().load(Utils.fullUrl(m.image)).into(h.image);
        } else {
            h.image.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> {
            if (click != null) click.onClick(m);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView model, kind, spec, usd, cny, avail, dispute;
        ImageView image;

        VH(View v) {
            super(v);
            model = v.findViewById(R.id.gModel);
            kind = v.findViewById(R.id.gKind);
            spec = v.findViewById(R.id.gSpec);
            usd = v.findViewById(R.id.gUsd);
            cny = v.findViewById(R.id.gCny);
            avail = v.findViewById(R.id.gAvail);
            dispute = v.findViewById(R.id.gDispute);
            image = v.findViewById(R.id.gImage);
        }
    }
}
