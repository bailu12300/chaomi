package com.chaomi.app;

import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;

/** 型号详情：当前价 + 涨跌幅 + 价格走势图 + 极值统计 */
public class ModelDetailActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_detail);
        Ui.fit(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        String key = getIntent().getStringExtra("key");
        String model = getIntent().getStringExtra("model");
        String brand = getIntent().getStringExtra("brand");
        String spec = getIntent().getStringExtra("spec");
        String cat = getIntent().getStringExtra("cat");
        String platform = getIntent().getStringExtra("platform");
        String shop = getIntent().getStringExtra("shop");
        String title = getIntent().getStringExtra("title");
        String price = getIntent().getStringExtra("price");
        double pct = getIntent().getDoubleExtra("changePct", 0);
        boolean hasChange = getIntent().getBooleanExtra("hasChange", false);

        TextView dCat = findViewById(R.id.dCat);
        TextView dModel = findViewById(R.id.dModel);
        TextView dSpec = findViewById(R.id.dSpec);
        TextView dPrice = findViewById(R.id.dPrice);
        TextView dChange = findViewById(R.id.dChange);
        TextView dShop = findViewById(R.id.dShop);
        TextView dTitle = findViewById(R.id.dTitle);
        TrendChartView chart = findViewById(R.id.chart);
        TextView sMin = findViewById(R.id.sMin);
        TextView sMax = findViewById(R.id.sMax);
        TextView sAvg = findViewById(R.id.sAvg);
        TextView sCount = findViewById(R.id.sCount);
        MaterialButton btnPost = findViewById(R.id.btnPost);

        dCat.setText(cat == null ? "" : cat);
        String name = (brand == null ? "" : brand) + " " + (model == null ? "" : model);
        dModel.setText(name.trim());
        dSpec.setText(spec == null ? "" : spec);
        dPrice.setText(price == null ? "暂无报价" : price);
        dShop.setText((platform == null ? "" : platform) + " · " + (shop == null ? "" : shop));
        dTitle.setText(title == null || title.isEmpty() ? "" : "商品标题：" + title);

        if (hasChange && pct != 0) {
            dChange.setText(String.format(Locale.US, "%+.2f%%", pct));
            dChange.setTextColor(pct > 0 ? 0xFFf85149 : 0xFF3fb950);
        } else {
            dChange.setText("较上一轮持平");
            dChange.setTextColor(0xFF8b949e);
        }

        String finalName = name.trim();
        btnPost.setOnClickListener(v -> {
            ComposerSheet sheet = new ComposerSheet(this, nick(), post -> {
                finish();
            });
            sheet.prefillText("【比价】" + finalName + " 现在 " + (price == null ? "暂无准确报价" : price)
                    + "\n" + (platform == null ? "" : platform) + " " + (shop == null ? "" : shop)
                    + "\n大家觉得这个价能上车吗？");
            sheet.show();
        });

        if (key == null || key.isEmpty()) {
            chart.setPoints(null);
            return;
        }
        Api.bg(() -> {
            try {
                HwHistory h = Api.hwHistory(key);
                List<HwPoint> pts = h.points;
                runOnUiThread(() -> {
                    chart.setPoints(pts);
                    sMin.setText(h.min > 0 ? String.format(Locale.CHINA, "¥%.0f", h.min) : "—");
                    sMax.setText(h.max > 0 ? String.format(Locale.CHINA, "¥%.0f", h.max) : "—");
                    sAvg.setText(h.avg > 0 ? String.format(Locale.CHINA, "¥%.0f", h.avg) : "—");
                    sCount.setText(String.valueOf(h.count));
                });
            } catch (Exception e) {
                runOnUiThread(() -> chart.setPoints(null));
            }
        });
    }

    private String nick() {
        return getSharedPreferences("chaomi", Context.MODE_PRIVATE).getString("nick", "匿名");
    }
}
