package com.chaomi.app;

import androidx.core.content.ContextCompat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.squareup.picasso.Picasso;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 个人主页：头像 / 昵称 / 唯一 UID / 简介 / 我的内容。
 * 也可以用它按 UID 查别人。
 */
public class ProfileActivity extends AppCompatActivity {

    private static final int REQ_AVATAR = 7201;

    private ImageView avatar;
    private TextView avatarLetter, nick, uidText, meta, bio, postTitle;
    private LinearLayout ownerBar, guestBar;
    private EditText searchUid;
    private RecyclerView postsView;

    private final List<Post> posts = new ArrayList<>();
    private FeedAdapter adapter;
    private User me;
    private boolean viewingSelf = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);
        Ui.fit(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) tb.setNavigationOnClickListener(v -> finish());

        avatar = findViewById(R.id.avatar);
        avatarLetter = findViewById(R.id.avatarLetter);
        nick = findViewById(R.id.nick);
        uidText = findViewById(R.id.uid);
        meta = findViewById(R.id.meta);
        bio = findViewById(R.id.bio);
        postTitle = findViewById(R.id.postTitle);
        ownerBar = findViewById(R.id.ownerBar);
        guestBar = findViewById(R.id.guestBar);
        searchUid = findViewById(R.id.searchUid);
        postsView = findViewById(R.id.myPosts);

        postsView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FeedAdapter(posts, this::openDetail, p -> { /* 主页不给赞，进详情 */ });
        postsView.setAdapter(adapter);

        // ★ 头像点击要绑在容器上：无头像时 avatar 是 INVISIBLE，绑它点不到
        View avatarBox = findViewById(R.id.avatarBox);
        if (avatarBox != null) {
            avatarBox.setOnClickListener(v -> {
                if (viewingSelf) pickAvatar();
            });
        } else {
            avatar.setOnClickListener(v -> {
                if (viewingSelf) pickAvatar();
            });
        }
        // 首字占位也要能点
        avatarLetter.setOnClickListener(v -> {
            if (viewingSelf) pickAvatar();
        });

        findViewById(R.id.btnEdit).setOnClickListener(v -> editProfile());
        findViewById(R.id.btnLogout).setOnClickListener(v -> confirmLogout());
        findViewById(R.id.btnSearch).setOnClickListener(v -> searchByUid());

        loadSelf();
    }

    private void loadSelf() {
        if (!Session.loggedIn(this)) {
            // 没登录也能查人
            viewingSelf = false;
            ownerBar.setVisibility(View.GONE);
            guestBar.setVisibility(View.VISIBLE);
            nick.setText("未登录");
            uidText.setText("");
            meta.setText("登录后可拥有自己的主页");
            bio.setText("");
            postTitle.setVisibility(View.GONE);
            return;
        }
        viewingSelf = true;
        ownerBar.setVisibility(View.VISIBLE);
        guestBar.setVisibility(View.GONE);

        Api.bg(() -> {
            try {
                User u = Api.me();
                runOnUiThread(() -> {
                    me = u;
                    bindUser(u, true);
                    loadPosts(u.id);
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("读取资料失败：" + e.getMessage()));
            }
        });
    }

    private void bindUser(User u, boolean self) {
        if (u == null) return;
        nick.setText(u.nick == null || u.nick.isEmpty() ? "匿名" : u.nick);
        uidText.setText("UID " + (u.uid == null ? "—" : u.uid));
        StringBuilder sb = new StringBuilder();
        if (u.postCount > 0) sb.append(u.postCount).append(" 条内容");
        if (u.createdAt > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("加入于 ").append(Utils.formatTime(u.createdAt));
        }
        // 只有自己能看到 IP
        if (self && u.lastIp != null && !u.lastIp.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("IP ").append(u.lastIp);
        }
        meta.setText(sb.toString());
        bio.setText(u.bio == null || u.bio.isEmpty()
                ? (self ? "还没有简介，点「编辑资料」写一句" : "") : u.bio);

        // 头像：有图用图，没图用首字母 + 颜色
        avatarLetter.setText(initialOf(nick.getText().toString()));
        avatarLetter.setBackgroundColor(colorOf(nick.getText().toString()));
        if (u.avatar != null && !u.avatar.isEmpty()) {
            avatarLetter.setVisibility(View.GONE);
            avatar.setVisibility(View.VISIBLE);
            Picasso.get().load(Utils.fullUrl(u.avatar)).into(avatar);
        } else {
            avatar.setVisibility(View.INVISIBLE);
            avatarLetter.setVisibility(View.VISIBLE);
        }
    }

    private void loadPosts(long userId) {
        Api.bg(() -> {
            try {
                List<Post> list = Api.feed(0, null, null, null, true);
                runOnUiThread(() -> {
                    posts.clear();
                    posts.addAll(list);
                    adapter.notifyDataSetChanged();
                    postTitle.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
                });
            } catch (Exception ignored) {
            }
        });
    }

    // ---------- 编辑资料 ----------

    private void editProfile() {
        if (me == null) {
            toast("资料还没加载好");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText etNick = new EditText(this);
        etNick.setHint("昵称（最多 20 字）");
        etNick.setSingleLine(true);
        etNick.setMaxLines(1);
        etNick.setText(me.nick);
        box.addView(etNick);

        EditText etBio = new EditText(this);
        etBio.setHint("简介（最多 120 字）");
        etBio.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etBio.setMinLines(2);
        etBio.setGravity(android.view.Gravity.TOP);
        etBio.setText(me.bio == null ? "" : me.bio);
        box.addView(etBio);

        TextView hint = new TextView(this);
        hint.setText("\nUID " + (me.uid == null ? "—" : me.uid) + " 不可修改，可用于被搜索");
        hint.setTextColor(ContextCompat.getColor(this, R.color.chaomi_text_dim));
        hint.setTextSize(11);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle("编辑资料")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> saveProfile(
                        etNick.getText().toString().trim(),
                        etBio.getText().toString().trim()))
                .show();
    }

    private void saveProfile(String nick, String bio) {
        if (nick.isEmpty()) {
            toast("昵称不能为空");
            return;
        }
        Api.bg(() -> {
            try {
                User u = Api.updateProfile(nick, bio, null);
                runOnUiThread(() -> {
                    me = u;
                    Session.saveUser(this, u);
                    bindUser(u, true);
                    toast("已保存");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("保存失败：" + e.getMessage()));
            }
        });
    }

    // ---------- 头像 ----------

    private void pickAvatar() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(i, REQ_AVATAR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_AVATAR || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        byte[] bytes = readUri(data.getData());
        if (bytes == null) {
            toast("读取图片失败");
            return;
        }
        if (bytes.length > 5 * 1024 * 1024) {
            toast("图片太大（限 5MB）");
            return;
        }
        toast("上传中…");
        String ext = bytes.length > 4 && bytes[0] == 0x89 ? ".png"
                : (bytes.length > 4 && bytes[0] == 0x47 ? ".gif" : ".jpg");
        Api.bg(() -> {
            try {
                String url = Api.uploadAvatar(bytes, "avatar" + ext);
                runOnUiThread(() -> {
                    if (url == null || url.isEmpty()) {
                        toast("上传失败");
                        return;
                    }
                    if (me != null) me.avatar = url;
                    bindUser(me, true);
                    toast("头像已更新");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("上传失败：" + e.getMessage()));
            }
        });
    }

    private byte[] readUri(Uri u) {
        try {
            InputStream is = getContentResolver().openInputStream(u);
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

    // ---------- 查别人 ----------

    private void searchByUid() {
        String uid = searchUid.getText().toString().trim().toUpperCase();
        if (uid.isEmpty()) {
            toast("请输入 UID");
            return;
        }
        Api.bg(() -> {
            try {
                Map<String, Object> d = Api.userByUid(uid);
                runOnUiThread(() -> bindGuest(d));
            } catch (Exception e) {
                runOnUiThread(() -> toast("找不到该用户"));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void bindGuest(Map<String, Object> d) {
        Object uo = d.get("user");
        if (!(uo instanceof Map)) return;
        Map<String, Object> um = (Map<String, Object>) uo;
        User u = new User();
        u.id = ((Number) um.getOrDefault("id", 0)).longValue();
        u.uid = String.valueOf(um.getOrDefault("uid", ""));
        u.nick = String.valueOf(um.getOrDefault("nick", ""));
        u.bio = String.valueOf(um.getOrDefault("bio", ""));
        u.avatar = String.valueOf(um.getOrDefault("avatar", ""));
        u.createdAt = ((Number) um.getOrDefault("createdAt", 0)).longValue();
        u.postCount = ((Number) um.getOrDefault("postCount", 0)).intValue();

        viewingSelf = false;
        ownerBar.setVisibility(View.GONE);
        bindUser(u, false);

        Object po = d.get("posts");
        posts.clear();
        if (po instanceof List) {
            com.google.gson.Gson g = new com.google.gson.Gson();
            List<Post> list = g.fromJson(g.toJson(po),
                    new com.google.gson.reflect.TypeToken<List<Post>>() {}.getType());
            if (list != null) posts.addAll(list);
        }
        adapter.notifyDataSetChanged();
        postTitle.setVisibility(posts.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------- 工具 ----------

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("退出后需要重新登录才能发帖。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (d, w) -> Api.bg(() -> {
                    try {
                        Api.logout();
                    } catch (Exception ignored) {
                    }
                    runOnUiThread(() -> {
                        Session.clear(this);
                        toast("已退出");
                        finish();
                    });
                }))
                .show();
    }

    private String initialOf(String s) {
        if (s == null || s.trim().isEmpty()) return "超";
        return s.trim().substring(0, 1).toUpperCase();
    }

    /** 昵称首字决定头像底色，保证同一个人颜色稳定 */
    private int colorOf(String s) {
        int h = s == null ? 0 : s.hashCode();
        int[] palette = {
                ContextCompat.getColor(this, R.color.chaomi_accent_2), ContextCompat.getColor(this, R.color.chaomi_ok), 0xFF8957e5, ContextCompat.getColor(this, R.color.chaomi_warn),
                ContextCompat.getColor(this, R.color.chaomi_danger), 0xFF0d9488, 0xFF9333ea, 0xFFc2410c
        };
        return palette[Math.abs(h) % palette.length];
    }

    private void openDetail(Post p) {
        Intent i = new Intent(this, PostDetailActivity.class);
        i.putExtra("postId", p.id);
        startActivity(i);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
