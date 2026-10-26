package com.chaomi.app;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Set;

/** 自动跳过广告设置页 */
public class AdSkipActivity extends AppCompatActivity {

    private TextView accState, battState, hitInfo;
    private MaterialButton btnAcc, btnBatt, btnSave;
    private SwitchMaterial swEnabled, swAlive;
    private EditText etDelay, etWords, etAllow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_adskip);
        Ui.fit(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) tb.setNavigationOnClickListener(v -> finish());

        accState = findViewById(R.id.accState);
        battState = findViewById(R.id.battState);
        hitInfo = findViewById(R.id.hitInfo);
        btnAcc = findViewById(R.id.btnAcc);
        btnBatt = findViewById(R.id.btnBatt);
        btnSave = findViewById(R.id.btnSave);
        swEnabled = findViewById(R.id.swEnabled);
        swAlive = findViewById(R.id.swAlive);
        etDelay = findViewById(R.id.etDelay);
        etWords = findViewById(R.id.etWords);
        etAllow = findViewById(R.id.etAllow);

        load();

        btnAcc.setOnClickListener(v -> openAccessibility());
        btnBatt.setOnClickListener(v -> openBattery());
        btnSave.setOnClickListener(v -> save());
    }

    private void load() {
        swEnabled.setChecked(AdSkipPrefs.enabled(this));
        swAlive.setChecked(AdSkipPrefs.sp(this).getBoolean("keepalive", true));
        etDelay.setText(String.valueOf(AdSkipPrefs.delayMs(this)));
        etWords.setText(join(AdSkipPrefs.words(this)));
        etAllow.setText(join(AdSkipPrefs.whitelist(this)));
        refreshState();
    }

    private String join(Set<String> s) {
        StringBuilder sb = new StringBuilder();
        for (String x : s) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(x);
        }
        return sb.toString();
    }

    private void refreshState() {
        boolean acc = KeepAliveService.isAccessibilityOn(this);
        accState.setText(acc ? "无障碍权限：已开启" : "无障碍权限：未开启");
        accState.setTextColor(acc ? 0xFF3fb950 : 0xFFf85149);
        btnAcc.setText(acc ? "已开启" : "去开启");
        btnAcc.setEnabled(!acc);

        boolean batt = KeepAliveService.isIgnoringBattery(this);
        battState.setText(batt ? "后台保活：已加入白名单" : "后台保活：未加入白名单（易被清理）");
        battState.setTextColor(batt ? 0xFF8b949e : 0xFFe3b341);
        btnBatt.setVisibility(batt ? View.GONE : View.VISIBLE);

        int hits = AdSkipPrefs.hits(this);
        String last = AdSkipPrefs.lastHit(this);
        hitInfo.setText("已自动跳过 " + hits + " 次"
                + (last.isEmpty() ? "" : "\n最近：" + last));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    /** 跳系统无障碍设置页；国产 ROM 这里路径可能不同，给两级兜底 */
    private void openAccessibility() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "在列表里找到「超米」并开启", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                startActivity(i);
            } catch (Exception e2) {
                Toast.makeText(this, "请手动到 设置 → 无障碍 中开启", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 请求忽略电池优化 */
    private void openBattery() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        openAppDetail();
    }

    /** 应用详情页：自启动/后台限制通常在这里 */
    private void openAppDetail() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "请在系统设置中手动允许后台运行", Toast.LENGTH_LONG).show();
        }
    }

    private void save() {
        AdSkipPrefs.setEnabled(this, swEnabled.isChecked());
        AdSkipPrefs.sp(this).edit()
                .putBoolean("keepalive", swAlive.isChecked()).apply();

        int d;
        try {
            d = Integer.parseInt(etDelay.getText().toString().trim());
        } catch (Exception e) {
            d = 120;
        }
        if (d < 0) d = 0;
        if (d > 3000) d = 3000;
        AdSkipPrefs.setDelayMs(this, d);

        AdSkipPrefs.setWords(this, etWords.getText().toString().trim());
        AdSkipPrefs.setWhitelist(this, etAllow.getText().toString().trim());

        if (swAlive.isChecked()) {
            KeepAliveService.start(this);
            KeepAliveReceiver.schedule(this);
        } else {
            KeepAliveService.stop(this);
            KeepAliveReceiver.cancel(this);
        }

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        refreshState();
    }
}
