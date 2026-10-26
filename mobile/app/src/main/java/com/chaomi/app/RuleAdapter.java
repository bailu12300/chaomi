package com.chaomi.app;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

/** 规则列表 */
public class RuleAdapter extends RecyclerView.Adapter<RuleAdapter.VH> {

    public interface OnToggle { void onToggle(SkipRule r, boolean on); }
    public interface OnLongClick { void onLongClick(SkipRule r); }

    private final List<SkipRule> items;
    private final OnToggle onToggle;
    private final OnLongClick onLongClick;

    public RuleAdapter(List<SkipRule> items, OnToggle t, OnLongClick l) {
        this.items = items;
        this.onToggle = t;
        this.onLongClick = l;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_rule, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        SkipRule r = items.get(pos);
        h.name.setText(TextUtils.isEmpty(r.name) ? r.pkg : r.name);
        h.pkg.setText(r.pkg + srcTag(r.source));

        StringBuilder sb = new StringBuilder();
        sb.append("动作 ").append(r.rules.size()).append(" 条");
        if (!r.activityIds.isEmpty()) sb.append(" · 限定 ").append(r.activityIds.size()).append(" 个 Activity");
        // 展示前两个动作的匹配词
        int shown = 0;
        for (SkipRule.Action a : r.rules) {
            if (shown >= 2) {
                sb.append(" …");
                break;
            }
            sb.append("\n· ").append(a.action);
            if (!a.matches.isEmpty()) {
                sb.append(" ").append(String.join(" / ", a.matches.size() > 4
                        ? a.matches.subList(0, 4) : a.matches));
            }
            if (!a.viewIds.isEmpty()) sb.append(" #").append(a.viewIds.get(0));
            if (a.position != null) sb.append(" @").append((int) a.position[0])
                    .append(",").append((int) a.position[1]);
            shown++;
        }
        h.detail.setText(sb.toString());

        h.sw.setOnCheckedChangeListener(null);
        h.sw.setChecked(r.enabled);
        h.sw.setOnCheckedChangeListener((b, on) -> {
            if (onToggle != null) onToggle.onToggle(r, on);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (onLongClick != null) onLongClick.onLongClick(r);
            return true;
        });
    }

    private String srcTag(String s) {
        if (s == null) return "";
        switch (s) {
            case "builtin": return "  · 内置";
            case "import": return "  · 导入";
            case "url": return "  · 在线";
            default: return s.isEmpty() ? "" : "  · " + s;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView name, pkg, detail;
        SwitchMaterial sw;

        VH(View v) {
            super(v);
            name = v.findViewById(R.id.rName);
            pkg = v.findViewById(R.id.rPkg);
            detail = v.findViewById(R.id.rDetail);
            sw = v.findViewById(R.id.rSwitch);
        }
    }
}
