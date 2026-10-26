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

/** Amazon 显卡行情列表：美元原价 + 人民币换算 */
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

        h.model.setText("RTX " + m.model);
        h.spec.setText(m.spec == null || m.spec.isEmpty() ? "—" : m.spec);

        // 美元原价
        h.usd.setText(String.format(Locale.US, "$%,.2f", m.priceUsd));
        // 人民币换算
        h.cny.setText(String.format(Locale.CHINA, "≈ ¥%,.0f", m.priceCny));

        // 库存状态
        boolean inStock = m.availability == null || m.availability.isEmpty()
                || m.availability.toLowerCase().contains("instock");
        h.avail.setText(inStock ? "有货" : "缺货");
        h.avail.setTextColor(ContextCompat.getColor(h.itemView.getContext(),
                inStock ? R.color.chaomi_down : R.color.chaomi_text_dim));

        if (m.image != null && !m.image.isEmpty()) {
            Picasso.get().load(Utils.fullUrl(m.image)).into(h.image);
        } else {
            h.image.setImageDrawable(null);
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
        TextView model, spec, usd, cny, avail;
        ImageView image;

        VH(View v) {
            super(v);
            model = v.findViewById(R.id.gModel);
            spec = v.findViewById(R.id.gSpec);
            usd = v.findViewById(R.id.gUsd);
            cny = v.findViewById(R.id.gCny);
            avail = v.findViewById(R.id.gAvail);
            image = v.findViewById(R.id.gImage);
        }
    }
}
