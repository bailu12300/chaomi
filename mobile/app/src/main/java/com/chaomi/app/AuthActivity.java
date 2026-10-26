package com.chaomi.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

/**
 * 登录 / 注册二合一。成功后写入 Session 并返回。
 * 也可「先随便逛逛」，以游客身份浏览（发帖点赞时再提示登录）。
 */
public class AuthActivity extends AppCompatActivity {

    private boolean registerMode = false;

    private EditText emailEt, passEt, nickEt;
    private MaterialButton submitBtn;
    private TextView switchMode, subTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        Ui.fit(this);

        emailEt = findViewById(R.id.emailEt);
        passEt = findViewById(R.id.passEt);
        nickEt = findViewById(R.id.nickEt);
        submitBtn = findViewById(R.id.submitBtn);
        switchMode = findViewById(R.id.switchMode);
        subTitle = findViewById(R.id.subTitle);

        findViewById(R.id.skipBtn).setOnClickListener(v -> finish());

        switchMode.setOnClickListener(v -> {
            registerMode = !registerMode;
            applyMode();
        });

        submitBtn.setOnClickListener(v -> submit());
    }

    private void applyMode() {
        if (registerMode) {
            subTitle.setText("注册一个账号，帖子会记在你名下");
            submitBtn.setText("注册并登录");
            switchMode.setText("已有账号？去登录");
            nickEt.setVisibility(View.VISIBLE);
        } else {
            subTitle.setText("登录后可发帖、评论、点赞");
            submitBtn.setText("登录");
            switchMode.setText("还没有账号？去注册");
            nickEt.setVisibility(View.GONE);
        }
    }

    private void submit() {
        String email = emailEt.getText().toString().trim();
        String pass = passEt.getText().toString();
        String nick = nickEt.getText().toString().trim();

        if (email.isEmpty() || !email.contains("@")) {
            toast("请填写正确的邮箱");
            return;
        }
        if (pass.length() < 6) {
            toast("密码至少 6 位");
            return;
        }

        submitBtn.setEnabled(false);
        boolean reg = registerMode;
        Api.bg(() -> {
            try {
                AuthResult r = reg ? Api.register(email, pass, nick) : Api.login(email, pass);
                runOnUiThread(() -> {
                    submitBtn.setEnabled(true);
                    if (r == null || r.token == null || r.token.isEmpty()) {
                        toast("登录失败：响应异常");
                        return;
                    }
                    Session.save(this, r.token, r.user);
                    toast("欢迎，" + (r.user == null ? "你" : r.user.nick));
                    setResult(RESULT_OK, new Intent());
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    submitBtn.setEnabled(true);
                    toast(e.getMessage() == null ? "操作失败" : e.getMessage());
                });
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
