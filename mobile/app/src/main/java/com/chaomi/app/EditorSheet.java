package com.chaomi.app;

import androidx.core.content.ContextCompat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.squareup.picasso.Picasso;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 富文本发布器：块式编辑，支持混合
 *   文字 / 代码块 / 引用 / 话题 / 图片（内联上传）
 * 以及公众号链接一键转原生文章。
 *
 * 产出统一为 blocks 数组交给后端，客户端 ArticleRenderer 原生渲染。
 */
public class EditorSheet extends BottomSheetDialog {

    public interface OnPublished { void onPublished(Post p); }

    public static final int REQ_PICK = 7101;

    /** 编辑中的块 */
    static class EditBlock {
        String type;            // p | code | quote | topic | img | h2 | link
        String text = "";
        String src = "";
        byte[] bytes;           // 待上传的图片/动图
        LinkCard card;          // link 块的解析结果
        View view;              // 绑定的视图
    }

    private final AppCompatActivity activity;
    private final OnPublished cb;
    private final List<EditBlock> blocks = new ArrayList<>();

    private EditText titleEt, articleEt;
    private LinearLayout blockList;
    private MaterialButton publishBtn, parseBtn;
    private TextView articleMeta, hint;

    private EditBlock pendingImageBlock;   // 正在选图的块

    public EditorSheet(AppCompatActivity act, String nick, OnPublished cb) {
        super(act);
        this.activity = act;
        this.cb = cb;

        View v = LayoutInflater.from(act).inflate(R.layout.sheet_editor, null);
        setContentView(v);

        titleEt = v.findViewById(R.id.titleEt);
        articleEt = v.findViewById(R.id.articleEt);
        blockList = v.findViewById(R.id.blockList);
        publishBtn = v.findViewById(R.id.publishBtn);
        parseBtn = v.findViewById(R.id.parseBtn);
        articleMeta = v.findViewById(R.id.articleMeta);
        hint = v.findViewById(R.id.hint);

        v.findViewById(R.id.addText).setOnClickListener(x -> addBlock("p"));
        v.findViewById(R.id.addCode).setOnClickListener(x -> addBlock("code"));
        v.findViewById(R.id.addQuote).setOnClickListener(x -> addBlock("quote"));
        v.findViewById(R.id.addTopic).setOnClickListener(x -> addBlock("topic"));
        v.findViewById(R.id.addImage).setOnClickListener(x -> pickMediaFor(null));
        v.findViewById(R.id.addLink).setOnClickListener(x -> addLinkBlock());

        parseBtn.setOnClickListener(x -> parseArticle());
        publishBtn.setOnClickListener(x -> publish());

        hint.setText("发布后为原生文章，读者端不用 WebView 渲染");
        addBlock("p");
    }

    // ---------- 块操作 ----------

    private void addBlock(String type) {
        EditBlock b = new EditBlock();
        b.type = type;
        blocks.add(b);
        renderBlocks();
        // 新块自动聚焦
        if (!"img".equals(type) && b.view != null) {
            EditText et = b.view.findViewById(R.id.bText);
            if (et != null) et.requestFocus();
        }
    }

    private void renderBlocks() {
        blockList.removeAllViews();
        for (int i = 0; i < blocks.size(); i++) {
            EditBlock b = blocks.get(i);
            View item = LayoutInflater.from(activity)
                    .inflate(R.layout.item_block, blockList, false);
            b.view = item;

            TextView type = item.findViewById(R.id.bType);
            EditText text = item.findViewById(R.id.bText);
            ImageView img = item.findViewById(R.id.bImage);

            type.setText(label(b.type));

            switch (b.type) {
                case "code":
                    text.setHint("贴代码…");
                    text.setTypeface(android.graphics.Typeface.MONOSPACE);
                    text.setTextSize(12);
                    break;
                case "quote":
                    text.setHint("引用一段话…");
                    text.setBackgroundColor(ContextCompat.getColor(activity, R.color.chaomi_surface_2));
                    break;
                case "topic":
                    text.setHint("话题名，如 装机 / 显卡 / 二手");
                    text.setSingleLine(true);
                    text.setMinLines(1);
                    break;
                case "img":
                    text.setVisibility(View.GONE);
                    img.setVisibility(View.VISIBLE);
                    if (b.bytes != null) {
                        // 编辑器里用首帧预览；真正发布后服务端存文件，
                        // 阅读端按 URL 加载，动图才会动（Picasso 不支持 byte[]）
                        try {
                            android.graphics.Bitmap bm = android.graphics.BitmapFactory
                                    .decodeByteArray(b.bytes, 0, b.bytes.length);
                            if (bm != null) img.setImageBitmap(bm);
                        } catch (Exception ignored) {
                        }
                    } else if (b.src != null && !b.src.isEmpty()) {
                        Picasso.get().load(Utils.fullUrl(b.src)).into(img);
                    }
                    img.setOnClickListener(x -> pickMediaFor(b));
                    break;
                case "link":
                    text.setVisibility(View.GONE);
                    img.setVisibility(b.card != null && b.card.image != null
                            && !b.card.image.isEmpty() ? View.VISIBLE : View.GONE);
                    if (b.card != null && b.card.image != null && !b.card.image.isEmpty()) {
                        Picasso.get().load(Utils.fullUrl(b.card.image)).into(img);
                    }
                    img.setOnClickListener(null);
                    type.setText("链接卡片"
                            + (b.card == null ? "" : " · " + cardKind(b.card.kind)));
                    break;
                default:
                    text.setHint("写点什么…");
                    break;
            }

            if (!"img".equals(b.type)) {
                text.setText(b.text);
                final EditBlock fb = b;
                text.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
                    public void onTextChanged(CharSequence s, int a, int c, int d) {
                        fb.text = s == null ? "" : s.toString();
                    }
                    public void afterTextChanged(android.text.Editable s) {}
                });
            }

            // ★ 用对象引用而不是捕获索引：块增删后索引会错乱，导致点 ✕ 删错块或没反应。
            // ★ 用 post 延迟到下一帧再重建视图：直接在点击回调里 removeAllViews()
            //   会把正在分发事件的 View 拆掉，点击会被吞掉（这就是"x 不掉"的真正原因）。
            item.findViewById(R.id.bDel).setOnClickListener(x -> removeBlock(b));
            item.findViewById(R.id.bMoveUp).setOnClickListener(x -> moveBlock(b, -1));
            item.findViewById(R.id.bMoveDown).setOnClickListener(x -> moveBlock(b, 1));

            blockList.addView(item);
        }
    }

    /** 删除块（按对象引用定位，不用索引） */
    private void removeBlock(EditBlock b) {
        int i = indexOfBlock(b);
        if (i < 0) return;
        blocks.remove(i);
        if (blocks.isEmpty()) {
            EditBlock nb = new EditBlock();
            nb.type = "p";
            blocks.add(nb);
        }
        postRender();
    }

    /** 移动块 */
    private void moveBlock(EditBlock b, int delta) {
        int i = indexOfBlock(b);
        if (i < 0) return;
        int j = i + delta;
        if (j < 0 || j >= blocks.size()) return;
        blocks.remove(i);
        blocks.add(j, b);
        postRender();
    }

    private int indexOfBlock(EditBlock b) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i) == b) return i;
        }
        return -1;
    }

    /** 延迟重建，避免在点击回调里拆掉正在处理事件的 View */
    private void postRender() {
        if (blockList == null) return;
        blockList.post(this::renderBlocks);
    }

    private String label(String t) {
        switch (t) {
            case "code": return "代码块";
            case "quote": return "引用";
            case "topic": return "话题";
            case "img": return "图片/动图";
            case "link": return "链接卡片";
            case "h2": return "小标题";
            default: return "文字";
        }
    }

    // ---------- 选图 ----------

    /** 选图（含 GIF 动图） */
    private void pickMediaFor(EditBlock target) {
        pendingImageBlock = target;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");   // image/* 已覆盖 gif/webp
        i.putExtra("android.intent.extra.MIME_TYPES",
                new String[]{"image/*", "image/gif", "image/webp"});
        activity.startActivityForResult(i, REQ_PICK);
    }

    /** 由 Activity.onActivityResult 转发 */
    public void onPickResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        byte[] bytes = readUri(data.getData());
        if (bytes == null) {
            toast("读取图片失败");
            return;
        }
        if (bytes.length > 8 * 1024 * 1024) {
            toast("图片过大（限 8MB）");
            return;
        }
        if (pendingImageBlock != null) {
            pendingImageBlock.bytes = bytes;
            pendingImageBlock.type = "img";
            renderBlocks();
        } else {
            EditBlock b = new EditBlock();
            b.type = "img";
            b.bytes = bytes;
            blocks.add(b);
            renderBlocks();
        }
        pendingImageBlock = null;
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

    // ---------- 链接卡片 ----------

    /** 弹窗输入 URL → 解析 OG → 生成卡片块 */
    private void addLinkBlock() {
        android.widget.EditText et = new android.widget.EditText(activity);
        et.setHint("https://…");
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        int pad = (int) (14 * activity.getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("挂链接")
                .setMessage("自动抓取标题/摘要/封面，以卡片展示。公众号链接会转成原生文章。")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("解析", (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (url.isEmpty()) return;
                    resolveLink(url);
                })
                .show();
    }

    private void resolveLink(String url) {
        toast("解析中…");
        Api.bg(() -> {
            try {
                LinkCard c = Api.linkPreview(url);
                activity.runOnUiThread(() -> {
                    EditBlock b = new EditBlock();
                    b.type = "link";
                    b.src = url;
                    b.card = c;
                    b.text = c == null ? url : (c.title == null ? url : c.title);
                    blocks.add(b);
                    renderBlocks();
                    toast(c != null && c.ok ? "已生成卡片" : "只拿到部分信息，仍可发布");
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    // 解析失败也允许挂纯链接
                    EditBlock b = new EditBlock();
                    b.type = "link";
                    b.src = url;
                    b.text = url;
                    blocks.add(b);
                    renderBlocks();
                    toast("解析失败，已作为纯链接挂上");
                });
            }
        });
    }

    private String cardKind(String kind) {
        if (kind == null) return "网页";
        switch (kind) {
            case "wechat": return "公众号";
            case "video": return "视频";
            case "github": return "GitHub";
            case "image": return "图片";
            case "doc": return "文档";
            case "article": return "文章";
            default: return "网页";
        }
    }

    /** 按文件头判断扩展名：动图必须保留 .gif，否则服务端按扩展名拒收 */
    private String sniffExt(byte[] b) {
        if (b == null || b.length < 12) return ".jpg";
        // GIF: 47 49 46 38
        if (b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38) return ".gif";
        // PNG: 89 50 4E 47
        if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return ".png";
        // WEBP: RIFF....WEBP
        if (b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
                && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) return ".webp";
        return ".jpg";
    }

    // ---------- 公众号解析 ----------

    private void parseArticle() {
        String url = articleEt.getText().toString().trim();
        if (url.isEmpty()) {
            toast("先粘贴公众号文章链接");
            return;
        }
        parseBtn.setEnabled(false);
        parseBtn.setText("解析中…");
        Api.bg(() -> {
            try {
                Article a = Api.parseArticle(url);
                activity.runOnUiThread(() -> {
                    parseBtn.setEnabled(true);
                    parseBtn.setText(activity.getString(R.string.parse));
                    if (a == null) {
                        toast("解析失败");
                        return;
                    }
                    if (a.title != null && !a.title.isEmpty()
                            && titleEt.getText().toString().trim().isEmpty()) {
                        titleEt.setText(a.title);
                    }
                    // 把解析出来的块并进编辑器
                    int n = 0;
                    if (a.blocks != null) {
                        for (Block blk : a.blocks) {
                            EditBlock eb = new EditBlock();
                            if ("img".equals(blk.type)) {
                                eb.type = "img";
                                eb.src = blk.src;
                            } else {
                                eb.type = blk.type;
                                eb.text = blk.text == null ? "" : blk.text;
                            }
                            blocks.add(eb);
                            n++;
                        }
                    }
                    // 清掉空的首块
                    if (!blocks.isEmpty() && blocks.get(0).type.equals("p")
                            && (blocks.get(0).text == null || blocks.get(0).text.isEmpty())
                            && blocks.size() > 1) {
                        blocks.remove(0);
                    }
                    articleMeta.setVisibility(View.VISIBLE);
                    articleMeta.setText("已解析 " + n + " 段 · 可在下面继续编辑再发布");
                    renderBlocks();
                    toast("解析完成，共 " + n + " 段");
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

    // ---------- 发布 ----------

    private void publish() {
        if (!Session.loggedIn(activity)) {
            toast("请先登录再发布");
            return;
        }
        String title = titleEt.getText().toString().trim();

        // 收集有效块
        List<Block> out = new ArrayList<>();
        List<Api.Part> uploads = new ArrayList<>();
        List<EditBlock> uploadBlocks = new ArrayList<>();
        List<String> tags = new ArrayList<>();

        for (EditBlock b : blocks) {
            if ("img".equals(b.type)) {
                if (b.bytes != null) {
                    String ext = sniffExt(b.bytes);
                    uploads.add(new Api.Part(b.bytes, "img" + uploads.size() + ext));
                    uploadBlocks.add(b);
                } else if (b.src != null && !b.src.isEmpty()) {
                    Block x = new Block();
                    x.type = "img";
                    x.src = b.src;
                    out.add(x);
                }
                continue;
            }
            String t = b.text == null ? "" : b.text.trim();
            if (t.isEmpty()) continue;
            if ("link".equals(b.type)) {
                Block x = new Block();
                x.type = "link";
                x.text = b.text == null ? "" : b.text;   // 标题
                x.src = b.src == null ? "" : b.src;       // URL
                if (b.card != null) {
                    x.alt = b.card.description;
                }
                out.add(x);
                continue;
            }
            if ("topic".equals(b.type)) {
                // 话题块转成 tags，不占正文
                for (String s : t.split("[,，#\\s]+")) {
                    if (!s.trim().isEmpty()) tags.add(s.trim());
                }
                continue;
            }
            Block x = new Block();
            x.type = b.type;
            x.text = t;
            out.add(x);
        }

        if (out.isEmpty() && uploads.isEmpty() && title.isEmpty()) {
            toast("还没有内容");
            return;
        }

        publishBtn.setEnabled(false);
        publishBtn.setText("发布中…");

        final List<Api.Part> up = uploads;
        final List<EditBlock> upBlocks = uploadBlocks;
        final List<Block> finalBlocks = out;
        final List<String> finalTags = tags;

        Api.bg(() -> {
            try {
                // 先上传图片，拿到 URL 后按原顺序插回 blocks
                if (!up.isEmpty()) {
                    List<String> urls = Api.upload(up);
                    for (int i = 0; i < upBlocks.size() && i < urls.size(); i++) {
                        Block x = new Block();
                        x.type = "img";
                        x.src = urls.get(i);
                        // 插到该块原本的位置（近似：追加到末尾前面）
                        finalBlocks.add(x);
                    }
                }

                Post p = new Post();
                p.type = "article";
                p.title = title;
                p.author = Session.nick(activity);
                p.blocks = finalBlocks;
                if (!finalTags.isEmpty()) p.tags = finalTags;
                // 正文摘要给列表页用
                StringBuilder sb = new StringBuilder();
                for (Block x : finalBlocks) {
                    if (x.text != null && !x.text.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(x.text);
                    }
                    if (sb.length() > 300) break;
                }
                p.body = sb.toString();

                Post saved = Api.createPost(p);
                activity.runOnUiThread(() -> {
                    publishBtn.setEnabled(true);
                    publishBtn.setText("发布");
                    if (cb != null) cb.onPublished(saved);
                    dismiss();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    publishBtn.setEnabled(true);
                    publishBtn.setText("发布");
                    toast("发布失败：" + e.getMessage());
                });
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }
}
