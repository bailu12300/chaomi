package com.chaomi.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 账号设置：修改密码 / 登录设备 / 我的内容 / 注销账号。
 *
 * 为什么单独成页而不是塞进资料页：
 *   资料页是「展示自己」的（头像昵称 bio 帖子列表），
 *   这里是「管理账号」的（安全与销毁），性质不同，混在一起会让资料页变长且危险操作容易被误触。
 */
public class AccountSettingsActivity extends AppCompatActivity {

    private EditText oldPass, newPass, newPass2;
    private MaterialButton btnChangePass;
    private LinearLayout sessionsBox, contentsBox;
    private TextView contentsTitle;
    private MaterialButton btnKick, btnDelete;

    private String contentKind = "posts";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_settings);
        Ui.fit(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) tb.setNavigationOnClickListener(v -> finish());

        oldPass = findViewById(R.id.asOldPass);
        newPass = findViewById(R.id.asNewPass);
        newPass2 = findViewById(R.id.asNewPass2);
        btnChangePass = findViewById(R.id.asChangePass);
        sessionsBox = findViewById(R.id.asSessions);
        contentsBox = findViewById(R.id.asContents);
        contentsTitle = findViewById(R.id.asContentsTitle);
        btnKick = findViewById(R.id.asKick);
        btnDelete = findViewById(R.id.asDelete);

        btnChangePass.setOnClickListener(v -> changePassword());
        btnKick.setOnClickListener(v -> kickOthers());
        btnDelete.setOnClickListener(v -> confirmDelete());

        // 我的内容：三个 tab
        findViewById(R.id.asTabPosts).setOnClickListener(v -> loadContents("posts"));
        findViewById(R.id.asTabComments).setOnClickListener(v -> loadContents("comments"));
        findViewById(R.id.asTabLikes).setOnClickListener(v -> loadContents("likes"));

        loadSessions();
        loadContents("posts");
    }

    // ---------- 修改密码 ----------

    private void changePassword() {
        String o = oldPass.getText().toString();
        String p1 = newPass.getText().toString();
        String p2 = newPass2.getText().toString();
        if (o.isEmpty()) { toast("请输入原密码"); return; }
        if (p1.length() < 6) { toast("新密码至少 6 位"); return; }
        if (!p1.equals(p2)) { toast("两次输入的新密码不一致"); return; }

        btnChangePass.setEnabled(false);
        Api.bg(() -> {
            try {
                String msg = Api.changePassword(o, p1);
                runOnUiThread(() -> {
                    toast(msg == null ? "密码已修改" : msg);
                    oldPass.setText("");
                    newPass.setText("");
                    newPass2.setText("");
                    btnChangePass.setEnabled(true);
                    loadSessions();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    toast(e.getMessage() == null ? "修改失败" : e.getMessage());
                    btnChangePass.setEnabled(true);
                });
            }
        });
    }

    // ---------- 登录设备 ----------

    private void loadSessions() {
        sessionsBox.removeAllViews();
        addHint(sessionsBox, "加载中…");
        Api.bg(() -> {
            try {
                List<Api.SessionInfo> list = Api.sessions();
                runOnUiThread(() -> {
                    sessionsBox.removeAllViews();
                    if (list == null || list.isEmpty()) {
                        addHint(sessionsBox, "暂无会话");
                        return;
                    }
                    SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
                    for (Api.SessionInfo s : list) {
                        String txt = s.tokenMasked + (s.current ? "（当前设备）" : "")
                                + "\n登录于 " + f.format(new Date(s.createdAt))
                                + " · 有效期至 " + f.format(new Date(s.expiresAt));
                        addHint(sessionsBox, txt);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    sessionsBox.removeAllViews();
                    addHint(sessionsBox, "加载失败：" + e.getMessage());
                });
            }
        });
    }

    private void kickOthers() {
        new AlertDialog.Builder(this)
                .setTitle("退出其它设备")
                .setMessage("只保留当前设备登录，其它设备需重新登录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定退出", (d, w) -> Api.bg(() -> {
                    try {
                        String msg = Api.kickOtherSessions();
                        runOnUiThread(() -> {
                            toast(msg == null ? "已退出其它设备" : msg);
                            loadSessions();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> toast("操作失败"));
                    }
                }))
                .show();
    }

    // ---------- 我的内容 ----------

    private void loadContents(String kind) {
        contentKind = kind;
        contentsTitle.setText(kind.equals("posts") ? "我的帖子"
                : kind.equals("comments") ? "我的评论" : "我的点赞");
        contentsBox.removeAllViews();
        addHint(contentsBox, "加载中…");

        Api.bg(() -> {
            try {
                List<String> lines = Api.myContents(kind);
                runOnUiThread(() -> {
                    contentsBox.removeAllViews();
                    if (lines == null || lines.isEmpty()) {
                        addHint(contentsBox, "还没有内容");
                        return;
                    }
                    for (String l : lines) addHint(contentsBox, l);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    contentsBox.removeAllViews();
                    addHint(contentsBox, "加载失败：" + (e.getMessage() == null ? "网络异常" : e.getMessage()));
                });
            }
        });
    }

    private void addHint(LinearLayout box, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setLineSpacing(4f, 1f);
        tv.setTextColor(ContextCompat.getColor(this, R.color.chaomi_text_dim));
        tv.setPadding(0, dp(7), 0, dp(7));
        box.addView(tv);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- 注销账号 ----------

    private void confirmDelete() {
        final EditText pw = new EditText(this);
        pw.setHint("输入登录密码");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        final EditText cf = new EditText(this);
        cf.setHint("输入「注销」二字确认");
        cf.setInputType(InputType.TYPE_CLASS_TEXT);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(pw);
        box.addView(cf);
        TextView warn = new TextView(this);
        warn.setText("注销会永久删除你的帖子、评论、点赞，无法恢复。");
        warn.setTextSize(12f);
        warn.setTextColor(ContextCompat.getColor(this, R.color.chaomi_up));
        warn.setPadding(0, dp(10), 0, 0);
        box.addView(warn);

        new AlertDialog.Builder(this)
                .setTitle("注销账号")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("永久注销", (d, w) -> {
                    String p = pw.getText().toString();
                    String c = cf.getText().toString().trim();
                    if (p.isEmpty()) { toast("请输入密码"); return; }
                    if (!c.equals("注销")) { toast("请输入「注销」二字确认"); return; }
                    Api.bg(() -> {
                        try {
                            Api.deleteAccount(p, c);
                            Session.clear(this);
                            runOnUiThread(() -> {
                                toast("账号已注销");
                                Intent i = new Intent(this, MainActivity.class);
                                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(i);
                                finish();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> toast(
                                    e.getMessage() == null ? "注销失败" : e.getMessage()));
                        }
                    });
                })
                .show();
    }
}
