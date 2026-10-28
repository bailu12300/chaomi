package com.chaomi.app;

import androidx.core.content.ContextCompat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.squareup.picasso.Picasso;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 富文本编辑器（对标微信公众号的写作能力）。
 *
 * 块类型：文字 / 大标题 / 小标题 / 圆点列表 / 数字列表 / 引用 / 代码块 /
 *         图片(含动图) / 视频 / 分割线 / 话题 / 链接卡片 / 引用文章
 *
 * 行内格式（选中文字后点工具栏按钮，自动包裹标记）：
 *   **加粗**  *斜体*  ==高亮==  ~~删除~~  `行内代码`
 *
 * 产出 blocks 数组交后端存储，客户端 ArticleRenderer 原生渲染，无需 WebView。
 */
public class EditorSheet extends BottomSheetDialog {

    public interface OnPublished { void onPublished(Post p); }

    public static final int REQ_PICK = 7101;

    /** 编辑中的块 */
    static class EditBlock {
        String type;            // p|h2|h3|li|ol|quote|code|img|video|hr|topic|link|ref
        String text = "";
        String src = "";
        String alt = "";        // 图片说明 / 视频封面
        String lang = "";       // 代码块语言标注
        String style = "";      // center = 居中
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
    private TextView articleMeta, hint, wordCount;

    private EditBlock pendingImageBlock;   // 正在选图的块
    private EditBlock focusedBlock;        // 当前聚焦块（行内格式作用对象）
    private String articleUrl = "";        // 公众号原文链接
    private String articleCover = "";      // 原文封面
    private String articleAccount = "";    // 公众号名称
    private String articleAvatar = "";     // 公众号头像
    private long articlePubTime = 0;       // 原文发布时间

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
        wordCount = v.findViewById(R.id.wordCount);

        // 块类型工具条
        v.findViewById(R.id.addText).setOnClickListener(x -> addBlock("p"));
        v.findViewById(R.id.addH2).setOnClickListener(x -> addBlock("h2"));
        v.findViewById(R.id.addH3).setOnClickListener(x -> addBlock("h3"));
        v.findViewById(R.id.addUl).setOnClickListener(x -> addBlock("li"));
        v.findViewById(R.id.addOl).setOnClickListener(x -> addBlock("ol"));
        v.findViewById(R.id.addQuote).setOnClickListener(x -> addBlock("quote"));
        v.findViewById(R.id.addCode).setOnClickListener(x -> addBlock("code"));
        v.findViewById(R.id.addImage).setOnClickListener(x -> pickMediaFor(null));
        v.findViewById(R.id.addVideo).setOnClickListener(x -> addVideoBlock());
        v.findViewById(R.id.addHr).setOnClickListener(x -> addBlock("hr"));
        v.findViewById(R.id.addTopic).setOnClickListener(x -> addBlock("topic"));
        v.findViewById(R.id.addLink).setOnClickListener(x -> addLinkBlock());
        v.findViewById(R.id.addRef).setOnClickListener(x -> pickExistingPost());

        // 行内格式工具条
        v.findViewById(R.id.fmtBold).setOnClickListener(x -> wrapSelection("**", "**"));
        v.findViewById(R.id.fmtItalic).setOnClickListener(x -> wrapSelection("*", "*"));
        v.findViewById(R.id.fmtHighlight).setOnClickListener(x -> wrapSelection("==", "=="));
        v.findViewById(R.id.fmtStrike).setOnClickListener(x -> wrapSelection("~~", "~~"));
        v.findViewById(R.id.fmtCode).setOnClickListener(x -> wrapSelection("`", "`"));
        v.findViewById(R.id.fmtCenter).setOnClickListener(x -> toggleCenter());

        v.findViewById(R.id.clearBtn).setOnClickListener(x -> confirmClear());

        parseBtn.setOnClickListener(x -> parseArticle());
        publishBtn.setOnClickListener(x -> publish());

        hint.setText("支持行内格式；发布后为原生文章，读者端不用 WebView 渲染");
        addBlock("p");
        updateWordCount();
    }

    // ---------- 块操作 ----------

    private void addBlock(String type) {
        if ("hr".equals(type)) {
            // 分割线是原子块，不需要输入框
            EditBlock b = new EditBlock();
            b.type = "hr";
            blocks.add(b);
            renderBlocks();
            updateWordCount();
            return;
        }
        EditBlock b = new EditBlock();
        b.type = type;
        blocks.add(b);
        renderBlocks();
        if (!"img".equals(type) && !"video".equals(type) && b.view != null) {
            EditText et = b.view.findViewById(R.id.bText);
            if (et != null) et.requestFocus();
        }
        updateWordCount();
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
            EditText altEt = item.findViewById(R.id.bAlt);
            ImageView img = item.findViewById(R.id.bImage);

            type.setText(label(b.type));
            text.setVisibility(View.VISIBLE);
            img.setVisibility(View.GONE);
            altEt.setVisibility(View.GONE);

            switch (b.type) {
                case "h2":
                    text.setHint("大标题");
                    text.setTextSize(19);
                    text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    text.setMinLines(1);
                    break;
                case "h3":
                    text.setHint("小标题");
                    text.setTextSize(16);
                    text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    text.setMinLines(1);
                    break;
                case "li":
                    text.setHint("列表项内容（每条一块，自动加圆点）");
                    break;
                case "ol":
                    text.setHint("列表项内容（自动编号）");
                    break;
                case "code":
                    text.setHint("贴代码片段…（长代码建议用代码块）");
                    text.setTypeface(android.graphics.Typeface.MONOSPACE);
                    text.setTextSize(12);
                    break;
                case "quote":
                    text.setHint("引用一段话…");
                    text.setBackgroundColor(ContextCompat.getColor(activity, R.color.chaomi_quote_bg));
                    break;
                case "topic":
                    text.setHint("话题名，如 装机 / 显卡 / 二手");
                    text.setSingleLine(true);
                    text.setMinLines(1);
                    break;
                case "hr":
                    text.setVisibility(View.GONE);
                    img.setVisibility(View.GONE);
                    type.setText("──────── 分割线 ────────");
                    break;
                case "img":
                    text.setVisibility(View.GONE);
                    img.setVisibility(View.VISIBLE);
                    altEt.setVisibility(View.VISIBLE);
                    altEt.setText(b.alt == null ? "" : b.alt);
                    final EditBlock fbImg = b;
                    altEt.addTextChangedListener(new SimpleWatcher(s -> fbImg.alt = s));
                    if (b.bytes != null) {
                        try {
                            android.graphics.Bitmap bm = android.graphics.BitmapFactory
                                    .decodeByteArray(b.bytes, 0, b.bytes.length);
                            if (bm != null) img.setImageBitmap(bm);
                        } catch (Exception ignored) {
                        }
                    } else if (b.src != null && !b.src.isEmpty()) {
                        Picasso.get().load(Utils.fullUrl(b.src)).into(img);
                    }
                    img.setOnClickListener(x -> pickMediaFor(fbImg));
                    break;
                case "video":
                    text.setVisibility(View.GONE);
                    img.setVisibility(View.GONE);
                    type.setText("视频" + (b.text == null || b.text.isEmpty() ? "" : " · " + b.text));
                    break;
                case "link":
                case "ref":
                    text.setVisibility(View.GONE);
                    img.setVisibility(b.card != null && b.card.image != null
                            && !b.card.image.isEmpty() ? View.VISIBLE : View.GONE);
                    if (b.card != null && b.card.image != null && !b.card.image.isEmpty()) {
                        Picasso.get().load(Utils.fullUrl(b.card.image)).into(img);
                    }
                    img.setOnClickListener(null);
                    type.setText(("ref".equals(b.type) ? "引用站内文章" : "链接卡片")
                            + (b.card == null ? "" : " · " + cardKind(b.card.kind)));
                    break;
                default:
                    text.setHint("写点什么…");
                    break;
            }

            // 文本绑定（hr/img/video/link/ref 不用）
            if (!"img".equals(b.type) && !"hr".equals(b.type)
                    && !"video".equals(b.type) && !"link".equals(b.type)
                    && !"ref".equals(b.type)) {
                text.setText(b.text);
                final EditBlock fb = b;
                text.addTextChangedListener(new SimpleWatcher(s -> {
                    fb.text = s;
                    updateWordCount();
                }));
                // 记录聚焦块，供行内格式按钮使用
                text.setOnFocusChangeListener((vv, has) -> {
                    if (has) focusedBlock = fb;
                });
                text.setOnClickListener(vv -> focusedBlock = fb);
            }

            // ★ 用对象引用而不是捕获索引：块增删后索引会错乱
            // ★ 用 post 延迟重建：直接在点击回调里 removeAllViews 会吞掉点击
            item.findViewById(R.id.bDel).setOnClickListener(x -> removeBlock(b));
            item.findViewById(R.id.bMoveUp).setOnClickListener(x -> moveBlock(b, -1));
            item.findViewById(R.id.bMoveDown).setOnClickListener(x -> moveBlock(b, 1));

            blockList.addView(item);
        }
    }

    /** 极简 TextWatcher */
    private static class SimpleWatcher implements TextWatcher {
        interface OnText { void on(String s); }
        private final OnText cb;
        SimpleWatcher(OnText cb) { this.cb = cb; }
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
        public void afterTextChanged(Editable s) { cb.on(s == null ? "" : s.toString()); }
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
        updateWordCount();
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
            case "ref": return "引用站内文章";
            case "h2": return "大标题";
            case "h3": return "小标题";
            case "li": return "圆点列表";
            case "ol": return "数字列表";
            case "hr": return "分割线";
            case "video": return "视频";
            default: return "文字";
        }
    }

    // ---------- 行内格式 ----------

    /**
     * 把选中文字用标记包裹；没选中就插入一对标记并把光标放中间。
     */
    private void wrapSelection(String open, String close) {
        if (focusedBlock == null || focusedBlock.view == null) {
            toast("先点一下要编辑的文字块");
            return;
        }
        EditText et = focusedBlock.view.findViewById(R.id.bText);
        if (et == null) return;

        int start = Math.max(et.getSelectionStart(), 0);
        int end = Math.max(et.getSelectionEnd(), 0);
        if (start > end) { int t = start; start = end; end = t; }

        String all = et.getText().toString();
        String selected = all.substring(start, end);
        String replaced = open + selected + close;
        StringBuilder sb = new StringBuilder(all);
        sb.replace(start, end, replaced);
        et.setText(sb.toString());

        if (selected.isEmpty()) {
            et.setSelection(start + open.length());
        } else {
            et.setSelection(start + replaced.length());
        }
        focusedBlock.text = et.getText().toString();
        updateWordCount();
    }

    /** 段落居中 / 取消居中 */
    private void toggleCenter() {
        if (focusedBlock == null) {
            toast("先点一下要居中的文字块");
            return;
        }
        focusedBlock.style = "center".equals(focusedBlock.style) ? "" : "center";
        toast("center".equals(focusedBlock.style) ? "已居中" : "已取消居中");
        postRender();
    }

    private void updateWordCount() {
        if (wordCount == null) return;
        int n = 0;
        for (EditBlock b : blocks) {
            if (b.text != null) n += InlineRenderer.plain(b.text).length();
        }
        wordCount.setText(n + " 字");
    }

    private void confirmClear() {
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("清空内容？")
                .setMessage("正文所有块都会被清掉，标题保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    blocks.clear();
                    EditBlock nb = new EditBlock();
                    nb.type = "p";
                    blocks.add(nb);
                    renderBlocks();
                    updateWordCount();
                })
                .show();
    }

    // ---------- 选图 ----------

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
        updateWordCount();
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

    // ---------- 视频 ----------

    /** 视频块：贴 mp4/m3u8 直链，或公众号文章里的视频页地址 */
    private void addVideoBlock() {
        android.widget.EditText et = new android.widget.EditText(activity);
        et.setHint("https://…/video.mp4");
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        int pad = (int) (14 * activity.getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("插入视频")
                .setMessage("支持 mp4 / m3u8 直链。阅读页会显示视频封面，点击用浏览器或播放器打开。")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加", (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (url.isEmpty()) return;
                    EditBlock b = new EditBlock();
                    b.type = "video";
                    b.src = url;
                    b.text = "link";
                    blocks.add(b);
                    renderBlocks();
                    toast("已添加视频");
                })
                .show();
    }

    // ---------- 链接卡片 ----------

    private void addLinkBlock() {
        android.widget.EditText et = new android.widget.EditText(activity);
        et.setHint("https://…");
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        int pad = (int) (14 * activity.getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("插入链接")
                .setMessage("自动抓取标题/摘要/封面，以卡片展示。公众号链接会转成原生文章。")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("解析", (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (url.isEmpty()) return;
                    resolveLink(url, "link");
                })
                .show();
    }

    private void resolveLink(String url, String blockType) {
        toast("解析中…");
        Api.bg(() -> {
            try {
                LinkCard c = Api.linkPreview(url);
                activity.runOnUiThread(() -> {
                    EditBlock b = new EditBlock();
                    b.type = blockType;
                    b.src = url;
                    b.card = c;
                    b.text = c == null ? url : (c.title == null ? url : c.title);
                    blocks.add(b);
                    renderBlocks();
                    toast(c != null && c.ok ? "已生成卡片" : "只拿到部分信息，仍可发布");
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    EditBlock b = new EditBlock();
                    b.type = blockType;
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

    // ---------- 引用站内旧文章 ----------

    /** 列出最近的文章，选一篇作为引用块插入（形成站内互链） */
    private void pickExistingPost() {
        toast("加载文章列表…");
        Api.bg(() -> {
            List<Post> list;
            try {
                list = Api.feed(0, "", "", "", false);
            } catch (Exception e) {
                activity.runOnUiThread(() -> toast("加载失败：" + e.getMessage()));
                return;
            }
            // 只保留有标题的文章类
            List<Post> arts = new ArrayList<>();
            for (Post p : list) {
                if (p.title != null && !p.title.trim().isEmpty()) arts.add(p);
                if (arts.size() >= 30) break;
            }
            activity.runOnUiThread(() -> {
                if (arts.isEmpty()) {
                    toast("还没有可引用的文章");
                    return;
                }
                String[] names = new String[arts.size()];
                for (int i = 0; i < arts.size(); i++) {
                    Post p = arts.get(i);
                    String t = p.title == null ? "（无标题）" : p.title;
                    if (t.length() > 28) t = t.substring(0, 28) + "…";
                    names[i] = t;
                }
                new androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle("引用站内文章")
                        .setItems(names, (d, which) -> insertRefBlock(arts.get(which)))
                        .setNegativeButton("取消", null)
                        .show();
            });
        });
    }

    /** 把选中的文章做成引用卡片块 */
    private void insertRefBlock(Post p) {
        LinkCard card = new LinkCard();
        card.ok = true;
        card.title = p.title;
        card.description = p.body == null ? "" : InlineRenderer.plain(p.body);
        card.image = p.cover;
        card.kind = "article";
        card.siteName = "超米";

        EditBlock b = new EditBlock();
        b.type = "ref";
        b.src = "/community?post=" + p.id;   // 站内深链
        b.text = p.title == null ? "站内文章" : p.title;
        b.card = card;
        blocks.add(b);
        renderBlocks();
        toast("已引用：" + (p.title == null ? "文章" : p.title));
    }

    /** 按文件头判断扩展名：动图必须保留 .gif，否则服务端按扩展名拒收 */
    private String sniffExt(byte[] b) {
        if (b == null || b.length < 12) return ".jpg";
        if (b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38) return ".gif";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return ".png";
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
                    if (a == null || a.title == null || a.title.trim().isEmpty()) {
                        toast("解析失败：没拿到标题");
                        return;
                    }
                    titleEt.setText(a.title.trim());
                    articleUrl = url;
                    articleCover = a.cover == null ? "" : a.cover;
                    articleAccount = a.accountName == null ? "" : a.accountName;
                    articleAvatar = a.accountAvatar == null ? "" : a.accountAvatar;
                    articlePubTime = a.publishTime;

                    blocks.clear();
                    renderBlocks();

                    articleMeta.setVisibility(View.VISIBLE);
                    StringBuilder meta = new StringBuilder();
                    if (articleAccount != null && !articleAccount.isEmpty()) {
                        meta.append(articleAccount).append(" · ");
                    }
                    meta.append("正文将在阅读页用浏览器渲染");
                    articleMeta.setText(meta.toString() + "\n" + a.title.trim());
                    toast("标题已获取，点发布即可");
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
                    x.alt = b.alt;
                    out.add(x);
                }
                continue;
            }
            if ("hr".equals(b.type)) {
                Block x = new Block();
                x.type = "hr";
                out.add(x);
                continue;
            }
            if ("video".equals(b.type)) {
                if (b.src != null && !b.src.isEmpty()) {
                    Block x = new Block();
                    x.type = "video";
                    x.src = b.src;
                    x.alt = b.alt;
                    out.add(x);
                }
                continue;
            }

            String t = b.text == null ? "" : b.text.trim();
            if (t.isEmpty()) continue;

            if ("link".equals(b.type) || "ref".equals(b.type)) {
                Block x = new Block();
                x.type = "link";
                x.text = b.text;                       // 标题
                x.src = b.src == null ? "" : b.src;    // URL
                if (b.card != null) x.alt = b.card.description;
                out.add(x);
                continue;
            }
            if ("topic".equals(b.type)) {
                for (String s : t.split("[,，#\\s]+")) {
                    if (!s.trim().isEmpty()) tags.add(s.trim());
                }
                continue;
            }
            Block x = new Block();
            x.type = b.type;
            x.text = t;
            if (b.style != null && !b.style.isEmpty()) x.style = b.style;
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
                if (!up.isEmpty()) {
                    List<String> urls = Api.upload(up);
                    for (int i = 0; i < upBlocks.size() && i < urls.size(); i++) {
                        Block x = new Block();
                        x.type = "img";
                        x.src = urls.get(i);
                        x.alt = upBlocks.get(i).alt;
                        finalBlocks.add(x);
                    }
                }

                Post p = new Post();
                p.type = "article";
                p.title = title;
                p.author = Session.nick(activity);

                // 有公众号原文链接时：只存链接 + 封面，正文交给浏览器渲染
                if (articleUrl != null && !articleUrl.isEmpty()) {
                    p.url = articleUrl;
                    if (articleCover != null && !articleCover.isEmpty()) {
                        p.cover = articleCover;
                    }
                    p.accountName = articleAccount;
                    p.accountAvatar = articleAvatar;
                    p.publishTime = articlePubTime;
                    p.body = "";
                    p.blocks = new ArrayList<>();
                } else {
                    p.blocks = finalBlocks;
                }
                if (!finalTags.isEmpty()) p.tags = finalTags;

                // 摘要：去掉行内标记，取前 300 字
                StringBuilder sb = new StringBuilder();
                for (Block x : (p.blocks == null ? finalBlocks : p.blocks)) {
                    if (x.text != null && !x.text.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(InlineRenderer.plain(x.text));
                    }
                    if (sb.length() > 300) break;
                }
                if (p.body == null || p.body.isEmpty()) p.body = sb.toString();

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
