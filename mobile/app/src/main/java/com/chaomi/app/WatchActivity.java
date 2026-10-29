package com.chaomi.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务监控看板：展示第三方服务（DeepSeek / GPT / Cloudflare 等）的在线状态。
 *
 * 数据源：/api/watch/status（服务端每 15 秒探测一次，这里 3 秒拉一次展示）。
 * 采集频率不变，只有展示实时刷新 —— 与服务端解耦，不增加探测压力。
 */
public class WatchActivity extends AppCompatActivity {

    private final List<WatchResult> items = new ArrayList<>();
    private WatchAdapter adapter;

    private TextView totalTv, upTv, downTv, avgTv, badgeTv;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable poller;
    private boolean polling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch);
        Ui.fit(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) tb.setNavigationOnClickListener(v -> finish());

        totalTv = findViewById(R.id.wTotal);
        upTv = findViewById(R.id.wUp);
        downTv = findViewById(R.id.wDown);
        avgTv = findViewById(R.id.wAvg);
        badgeTv = findViewById(R.id.wBadge);

        RecyclerView rv = findViewById(R.id.wList);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new WatchAdapter(items);
        rv.setAdapter(adapter);

        SwipeRefreshLayout swipe = findViewById(R.id.swipe);
        swipe.setOnRefreshListener(() -> {
            load();
            swipe.setRefreshing(false);
        });

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        poller = new Runnable() {
            @Override
            public void run() {
                load();
                handler.postDelayed(this, 3000); // 3 秒刷新展示
            }
        };
        handler.postDelayed(poller, 3000);
    }

    private void stopPolling() {
        polling = false;
        if (poller != null) handler.removeCallbacks(poller);
    }

    private void load() {
        Api.bg(() -> {
            try {
                WatchStatus s = Api.watchStatus();
                runOnUiThread(() -> bind(s));
            } catch (Exception e) {
                runOnUiThread(() -> badgeTv.setText("获取失败"));
            }
        });
    }

    private void bind(WatchStatus s) {
        int total = s.list.size();
        int up = s.up, down = s.down;
        long sum = 0, cnt = 0;
        for (WatchResult w : s.list) {
            if (w.ok) { sum += w.latencyMs; cnt++; }
        }
        long avg = cnt > 0 ? sum / cnt : 0;

        totalTv.setText(String.valueOf(total));
        upTv.setText(String.valueOf(up));
        downTv.setText(String.valueOf(down));
        avgTv.setText(avg + " ms");

        boolean allOk = down == 0 && total > 0;
        badgeTv.setText(allOk ? "全部正常" : (down + " 项异常"));
        badgeTv.setTextColor(ContextCompat.getColor(this,
                allOk ? R.color.chaomi_down : R.color.chaomi_up));

        items.clear();
        items.addAll(s.list);
        adapter.notifyDataSetChanged();
    }
}
