package com.chaomi.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

/**
 * 登录 / 注册 / 忘记密码 三合一。
 *
 * 三种模式共用一套表单：
 *   LOGIN    邮箱 + 密码
 *   REGISTER 邮箱 + 密码 + 昵称 + 验证码
 *   RESET    邮箱 + 验证码 + 新密码
 */
public class AuthActivity extends AppCompatActivity {

    private enum Mode { LOGIN, REGISTER, RESET }

    private Mode mode = Mode.LOGIN;

    private EditText emailEt, passEt, nickEt, codeEt;
    private MaterialButton submitBtn, sendCodeBtn;
    private TextView switchMode, subTitle, forgotBtn;
    private View codeRow;

    private CountDownTimer timer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        Ui.fit(this);

        emailEt = findViewById(R.id.emailEt);
        passEt = findViewById(R.id.passEt);
        nickEt = findViewById(R.id.nickEt);
        codeEt = findViewById(R.id.codeEt);
        submitBtn = findViewById(R.id.submitBtn);
        sendCodeBtn = findViewById(R.id.sendCodeBtn);
        switchMode = findViewById(R.id.switchMode);
        subTitle = findViewById(R.id.subTitle);
        forgotBtn = findViewById(R.id.forgotBtn);
        codeRow = findViewById(R.id.codeRow);

        View skip = findViewById(R.id.skipBtn);
        if (skip != null) skip.setOnClickListener(v -> finish());

        sendCodeBtn.setOnClickListener(v -> sendCode());
        forgotBtn.setOnClickListener(v -> applyMode(Mode.RESET));
        switchMode.setOnClickListener(v -> {
            if (mode == Mode.RESET) {
                applyMode(Mode.LOGIN);
            } else {
                applyMode(mode == Mode.REGISTER ? Mode.LOGIN : Mode.REGISTER);
            }
        });
        submitBtn.setOnClickListener(v -> submit());

        applyMode(Mode.LOGIN);
    }

    private void applyMode(Mode m) {
        mode = m;
        switch (m) {
            case REGISTER:
                subTitle.setText("注册账号，帖子会记在你名下");
                submitBtn.setText("注册并登录");
                switchMode.setText("已有账号？去登录");
                nickEt.setVisibility(View.VISIBLE);
                codeRow.setVisibility(View.VISIBLE);
                forgotBtn.setVisibility(View.GONE);
                passEt.setHint("密码（至少 6 位）");
                break;
            case RESET:
                subTitle.setText("通过邮箱验证码重置密码");
                submitBtn.setText("重置密码");
                switchMode.setText("返回登录");
                nickEt.setVisibility(View.GONE);
                codeRow.setVisibility(View.VISIBLE);
                forgotBtn.setVisibility(View.GONE);
                passEt.setHint("新密码（至少 6 位）");
                break;
            default:
                subTitle.setText("登录后可发帖、评论、点赞");
                submitBtn.setText("登录");
                switchMode.setText("还没有账号？去注册");
                nickEt.setVisibility(View.GONE);
                codeRow.setVisibility(View.GONE);
                forgotBtn.setVisibility(View.VISIBLE);
                passEt.setHint("密码");
                break;
        }
    }

    /** 发验证码（注册 / 重置共用） */
    private void sendCode() {
        String email = emailEt.getText().toString().trim();
        if (email.isEmpty() || !email.contains("@")) {
            toast("请先填正确邮箱");
            return;
        }
        final String purpose = mode == Mode.RESET ? "reset" : "register";
        sendCodeBtn.setEnabled(false);
        sendCodeBtn.setText("发送中…");

        Api.bg(() -> {
            try {
                String devCode = Api.sendCode(email, purpose);
                runOnUiThread(() -> {
                    // 内测模式：服务端未配 SMTP 时会直接给码，自动填入省事
                    if (devCode != null && !devCode.isEmpty()) {
                        codeEt.setText(devCode);
                        toast("内测模式：验证码 " + devCode + "（已自动填入）");
                    } else {
                        toast("验证码已发送，请查收邮件");
                    }
                    startCountdown();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    sendCodeBtn.setEnabled(true);
                    sendCodeBtn.setText("获取验证码");
                    toast(e.getMessage() == null ? "发送失败" : e.getMessage());
                });
            }
        });
    }

    private void startCountdown() {
        if (timer != null) timer.cancel();
        sendCodeBtn.setEnabled(false);
        timer = new CountDownTimer(60_000, 1000) {
            @Override
            public void onTick(long ms) {
                sendCodeBtn.setText((ms / 1000) + " 秒");
            }

            @Override
            public void onFinish() {
                sendCodeBtn.setEnabled(true);
                sendCodeBtn.setText("重新发送");
            }
        }.start();
    }

    private void submit() {
        String email = emailEt.getText().toString().trim();
        String pass = passEt.getText().toString();
        String nick = nickEt.getText().toString().trim();
        String code = codeEt.getText().toString().trim();

        if (email.isEmpty() || !email.contains("@")) {
            toast("请填写正确的邮箱");
            return;
        }
        if (pass.length() < 6) {
            toast("密码至少 6 位");
            return;
        }
        if (mode == Mode.REGISTER || mode == Mode.RESET) {
            if (code.isEmpty()) {
                showError("还差一个验证码",
                        "请点「获取验证码」，到邮箱里找 6 位数字填入。\n\n"
                        + "没收到？检查垃圾邮件箱，或稍后点「重新发送验证码」。"
                        + (email.contains("@") ? "\n\n当前邮箱：" + email : ""));
                return;
            }
            if (code.length() < 4) {
                showError("验证码格式不对", "验证码是邮件里的 6 位数字，当前填写的是「" + code + "」。");
                return;
            }
        }

        submitBtn.setEnabled(false);
        final Mode m = mode;

        Api.bg(() -> {
            try {
                if (m == Mode.RESET) {
                    Api.resetPassword(email, code, pass);
                    runOnUiThread(() -> {
                        submitBtn.setEnabled(true);
                        toast("密码已重置，请用新密码登录");
                        applyMode(Mode.LOGIN);
                        passEt.setText("");
                        codeEt.setText("");
                        emailEt.setText(email);
                    });
                    return;
                }

                AuthResult r = m == Mode.REGISTER
                        ? Api.register(email, pass, nick, code)
                        : Api.login(email, pass);

                runOnUiThread(() -> {
                    submitBtn.setEnabled(true);
                    submitBtn.setText(btnLabel(m));
                    if (r == null || r.token == null || r.token.isEmpty()) {
                        showError("注册失败", "服务器返回的数据不完整，请稍后重试");
                        return;
                    }
                    Session.save(this, r.token, r.user);
                    String uid = r.user == null ? "" : r.user.uid;
                    toast("欢迎，" + (r.user == null ? "你" : r.user.nick)
                            + (uid == null || uid.isEmpty() ? "" : "（" + uid + "）"));
                    setResult(RESULT_OK, new Intent());
                    finish();
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "操作失败" : e.getMessage();
                runOnUiThread(() -> {
                    submitBtn.setEnabled(true);
                    submitBtn.setText(btnLabel(mode));
                    // 用对话框而不是 Toast：Toast 一闪而过，用户经常看不到
                    showError("操作未成功", msg);
                });
            }
        });
    }

    /** 弹明确的错误框，并给出可执行的下一步 */
    private void showError(String title, String msg) {
        String tip = msg == null ? "" : msg;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(tip)
                .setPositiveButton("知道了", null);

        // 针对常见错误给直接的动作，而不是让用户自己猜
        if (tip.contains("验证码")) {
            b.setNeutralButton("重新发送验证码", (d, w) -> {
                sendCodeBtn.setEnabled(true);
                sendCodeBtn.setText("获取验证码");
                sendCode();
            });
        } else if (tip.contains("已注册") || tip.contains("邮箱或密码")) {
            b.setNeutralButton(mode == Mode.LOGIN ? "去注册" : "去登录", (d, w) -> {
                applyMode(mode == Mode.LOGIN ? Mode.REGISTER : Mode.LOGIN);
            });
        } else if (tip.contains("网络") || tip.contains("超时") || tip.contains("connect")) {
            b.setNeutralButton("重试", (d, w) -> submit());
        }
        b.show();
    }

    private String btnLabel(Mode m) {
        switch (m) {
            case REGISTER: return "注册并登录";
            case RESET: return "重置密码";
            default: return "登录";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (timer != null) timer.cancel();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
