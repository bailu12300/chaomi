package com.chaomi.app;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

/**
 * 沉浸式阅读：用 WebView 直接渲染原文。
 *
 * 为什么这么做：
 *   公众号排版极度依赖内联样式、自定义组件、卡片、视频号，原生解析永远追不上。
 *   只解析标题用于列表展示，正文交给浏览器渲染，排版就是作者原本的样子。
 *
 * 沉浸式要点：
 *   1) WebView 铺满，去掉系统状态栏留白之外的任何装饰
 *   2) 顶部工具栏悬浮，向下滚动自动隐藏，向上滚动或到顶时出现
 *   3) 底部细进度条显示阅读进度
 *   4) 返回键优先回退 WebView 历史
 */
public class ReaderActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private WebView web;
    private View topBar;
    private ProgressBar progress;
    private TextView loadHint;

    private boolean barHidden = false;
    private int lastScroll = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);
        Ui.fit(this);

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "缺少链接", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        web = findViewById(R.id.web);
        topBar = findViewById(R.id.topBar);
        progress = findViewById(R.id.progress);
        loadHint = findViewById(R.id.loadHint);

        TextView titleView = findViewById(R.id.readerTitle);
        if (titleView != null) {
            titleView.setText(title == null || title.isEmpty() ? "阅读" : title);
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack();
            else finish();
        });

        FloatingActionButton openExternal = findViewById(R.id.btnExternal);
        final String finalUrl = url;
        openExternal.setOnClickListener(v -> {
            try {
                android.content.Intent i = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(finalUrl));
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "没有可用浏览器", Toast.LENGTH_SHORT).show();
            }
        });

        setupWeb();
        web.loadUrl(url);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings s = web.getSettings();
        // 公众号文章需要 JS 才能正常展示图片与视频
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 图文混排常有 http 图片，混合内容放开
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        // 移动端 UA 才能拿到公众号的移动版排版
        s.setUserAgentString(s.getUserAgentString()
                .replace("Android", "Android").trim());

        web.setBackgroundColor(ContextCompat_getBg());

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // 站内继续用 WebView，外部链接交给系统浏览器
                String u = req.getUrl() == null ? "" : req.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    String host = req.getUrl().getHost();
                    if (host != null && (host.contains("weixin.qq.com") || host.contains("qq.com"))) {
                        return false;
                    }
                    try {
                        startActivity(new android.content.Intent(
                                android.content.Intent.ACTION_VIEW, req.getUrl()));
                        return true;
                    } catch (Exception ignored) {
                    }
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
                loadHint.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                // 注入阅读优化：去掉公众号顶部的"关注"条、底部推荐，让正文更沉浸
                view.evaluateJavascript(JS_CLEAN, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        // 滚动隐藏/显示顶部工具栏
        if (Build.VERSION.SDK_INT >= 23) {
            web.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
                int dy = y - lastScroll;
                lastScroll = y;
                if (dy > 8 && y > 200 && !barHidden) {
                    hideBar();
                } else if ((dy < -8 || y <= 4) && barHidden) {
                    showBar();
                }
            });
        }
    }

    private int ContextCompat_getBg() {
        return androidx.core.content.ContextCompat.getColor(this, R.color.chaomi_bg);
    }

    private void hideBar() {
        barHidden = true;
        topBar.animate().translationY(-topBar.getHeight()).alpha(0f).setDuration(180).start();
    }

    private void showBar() {
        barHidden = false;
        topBar.animate().translationY(0f).alpha(1f).setDuration(180).start();
    }

    /** 注入的清理脚本：去掉干扰阅读的浮层，不改正文排版 */
    private static final String JS_CLEAN =
            "(function(){"
                    + "try{"
                    + "var kill=['#js_pc_qr_code','.qr_code_pc','#js_profile_qrcode',"
                    + "'.rich_media_area_extra','.rich_media_tool','#js_article_comment',"
                    + "'.js_ad_link','.reward_area','.rich_media_area_primary_inner>iframe'];"
                    + "kill.forEach(function(s){document.querySelectorAll(s).forEach(function(e){e.style.display='none';});});"
                    + "document.querySelectorAll('img').forEach(function(im){"
                    + "  im.style.maxWidth='100%'; im.style.height='auto';"
                    + "  try{ if(!im.src && im.dataset && im.dataset.src) im.src=im.dataset.src; }catch(e){}"
                    + "});"
                    + "var b=document.body; if(b){ b.style.padding='0 0 24px 0'; }"
                    + "}catch(e){}"
                    + "})();";

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
        if (web != null) {
            // 释放 WebView，避免内存泄漏
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
