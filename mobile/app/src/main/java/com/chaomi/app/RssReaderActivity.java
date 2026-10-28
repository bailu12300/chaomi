package com.chaomi.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 资讯原文阅读页。
 *
 * 数据来源：**服务端代理**（/api/rss/article?id=xxx）
 *   服务端抓原文 → 去脚本/去广告 → 注入阅读样式 → 返回 HTML
 *   App 只负责显示，**不直连任何第三方站**
 *
 * 三个好处：
 *   1. 不受第三方站墙/慢/弹窗墙影响（走自己服务器）
 *   2. 移动网络下省流量（服务端已裁剪掉无关内容）
 *   3. 原文抓取失败时服务端会返回 RSS 摘要页，不会白屏
 */
public class RssReaderActivity extends AppCompatActivity {

    private WebView web;
    private ProgressBar bar;
    private TextView errText;
    private String pageUrl = "";
    private String originalLink = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rss_reader);
        Ui.fit(this);

        pageUrl = getIntent().getStringExtra("url");
        originalLink = getIntent().getStringExtra("link");
        String title = getIntent().getStringExtra("title");

        web = findViewById(R.id.rssWeb);
        bar = findViewById(R.id.rssBar);
        errText = findViewById(R.id.rssErr);

        TextView titleView = findViewById(R.id.rssTitleBar);
        if (title != null && !title.isEmpty()) titleView.setText(title);

        findViewById(R.id.rssBack).setOnClickListener(v -> finish());
        findViewById(R.id.rssOpenBrowser).setOnClickListener(v -> {
            if (originalLink != null && !originalLink.isEmpty()) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(originalLink)));
                } catch (Exception e) {
                    toast("没有可用浏览器");
                }
            } else {
                toast("没有原文链接");
            }
        });

        WebSettings ws = web.getSettings();
        // 服务端已清洗脚本，但保留 JS 以支持部分排版与图片懒加载
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 固定浅色：服务端页面已是浅色，禁止系统强制反色
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            ws.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        web.setBackgroundColor(0xFFF6F8FA);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                bar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                bar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showError("加载失败：" + (error == null ? "" : error.getDescription()));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 站内页面留在 WebView，站外链接交给系统浏览器
                Uri u = request.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                if (host.endsWith("zy1080.xyz")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        if (pageUrl == null || pageUrl.isEmpty()) {
            showError("缺少文章地址");
            return;
        }
        web.loadUrl(pageUrl);
    }

    private void showError(String msg) {
        bar.setVisibility(View.GONE);
        errText.setVisibility(View.VISIBLE);
        errText.setText(msg + "\n\n可点右上角用浏览器打开原文");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // 必须显式销毁，否则 WebView 泄漏 Activity
        if (web != null) {
            try {
                web.setVisibility(View.GONE);
                web.removeAllViews();
                web.destroy();
            } catch (Exception ignored) {
            }
            web = null;
        }
        super.onDestroy();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
