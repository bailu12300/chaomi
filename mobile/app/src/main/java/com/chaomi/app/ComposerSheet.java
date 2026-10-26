package com.chaomi.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.squareup.picasso.Picasso;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ComposerSheet extends BottomSheetDialog {

    public interface OnPublished { void onPublished(Post p); }

    public static final int REQ_PICK = 7001;

    private final AppCompatActivity activity;
    private final OnPublished cb;
    private String currentType = "text";
    private final List<Api.Part> imageParts = new ArrayList<>();
    private Article parsedArticle;

    private ChipGroup typeGroup;
    private EditText titleEt, bodyEt, linkEt, articleEt, nickEt;
    private View bodyBox, linkBox, articleBox, imageBox;
    private MaterialButton pickBtn, parseBtn, publishBtn;
    private LinearLayout imagesRow, articlePreview;
    private View articlePreviewScroll;
    private TextView articleMeta;

    public ComposerSheet(AppCompatActivity activity, String nick, OnPublished cb) {
        super(activity);
        this.activity = activity;
        this.cb = cb;
        View v = LayoutInflater.from(activity).inflate(R.layout.sheet_composer, null);
        setContentView(v);

        typeGroup = v.findViewById(R.id.typeGroup);
        titleEt = v.findViewById(R.id.titleEt);
        bodyEt = v.findViewById(R.id.bodyEt);
        linkEt = v.findViewById(R.id.linkEt);
        articleEt = v.findViewById(R.id.articleEt);
        nickEt = v.findViewById(R.id.nickEt);
        bodyBox = v.findViewById(R.id.bodyBox);
        linkBox = v.findViewById(R.id.linkBox);
        articleBox = v.findViewById(R.id.articleBox);
        imageBox = v.findViewById(R.id.imageBox);
        pickBtn = v.findViewById(R.id.pickBtn);
        parseBtn = v.findViewById(R.id.parseBtn);
        publishBtn = v.findViewById(R.id.publishBtn);
        imagesRow = v.findViewById(R.id.imagesRow);
        articlePreview = v.findViewById(R.id.articlePreview);
        articlePreviewScroll = v.findViewById(R.id.articlePreviewScroll);
        articleMeta = v.findViewById(R.id.articleMeta);

        nickEt.setText(nick == null ? "匿名" : nick);
        // 已登录时昵称以账号为准，避免冒名
        if (Session.loggedIn(activity)) {
            nickEt.setEnabled(false);
            nickEt.setText(Session.nick(activity));
        }

        typeGroup.setOnCheckedChangeListener((g, checkedId) -> {
            if (checkedId == R.id.typeText) setType("text");
            else if (checkedId == R.id.typeLink) setType("link");
            else if (checkedId == R.id.typeImage) setType("image");
            else if (checkedId == R.id.typeArticle) setType("article");
        });

        pickBtn.setOnClickListener(x -> pickImages());
        parseBtn.setOnClickListener(x -> parseArticle());
        publishBtn.setOnClickListener(x -> publish());

        setType("text");
    }

    private void setType(String t) {
        currentType = t;
        bodyBox.setVisibility("text".equals(t) || "article".equals(t) ? View.VISIBLE : View.GONE);
        linkBox.setVisibility("link".equals(t) ? View.VISIBLE : View.GONE);
        articleBox.setVisibility("article".equals(t) ? View.VISIBLE : View.GONE);
        imageBox.setVisibility("image".equals(t) ? View.VISIBLE : View.GONE);
    }

    /** 从行情详情带入正文（比价帖的默认文案） */
    public void prefillText(String text) {
        if (text == null) return;
        setType("text");
        Chip c = findViewById(R.id.typeText);
        if (c != null) c.setChecked(true);
        bodyEt.setText(text);
    }

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        activity.startActivityForResult(i, REQ_PICK);
    }

    /** 由 Activity.onActivityResult 转发 */
    public void onPickResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        imageParts.clear();
        imagesRow.removeAllViews();
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int i = 0; i < n; i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        int idx = 0;
        for (Uri u : uris) {
            byte[] bytes = readUri(u);
            if (bytes == null) continue;
            imageParts.add(new Api.Part(bytes, "img" + (idx++) + ".jpg"));
            ImageView iv = new ImageView(activity);
            int s = (int) (96 * activity.getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.setMargins(0, 0, 8, 0);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Picasso.get().load(u).into(iv);
            imagesRow.addView(iv);
        }
    }

    private byte[] readUri(Uri u) {
        try {
            InputStream is = activity.getContentResolver().openInputStream(u);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析公众号链接 → 原生块 → 就地预览 */
    private void parseArticle() {
        String url = articleEt.getText().toString().trim();
        if (url.isEmpty()) { toast("请先粘贴公众号文章链接"); return; }
        parseBtn.setEnabled(false);
        parseBtn.setText("解析中…");
        Api.bg(() -> {
            try {
                Article a = Api.parseArticle(url);
                activity.runOnUiThread(() -> {
                    parseBtn.setEnabled(true);
                    parseBtn.setText(activity.getString(R.string.parse));
                    if (a == null) { toast("解析失败"); return; }
                    parsedArticle = a;
                    if (a.title != null && !a.title.isEmpty()
                            && titleEt.getText().toString().trim().isEmpty()) {
                        titleEt.setText(a.title);
                    }
                    int n = a.blocks == null ? 0 : a.blocks.size();
                    articleMeta.setVisibility(View.VISIBLE);
                    articleMeta.setText("已解析 " + n + " 段"
                            + (a.author == null || a.author.isEmpty() ? "" : " · 来源 " + a.author)
                            + " · 发布后为原生文章（不是网页）");
                    articlePreviewScroll.setVisibility(View.VISIBLE);
                    ArticleRenderer.render(activity, articlePreview, a.blocks);
                    toast("解析完成，确认预览后发布");
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    parseBtn.setEnabled(true);
                    parseBtn.setText(activity.getString(R.string.parse));
                    toast("解析失败：" + e.getMessage());
                });
            }
        });
    }

    private void publish() {
        if (!Session.loggedIn(activity)) {
            toast("请先登录再发布");
            return;
        }
        Post p = new Post();
        p.type = currentType;
        p.author = Session.nick(activity);
        p.title = titleEt.getText().toString().trim();

        if ("text".equals(currentType)) {
            p.body = bodyEt.getText().toString().trim();
            if (p.body.isEmpty()) { toast("写点内容吧"); return; }
        } else if ("link".equals(currentType)) {
            p.url = linkEt.getText().toString().trim();
            if (p.url.isEmpty()) { toast("请填写链接"); return; }
            if (p.title.isEmpty()) p.title = p.url;
        } else if ("image".equals(currentType)) {
            if (imageParts.isEmpty()) { toast("请选择至少一张图片"); return; }
            p.body = bodyEt.getText().toString().trim();
        } else if ("article".equals(currentType)) {
            if (parsedArticle == null || parsedArticle.blocks == null
                    || parsedArticle.blocks.isEmpty()) {
                toast("请先解析文章，确认内容后再发布");
                return;
            }
            p.blocks = parsedArticle.blocks;
            p.url = parsedArticle.url;
            p.cover = parsedArticle.cover;
            if (p.title.isEmpty() && parsedArticle.title != null) {
                p.title = parsedArticle.title;
            }
            String extra = bodyEt.getText().toString().trim();
            if (!extra.isEmpty()) p.body = extra;
        }

        publishBtn.setEnabled(false);
        publishBtn.setText("发布中…");
        Api.bg(() -> {
            try {
                if ("image".equals(currentType)) {
                    List<String> urls = Api.upload(imageParts);
                    p.images = urls;
                }
                Post saved = Api.createPost(p);
                activity.runOnUiThread(() -> {
                    publishBtn.setEnabled(true);
                    publishBtn.setText(activity.getString(R.string.publish));
                    if (cb != null) cb.onPublished(saved);
                    dismiss();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    publishBtn.setEnabled(true);
                    publishBtn.setText(activity.getString(R.string.publish));
                    toast("发布失败：" + e.getMessage());
                });
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }
}
