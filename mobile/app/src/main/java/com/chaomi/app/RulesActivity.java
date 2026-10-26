package com.chaomi.app;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * 跳过规则管理：查看 / 启停 / 导入（链接或粘贴）/ 删除 / 刷新在线规则。
 */
public class RulesActivity extends AppCompatActivity {

    private final List<SkipRule> rules = new ArrayList<>();
    private RuleAdapter adapter;
    private TextView ruleStat, syncInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rules);
        Ui.fit(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) tb.setNavigationOnClickListener(v -> finish());

        ruleStat = findViewById(R.id.ruleStat);
        RecyclerView rv = findViewById(R.id.ruleList);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RuleAdapter(rules,
                (r, on) -> {
                    RuleStore.setEnabled(this, r.id, on);
                    refreshStat();
                },
                r -> confirmDelete(r));
        rv.setAdapter(adapter);

        SwipeRefreshLayout swipe = findViewById(R.id.swipe);
        swipe.setOnRefreshListener(() -> {
            load();
            swipe.setRefreshing(false);
        });

        syncInfo = findViewById(R.id.syncInfo);
        findViewById(R.id.btnSync).setOnClickListener(v -> syncFromServer());

        findViewById(R.id.btnImportUrl).setOnClickListener(v -> importFromUrl());
        findViewById(R.id.btnImportText).setOnClickListener(v -> importFromText());

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        rules.clear();
        rules.addAll(RuleStore.all(this));
        adapter.notifyDataSetChanged();
        refreshStat();
        refreshSyncInfo();
    }

    private void refreshStat() {
        int total = rules.size();
        int on = 0;
        int builtin = 0;
        for (SkipRule r : rules) {
            if (r.enabled) on++;
            if ("builtin".equals(r.source)) builtin++;
        }
        ruleStat.setText("共 " + total + " 条规则 · 启用 " + on
                + " · 内置 " + builtin + " · 导入 " + (total - builtin)
                + "\n长按规则可删除");
    }

    /** 从 URL 导入（支持直接给规则 JSON 地址） */
    private void importFromUrl() {
        EditText et = new EditText(this);
        et.setHint("https://…/rules.json");
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("从链接导入规则")
                .setMessage("粘贴规则 JSON 的直链地址")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (url.isEmpty()) return;
                    doImportUrl(url);
                })
                .show();
    }

    private void doImportUrl(String url) {
        Toast.makeText(this, "下载中…", Toast.LENGTH_SHORT).show();
        Api.bg(() -> {
            try {
                String json = Http.get(url, null);
                int n = RuleStore.importJson(this, json, "url");
                runOnUiThread(() -> {
                    if (n == 0) {
                        Toast.makeText(this, "解析不出规则，请检查格式", Toast.LENGTH_LONG).show();
                    } else {
                        RuleStore.addSource(this, url);
                        Toast.makeText(this, "导入 " + n + " 条规则", Toast.LENGTH_SHORT).show();
                        load();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 粘贴 JSON 导入 */
    private void importFromText() {
        EditText et = new EditText(this);
        et.setHint("{\"pkg\":\"com.xxx\",\"rules\":[{\"matches\":[\"跳过\"]}]}");
        et.setMinLines(8);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("粘贴规则 JSON")
                .setMessage("支持单条规则、规则数组、以及 {rules:[...]} 包裹格式")
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (d, w) -> {
                    String txt = et.getText().toString().trim();
                    if (txt.isEmpty()) return;
                    try {
                        int n = RuleStore.importJson(this, txt, "import");
                        if (n == 0) {
                            Toast.makeText(this, "解析不出规则，请检查格式", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "导入 " + n + " 条规则", Toast.LENGTH_SHORT).show();
                            load();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "格式错误：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void confirmDelete(SkipRule r) {
        new AlertDialog.Builder(this)
                .setTitle("删除规则")
                .setMessage((r.name == null ? r.pkg : r.name) + "\n\n删除后不会再匹配这条规则。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    RuleStore.remove(this, r.id);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    load();
                })
                .show();
    }

    /** 从服务器拉取在线规则库 */
    private void syncFromServer() {
        Toast.makeText(this, "同步中…", Toast.LENGTH_SHORT).show();
        Api.bg(() -> {
            RuleSync.Result r = RuleSync.sync(this, true);
            runOnUiThread(() -> {
                Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
                load();
                refreshSyncInfo();
            });
        });
    }

    private void refreshSyncInfo() {
        if (syncInfo == null) return;
        int shake = AdSkipPrefs.shakeStops(this);
        syncInfo.setText("规则库 " + RuleSync.lastSyncText(this)
                + (shake > 0 ? "\n摇一摇拦截 " + shake + " 次" : ""));
    }

}
