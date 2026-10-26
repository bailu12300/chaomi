package com.chaomi.app;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Map;

/**
 * 节点监控：每 3 秒（20 次/分钟）心跳，实时显示 App ↔ 服务器的延迟、
 * 丢包、抖动，以及服务端节点自身状态。
 */
public class NodeActivity extends AppCompatActivity implements NodeMonitor.Listener {

    private final NodeMonitor monitor = new NodeMonitor();

    private TextView nodeName, rtt, quality, hbInfo;
    private TextView sAvg, sMinMax, sJitter, sLoss, nodeDetail;
    private View dot;
    private SwitchMaterial swMonitor;
    private LatencyChartView chart;

    private int tickCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_node);
        Ui.fit(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) tb.setNavigationOnClickListener(v -> finish());

        nodeName = findViewById(R.id.nodeName);
        rtt = findViewById(R.id.rtt);
        quality = findViewById(R.id.quality);
        hbInfo = findViewById(R.id.hbInfo);
        sAvg = findViewById(R.id.sAvg);
        sMinMax = findViewById(R.id.sMinMax);
        sJitter = findViewById(R.id.sJitter);
        sLoss = findViewById(R.id.sLoss);
        nodeDetail = findViewById(R.id.nodeDetail);
        dot = findViewById(R.id.dot);
        swMonitor = findViewById(R.id.swMonitor);
        chart = findViewById(R.id.chart);

        swMonitor.setChecked(true);
        swMonitor.setOnCheckedChangeListener((b, on) -> {
            if (on) {
                monitor.start(this);
            } else {
                monitor.stop();
                nodeName.setText("已暂停探测");
                setDot(0xFF8b949e);
            }
        });

        loadNodeInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (swMonitor.isChecked()) monitor.start(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        monitor.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        monitor.stop();
    }

    // ---- NodeMonitor.Listener ----

    @Override
    public void onTarget(String target) {
        nodeName.setText("目标 " + target + " · 每 3 秒心跳");
    }

    @Override
    public void onSample(int ms, boolean ok) {
        tickCount++;
        if (ok) {
            rtt.setText(String.valueOf(ms));
            rtt.setTextColor(LatencyChartView.qualityColor(ms));
            quality.setText(grade(ms));
            quality.setTextColor(LatencyChartView.qualityColor(ms));
            setDot(LatencyChartView.qualityColor(ms));
        } else {
            rtt.setText("超时");
            rtt.setTextColor(0xFFf85149);
            quality.setText("无响应");
            quality.setTextColor(0xFFf85149);
            setDot(0xFFf85149);
        }

        int sent = monitor.sent();
        hbInfo.setText(String.format(java.util.Locale.CHINA,
                "心跳 %d 次已发 · 每 3 秒 1 次（20 次/分钟）· 失败 %d 次",
                sent, monitor.lost()));

        chart.setData(monitor.series());
        sAvg.setText(monitor.avg() > 0 ? monitor.avg() + " ms" : "--");
        sMinMax.setText(monitor.min() > 0
                ? monitor.min() + "/" + monitor.max() : "--");
        sJitter.setText(monitor.jitter() > 0 ? monitor.jitter() + " ms" : "--");
        double loss = monitor.lossRate();
        sLoss.setText(String.format(java.util.Locale.CHINA, "%.1f%%", loss));
        sLoss.setTextColor(loss > 5 ? 0xFFf85149 : 0xFFe6edf3);

        // 每 20 次刷新一次节点信息
        if (tickCount % 20 == 0) loadNodeInfo();
    }

    private void setDot(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        dot.setBackground(g);
    }

    private String grade(int ms) {
        if (ms < 60) return "极佳";
        if (ms < 150) return "良好";
        if (ms < 300) return "一般";
        return "较差";
    }

    private void loadNodeInfo() {
        Api.bg(() -> {
            try {
                Map<String, Object> d = Api.nodeInfo();
                runOnUiThread(() -> bindNode(d));
            } catch (Exception e) {
                runOnUiThread(() -> nodeDetail.setText("节点信息获取失败：" + e.getMessage()));
            }
        });
    }

    private void bindNode(Map<String, Object> d) {
        if (d == null || d.isEmpty()) return;
        String name = str(d.get("name"));
        String region = str(d.get("region"));
        long up = lng(d.get("uptimeSec"));
        long db = lng(d.get("dbPingMs"));
        long hw = lng(d.get("hwCount"));
        long posts = lng(d.get("postCount"));
        long alloc = lng(d.get("memAllocMb"));
        long goro = lng(d.get("goroutines"));

        StringBuilder sb = new StringBuilder();
        sb.append("节点：").append(name).append("（").append(region).append("）\n");
        sb.append("在线时长：").append(upText(up)).append("\n");
        sb.append("数据库往返：").append(db >= 0 ? db + " ms" : "不可用").append("\n");
        sb.append("在售型号：").append(hw).append(" 个 · 社区帖子：").append(posts).append(" 条\n");
        sb.append("内存占用：").append(alloc).append(" MB · 协程：").append(goro).append("\n");

        Object hbObj = d.get("heartbeat");
        if (hbObj instanceof Map) {
            Map<String, Object> hb = (Map<String, Object>) hbObj;
            sb.append("心跳累计：").append(lng(hb.get("total"))).append(" 次")
                    .append(" · 本分钟 ").append(lng(hb.get("perMinute")))
                    .append("/").append(lng(hb.get("expectPerMin")));
            long la = lng(hb.get("latAvgMs"));
            if (la > 0) sb.append("\n服务端统计延迟：均 ").append(la).append(" ms")
                    .append("（").append(lng(hb.get("latMinMs"))).append("-")
                    .append(lng(hb.get("latMaxMs"))).append("）");
        }
        nodeDetail.setText(sb.toString());
    }

    private String upText(long sec) {
        if (sec <= 0) return "刚刚启动";
        long d = sec / 86400, h = (sec % 86400) / 3600, m = (sec % 3600) / 60;
        if (d > 0) return d + " 天 " + h + " 小时";
        if (h > 0) return h + " 小时 " + m + " 分";
        return m + " 分钟";
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private long lng(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }
}
