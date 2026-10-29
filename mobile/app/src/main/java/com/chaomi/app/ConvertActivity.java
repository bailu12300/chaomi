package com.chaomi.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

/**
 * 万能格式转换（App 端）。
 *
 * 与网页端同一套后端：/api/convert/{caps,data,codec}
 *   · 数据格式：JSON / CSV / TSV / YAML / Markdown ⇄ JSON / CSV / TSV / YAML / Markdown / XML / SQL
 *   · 编码工具：Base64 / URL / Hex / HTML 实体、JSON 美化压缩、时间戳↔日期、大小写
 *
 * 图片转换在网页端做（浏览器原生就能上传/下载大文件，且能直接预览），
 * App 端这里不重复实现，避免把几百 KB 的图传两遍——需要转图片时点「在网页打开」。
 */
public class ConvertActivity extends AppCompatActivity {

    private Spinner spFrom, spTo, spOp;
    private EditText etIn, etOut, etCodecIn, etCodecOut;
    private TextView tvInfo, tvCaps;
    private LinearLayout panelData, panelCodec;
    private MaterialButton tabData, tabCodec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_convert);
        Ui.fit(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) tb.setNavigationOnClickListener(v -> finish());

        spFrom = findViewById(R.id.cvFrom);
        spTo = findViewById(R.id.cvTo);
        spOp = findViewById(R.id.cvOp);
        etIn = findViewById(R.id.cvIn);
        etOut = findViewById(R.id.cvOut);
        etCodecIn = findViewById(R.id.cvCodecIn);
        etCodecOut = findViewById(R.id.cvCodecOut);
        tvInfo = findViewById(R.id.cvInfo);
        tvCaps = findViewById(R.id.cvCaps);
        panelData = findViewById(R.id.cvPanelData);
        panelCodec = findViewById(R.id.cvPanelCodec);
        tabData = findViewById(R.id.cvTabData);
        tabCodec = findViewById(R.id.cvTabCodec);

        tabData.setOnClickListener(v -> switchTab(true));
        tabCodec.setOnClickListener(v -> switchTab(false));

        findViewById(R.id.cvGo).setOnClickListener(v -> doConvertData());
        findViewById(R.id.cvCodecGo).setOnClickListener(v -> doCodec());
        findViewById(R.id.cvCopy).setOnClickListener(v -> copy(etOut.getText().toString()));
        findViewById(R.id.cvCopy2).setOnClickListener(v -> copy(etCodecOut.getText().toString()));

        loadCaps();
    }

    private void switchTab(boolean data) {
        panelData.setVisibility(data ? View.VISIBLE : View.GONE);
        panelCodec.setVisibility(data ? View.GONE : View.VISIBLE);
        tabData.setTextColor(ContextCompat.getColor(this, data ? R.color.chaomi_accent : R.color.chaomi_text_dim));
        tabCodec.setTextColor(ContextCompat.getColor(this, data ? R.color.chaomi_text_dim : R.color.chaomi_accent));
    }

    /** 拉能力清单，填充下拉框 */
    private void loadCaps() {
        Api.bg(() -> {
            try {
                Api.Caps caps = Api.convertCaps();
                runOnUiThread(() -> {
                    ArrayAdapter<String> fa = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item, caps.dataFrom);
                    spFrom.setAdapter(fa);
                    ArrayAdapter<String> ta = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item, caps.dataTo);
                    spTo.setAdapter(ta);
                    // 默认 JSON → CSV
                    for (int i = 0; i < caps.dataFrom.size(); i++) {
                        if ("json".equals(caps.dataFrom.get(i))) { spFrom.setSelection(i); break; }
                    }
                    for (int i = 0; i < caps.dataTo.size(); i++) {
                        if ("csv".equals(caps.dataTo.get(i))) { spTo.setSelection(i); break; }
                    }

                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (String[] c : caps.codec) names.add(c.length > 1 ? c[1] : c[0]);
                    ArrayAdapter<String> oa = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item, names);
                    spOp.setAdapter(oa);
                    tvCaps.setText("支持 " + caps.dataFrom.size() + " 种源格式 → "
                            + caps.dataTo.size() + " 种目标格式，" + caps.codec.size() + " 个编码工具");
                    // 把 op 列表存到 tag，转换时按下标取
                    spOp.setTag(caps.codec);
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvCaps.setText("能力清单加载失败：" + e.getMessage()));
            }
        });
    }

    private void doConvertData() {
        String from = String.valueOf(spFrom.getSelectedItem());
        String to = String.valueOf(spTo.getSelectedItem());
        String text = etIn.getText().toString();
        if (text.trim().isEmpty()) { toast("请输入要转换的内容"); return; }
        if (from.equals(to)) { toast("源格式和目标格式相同"); return; }

        setInfo("转换中…", false);
        Api.bg(() -> {
            try {
                String[] r = Api.convertData(from, to, text);
                runOnUiThread(() -> {
                    etOut.setText(r[0]);
                    setInfo("完成：" + r[1], true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> setInfo(e.getMessage() == null ? "转换失败" : e.getMessage(), false));
            }
        });
    }

    private void doCodec() {
        String text = etCodecIn.getText().toString();
        if (text.isEmpty()) { toast("请输入内容"); return; }

        String op = null;
        Object tag = spOp.getTag();
        if (tag instanceof java.util.List) {
            java.util.List<String[]> list = (java.util.List<String[]>) tag;
            int idx = spOp.getSelectedItemPosition();
            if (idx >= 0 && idx < list.size()) op = list.get(idx)[0];
        }
        if (op == null) { toast("请选择操作"); return; }

        final String fop = op;
        Api.bg(() -> {
            try {
                String r = Api.codec(fop, text);
                runOnUiThread(() -> etCodecOut.setText(r));
            } catch (Exception e) {
                runOnUiThread(() -> toast(e.getMessage() == null ? "执行失败" : e.getMessage()));
            }
        });
    }

    private void setInfo(String msg, boolean ok) {
        tvInfo.setText(msg);
        tvInfo.setTextColor(ContextCompat.getColor(this, ok ? R.color.chaomi_down : R.color.chaomi_up));
    }

    private void copy(String t) {
        if (t == null || t.isEmpty()) { toast("没有可复制的内容"); return; }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("result", t));
                toast("已复制");
            }
        } catch (Exception e) {
            toast("复制失败");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
