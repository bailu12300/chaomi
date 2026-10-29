package com.chaomi.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

/**
 * 短视频去水印。
 *
 * 与网页端完全同一套后端接口（/api/watermark + /api/watermark/dl），
 * 所以能力边界完全一致：
 *   · 哔哩哔哩：解析出无水印直链，可一键下载
 *   · 抖音 / TikTok：服务端走 DTK v5（CloakBrowser 自动铸造游客身份），
 *     无需任何 cookie，短链/分享口令都能直接出无水印直链（含图集）
 *   · 快手 / 小红书：接口有签名校验，仅展开原链接
 *
 * 下载走服务端代理：因为视频直链有 Referer 防盗链，
 * 手机直连会 403，必须由服务器带正确 Referer 转发。
 */
public class WatermarkActivity extends AppCompatActivity {

    private EditText input;
    private MaterialButton btnParse, btnPaste, btnClear;
    private LinearLayout resultBox;
    private TextView stateBadge;

    // 结果区控件
    private TextView rPlatform, rState, rTitle, rSub, rNote;
    private ImageView rCover;
    private MaterialButton rDownload, rOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watermark);
        Ui.fit(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) tb.setNavigationOnClickListener(v -> finish());

        input = findViewById(R.id.wmInput);
        btnParse = findViewById(R.id.wmParse);
        btnPaste = findViewById(R.id.wmPaste);
        btnClear = findViewById(R.id.wmClear);
        resultBox = findViewById(R.id.wmResult);
        stateBadge = findViewById(R.id.wmBadge);

        rPlatform = findViewById(R.id.rPlatform);
        rState = findViewById(R.id.rState);
        rTitle = findViewById(R.id.rTitle);
        rSub = findViewById(R.id.rSub);
        rNote = findViewById(R.id.rNote);
        rCover = findViewById(R.id.rCover);
        rDownload = findViewById(R.id.rDownload);
        rOpen = findViewById(R.id.rOpen);

        btnPaste.setOnClickListener(v -> pasteFromClipboard());
        btnClear.setOnClickListener(v -> {
            input.setText("");
            resultBox.setVisibility(View.GONE);
            setBadge("就绪", false);
        });
        btnParse.setOnClickListener(v -> doParse());

        // 进页面自动尝试读剪贴板（很多人是复制完才进来的）
        autoFillFromClipboard();
    }

    /** 自动填：剪贴板里像视频链接就直接填上，省一次点按 */
    private void autoFillFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return;
            CharSequence t = cd.getItemAt(0).coerceToText(this);
            if (t == null) return;
            String s = t.toString();
            if (s.contains("http") && input.getText().toString().trim().isEmpty()) {
                input.setText(s);
                Toast.makeText(this, "已自动填入剪贴板内容", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {
        }
    }

    private void pasteFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                Toast.makeText(this, "剪贴板是空的", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return;
            CharSequence t = cd.getItemAt(0).coerceToText(this);
            if (t != null && t.length() > 0) {
                input.setText(t.toString());
                Toast.makeText(this, "已粘贴", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "读取剪贴板失败，请长按输入框粘贴", Toast.LENGTH_SHORT).show();
        }
    }

    private void setBadge(String text, boolean bad) {
        stateBadge.setText(text);
        stateBadge.setTextColor(ContextCompat.getColor(this,
                bad ? R.color.chaomi_up : R.color.chaomi_down));
    }

    private void doParse() {
        final String raw = input.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, "请先粘贴分享内容", Toast.LENGTH_SHORT).show();
            return;
        }
        btnParse.setEnabled(false);
        btnParse.setText("解析中…");
        setBadge("解析中", false);
        resultBox.setVisibility(View.GONE);

        Api.bg(() -> {
            try {
                WmResult r = Api.watermark(raw);
                runOnUiThread(() -> render(r));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBadge("解析失败", true);
                    showPlain("解析失败：" + (e.getMessage() == null ? "网络异常" : e.getMessage()));
                    btnParse.setEnabled(true);
                    btnParse.setText("开始解析");
                });
            }
        });
    }

    private void render(WmResult r) {
        btnParse.setEnabled(true);
        btnParse.setText("开始解析");
        if (r == null) {
            setBadge("解析失败", true);
            showPlain("没有拿到解析结果");
            return;
        }

        setBadge(r.ok ? "解析成功" : "部分支持", !r.ok);
        rPlatform.setText(r.platformName == null ? "未知" : r.platformName);
        rState.setText(r.ok ? "解析成功" : "仅展开链接");
        rState.setTextColor(ContextCompat.getColor(this,
                r.ok ? R.color.chaomi_down : R.color.chaomi_gold));

        if (r.title != null && !r.title.isEmpty()) {
            rTitle.setText(r.title);
            rTitle.setVisibility(View.VISIBLE);
        } else {
            rTitle.setVisibility(View.GONE);
        }

        StringBuilder sb = new StringBuilder();
        if (r.author != null && !r.author.isEmpty()) sb.append("作者：").append(r.author);
        if (r.duration > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.CHINA, "时长：%d:%02d",
                    r.duration / 60, r.duration % 60));
        }
        rSub.setText(sb.toString());
        rSub.setVisibility(sb.length() > 0 ? View.VISIBLE : View.GONE);

        rNote.setText(r.note == null ? "" : r.note);
        rNote.setVisibility(r.note == null || r.note.isEmpty() ? View.GONE : View.VISIBLE);

        // 封面
        if (r.cover != null && !r.cover.isEmpty()) {
            rCover.setVisibility(View.VISIBLE);
            try {
                // 与 FeedAdapter 一致：用 Picasso
                com.squareup.picasso.Picasso.get()
                        .load(Utils.fullUrl(r.cover))
                        .into(rCover);
            } catch (Throwable t) {
                rCover.setVisibility(View.GONE);
            }
        } else {
            rCover.setVisibility(View.GONE);
        }

        // 下载 / 打开原链接
        if (r.ok && r.videoUrl != null && !r.videoUrl.isEmpty()) {
            final String name = buildFileName(r.title);
            rDownload.setVisibility(View.VISIBLE);
            rDownload.setOnClickListener(v -> downloadViaProxy(r.videoUrl, r.originalUrl, name));
        } else {
            rDownload.setVisibility(View.GONE);
        }

        if (r.originalUrl != null && !r.originalUrl.isEmpty()) {
            final String link = r.originalUrl;
            rOpen.setVisibility(View.VISIBLE);
            rOpen.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                } catch (Exception e) {
                    Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            rOpen.setVisibility(View.GONE);
        }

        resultBox.setVisibility(View.VISIBLE);
    }

    private String buildFileName(String title) {
        String t = (title == null || title.trim().isEmpty()) ? "video" : title.trim();
        t = t.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (t.length() > 40) t = t.substring(0, 40);
        return t + ".mp4";
    }

    /** 走服务端代理下载（绕过 Referer 防盗链），交给系统下载器 */
    private void downloadViaProxy(String videoUrl, String referer, String fileName) {
        String url = Api.watermarkDownloadUrl(videoUrl, referer, fileName);
        try {
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return;
            }
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(Uri.parse(url));
            req.setTitle(fileName);
            req.setDescription("超米 · 无水印视频");
            req.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, fileName);
            req.addRequestHeader("User-Agent", "chaomi/1.0");
            dm.enqueue(req);
            Toast.makeText(this, "已开始下载，完成后在「下载」里查看", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            // 没有存储权限 / DownloadManager 不可用 → 退回复制链接
            copyLink(url);
        }
    }

    private void copyLink(String url) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("下载链接", url));
                Toast.makeText(this, "下载链接已复制，可粘贴到浏览器", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "下载失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void showPlain(String msg) {
        rPlatform.setText("—");
        rState.setText("失败");
        rState.setTextColor(ContextCompat.getColor(this, R.color.chaomi_up));
        rTitle.setVisibility(View.GONE);
        rSub.setVisibility(View.GONE);
        rCover.setVisibility(View.GONE);
        rDownload.setVisibility(View.GONE);
        rOpen.setVisibility(View.GONE);
        rNote.setText(msg);
        rNote.setVisibility(View.VISIBLE);
        resultBox.setVisibility(View.VISIBLE);
    }
}
