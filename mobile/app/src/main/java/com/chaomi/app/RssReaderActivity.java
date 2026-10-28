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
 * **直连原文**（请求不经过服务器），阅读体验由客户端自己优化：
 *   WebView 加载真实 URL → 页面加载完成后注入阅读样式（CSS）
 *   → 隐藏导航/侧栏/广告浮层，正文宽度自适应手机屏
 *
 * 为什么在客户端做而不是服务端代理：
 *   · 不消耗服务器带宽（原文可能几 MB，代理等于流量翻倍）
 *   · 不把用户 IP 暴露给自己的服务器中转
 *   · 服务端少一次外部抓取，故障面更小
 *
 * 代价：拿不到服务端的 HTML 清洗，靠 CSS 压制干扰元素；
 *       个别重脚本站点（如需要 JS 渲染的）可能显示不完整。
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
                injectReaderStyle(view);
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

    /**
     * 注入阅读优化样式。
     *
     * 只加 CSS，不删 DOM、不执行站点自己的脚本——避免破坏页面逻辑。
     * 用 !important 压制原站样式的干扰（导航、侧栏、分享条、浮层）。
     */
    private void injectReaderStyle(WebView view) {
        String css = String.join(" ",
            "html{-webkit-text-size-adjust:100%!important}",
            "body{margin:0!important;padding:14px 16px 48px!important;",
            "background:#f6f8fa!important;color:#1f2328!important;",
            "font:16px/1.75 -apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',sans-serif!important;",
            "max-width:100%!important;overflow-x:hidden!important}",
            "img,video{max-width:100%!important;height:auto!important}",
            "table{max-width:100%!important;display:block!important;overflow-x:auto!important}",
            "pre{overflow-x:auto!important;background:#161b22!important;color:#e6edf3!important;",
            "padding:12px!important;border-radius:8px!important;font-size:13px!important}",
            // 压制常见干扰元素
            "nav,header,footer,aside,",
            "[class*='nav'],[class*='Nav'],[class*='header'],[class*='Header'],",
            "[class*='footer'],[class*='Footer'],[class*='sidebar'],[class*='Sidebar'],",
            "[class*='share'],[class*='Share'],[class*='comment'],[class*='Comment'],",
            "[class*='related'],[class*='Related'],[class*='recommend'],[class*='Recommend'],",
            "[class*='advert'],[class*='Advert'],[class*='banner'],[class*='Banner'],",
            "[class*='popup'],[class*='Popup'],[class*='modal'],[class*='Modal'],",
            "[class*='qrcode'],[class*='Qrcode'],[class*='app-download'],",
            "[id*='nav'],[id*='header'],[id*='footer'],[id*='sidebar'],",
            "[id*='share'],[id*='comment'],[id*='related'],[id*='banner']",
            "{display:none!important}",
            // 固定浮层（返回顶部、客服、下载 App）会遮正文
            "[style*='position:fixed'],[style*='position: fixed']{display:none!important}",
            "a{color:#0969da!important;text-decoration:none!important}",
            "h1,h2,h3,h4{line-height:1.4!important;margin:20px 0 10px!important;color:#111!important}",
            "p{margin:0 0 14px!important}",
            "blockquote{margin:14px 0!important;padding:10px 14px!important;",
            "background:#eef1f5!important;border-left:3px solid #0969da!important;",
            "border-radius:0 8px 8px 0!important}"
        );
        String js = "(function(){try{"
            + "var s=document.getElementById('chaomi-reader');"
            + "if(s)return;"
            + "s=document.createElement('style');s.id='chaomi-reader';"
            + "s.textContent=" + jsString(css) + ";"
            + "(document.head||document.documentElement).appendChild(s);"
            + "}catch(e){}})();";
        try {
            view.evaluateJavascript(js, null);
        } catch (Exception ignored) {
        }
    }

    /** 把字符串转成安全的 JS 字面量（避免引号/换行破坏脚本） */
    private String jsString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
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
