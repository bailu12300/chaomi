package com.mitiaotiao.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * 米跳跳 · 主页。
 *
 * 定位：极简、专注「跳过广告」这一件事。打开即见服务状态，一键开启。
 *
 * ★ 关于「设置里打不开无障碍」：
 *   从 Android 13 起，侧载安装的 APK（不是从应用商店装的）会被系统标记为
 *   「受限制的应用」。这类应用的无障碍开关在设置里是**灰色不可点**的，
 *   用户点上去毫无反应，看起来像"应用坏了"。
 *   必须在「应用信息 → 右上角 ⋮ → 允许受限设置」里放行，开关才会变可用。
 *
 *   本页三层引导（对齐李跳跳的用户体验）：
 *     1) 能开就直接开：Android 11+ 直达「本应用的无障碍详情页」，
 *        不用把用户丢到一长串无障碍列表里自己找
 *     2) 检测到服务根本没被系统列出（= 被受限屏蔽）→ 显示排查卡片，
 *        一键跳到应用信息页，让用户点右上角允许
 *     3) 兜底：跳总设置列表
 */
public class MainActivity extends AppCompatActivity {

    private TextView accState, hitInfo, restrictedDesc;
    private View restrictedBox;
    private SwitchMaterial swEnabled;
    private MaterialButton btnAcc, btnRules, btnAppInfo, btnAccDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.fit(this);

        accState = findViewById(R.id.accState);
        hitInfo = findViewById(R.id.hitInfo);
        swEnabled = findViewById(R.id.swEnabled);
        btnAcc = findViewById(R.id.btnAcc);
        btnRules = findViewById(R.id.btnRules);
        restrictedBox = findViewById(R.id.restrictedBox);
        restrictedDesc = findViewById(R.id.restrictedDesc);
        btnAppInfo = findViewById(R.id.btnAppInfo);
        btnAccDetail = findViewById(R.id.btnAccDetail);

        swEnabled.setChecked(AdSkipPrefs.enabled(this));
        swEnabled.setOnCheckedChangeListener((b, on) -> {
            AdSkipPrefs.setEnabled(this, on);
            refresh();
        });

        btnAcc.setOnClickListener(v -> openAccessibilityDetail());
        btnRules.setOnClickListener(v -> startActivity(new Intent(this, RulesActivity.class)));
        btnAppInfo.setOnClickListener(v -> openAppInfo());
        btnAccDetail.setOnClickListener(v -> openAccessibilityDetail());

        AdSkipPrefs.sp(this).edit().putBoolean("first_run", false).apply();
        startKeepAlive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void startKeepAlive() {
        try {
            KeepAliveService.start(this);
            KeepAliveScheduler.schedule(this);
        } catch (Exception ignored) {
        }
        // Android 13+ 通知权限（前台服务通知需要）
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{"android.permission.POST_NOTIFICATIONS"}, 9301);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void refresh() {
        boolean acc = KeepAliveService.isAccessibilityOn(this);
        accState.setText(acc ? "无障碍服务已开启" : "无障碍服务未开启");
        accState.setTextColor(ContextCompat.getColor(this,
                acc ? R.color.chaomi_down : R.color.chaomi_up));
        btnAcc.setText(acc ? "已开启" : "一键开启");

        // 未开启时给出针对性引导
        if (acc) {
            restrictedBox.setVisibility(View.GONE);
        } else {
            restrictedBox.setVisibility(View.VISIBLE);
            if (!isServiceListed()) {
                // 服务被系统藏起来了 → 一定是受限设置问题
                restrictedDesc.setText(
                        "这个 APK 是直接安装的（不是从应用商店装的），Android 13 及以上会把它"
                        + "标记为「受限制」，无障碍开关直接锁死、点了没反应。\n\n"
                        + "解决：点下面的「去允许受限设置」，在应用信息页点右上角 ⋮，"
                        + "选「允许受限设置」，再回来开启无障碍。");
            } else {
                restrictedDesc.setText(
                        "服务已就绪。点上面的「一键开启」会直接跳到米跳跳的无障碍详情页，"
                        + "打开开关即可。\n\n如果开关是灰色点不动，那就是被「受限设置」拦住了："
                        + "点下面「去允许受限设置」放行后再试。");
            }
        }

        int hits = AdSkipPrefs.hits(this);
        int shake = AdSkipPrefs.shakeStops(this);
        StringBuilder sb = new StringBuilder();
        sb.append("已自动跳过 ").append(hits).append(" 次广告");
        if (shake > 0) sb.append("\n摇一摇拦截 ").append(shake).append(" 次");
        sb.append("\n规则 ").append(RuleStore.enabledCount(this))
                .append(" / ").append(RuleStore.count(this)).append(" 条启用");
        hitInfo.setText(sb.toString());
    }

    /**
     * 判断无障碍服务是否被系统正常列出。
     *
     * 原理：用 AccessibilityManager 拿系统当前认知的无障碍服务列表。
     * 如果我们的服务在 Manifest 里声明了、但**列表里找不到**，
     * 说明它被「受限设置」屏蔽了（或被用户在本应用信息里禁用）。
     *
     * 这是经验判断；取不到列表时返回 true，避免误报吓到用户。
     */
    private boolean isServiceListed() {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            getSystemService(ACCESSIBILITY_SERVICE);
            if (am == null) return true;
            java.util.List<android.accessibilityservice.AccessibilityServiceInfo> list =
                    am.getInstalledAccessibilityServiceList();
            if (list == null || list.isEmpty()) return true;
            for (android.accessibilityservice.AccessibilityServiceInfo info : list) {
                if (info == null || info.getResolveInfo() == null
                        || info.getResolveInfo().serviceInfo == null) {
                    continue;
                }
                android.content.pm.ServiceInfo si = info.getResolveInfo().serviceInfo;
                if (si.packageName != null && si.packageName.equals(getPackageName())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 直达本应用的无障碍详情页（Android 11+），失败回落总列表 */
    private void openAccessibilityDetail() {
        // 用字面量而非 Settings. 常量：该 action 的部分别名在不同 SDK 上暴露不一致，
        // 直接写字符串最稳（系统设置接受这个 action）。
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                i.setData(Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Toast.makeText(this, "打开「米跳跳」的开关即可", Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "在列表里找到「米跳跳」并开启", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            openAppInfo();
        }
    }

    /** 应用信息页：允许受限设置在这里 */
    private void openAppInfo() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "点右上角 ⋮ → 允许受限设置", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "请手动到 设置 → 应用 → 米跳跳 中设置", Toast.LENGTH_LONG).show();
            }
        }
    }
}
