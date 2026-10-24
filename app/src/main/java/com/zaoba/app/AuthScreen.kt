package com.zaoba.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import kotlinx.coroutines.launch
import org.json.JSONObject

private val PageBg = Theme.PageBg
private val Brand = Theme.Brand
private val TextPrimary = Theme.TextPrimary
private val TextSecondary = Theme.TextSecondary

/** 账号体系 ViewModel（登录态持久化） */
class AuthViewModel(app: Application) : AndroidViewModel(app) {
    var busy by mutableStateOf(false)
        private set
    var msg by mutableStateOf<String?>(null)
        private set
    // 登录态：从 Session 持久化恢复
    var user by mutableStateOf(Session.load(getApplication()))
        private set
    val loggedIn: Boolean get() = user.isLoggedIn
    val token: String get() = user.token

    fun sendCode(email: String, onDone: (String) -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true; msg = null
            try {
                val r = HwApi.sendCode(email)
                if (r.optInt("code") == 0) {
                    msg = "验证码已发送，请查收邮箱"
                    onDone(r.optString("msg", "已发送"))
                } else {
                    msg = r.optString("msg", "发送失败")
                }
            } catch (e: Exception) {
                msg = e.message ?: "网络异常"
            } finally { busy = false }
        }
    }

    fun register(username: String, password: String, email: String, code: String, nickname: String, device: JSONObject) {
        if (busy) return
        viewModelScope.launch {
            busy = true; msg = null
            try {
                val r = HwApi.register(username, password, email, code, nickname, device)
                if (r.optInt("code") == 0) {
                    Session.save(getApplication(), r)
                    user = Session.load(getApplication())
                    msg = "注册成功，已登录"
                } else {
                    msg = r.optString("msg", "注册失败")
                }
            } catch (e: Exception) {
                msg = e.message ?: "网络异常"
            } finally { busy = false }
        }
    }

    fun login(username: String, password: String) {
        if (busy) return
        viewModelScope.launch {
            busy = true; msg = null
            try {
                val r = HwApi.login(username, password)
                if (r.optInt("code") == 0) {
                    Session.save(getApplication(), r)
                    user = Session.load(getApplication())
                    msg = "登录成功"
                } else {
                    msg = r.optString("msg", "登录失败")
                }
            } catch (e: Exception) {
                msg = e.message ?: "网络异常"
            } finally { busy = false }
        }
    }

    fun logout() {
        Session.clear(getApplication())
        user = Session.load(getApplication())
        msg = "已退出登录"
    }
}

@Composable
fun AuthScreen(vm: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    var mode by remember { mutableStateOf("login") }  // "login" | "register"

    if (vm.loggedIn) {
        // 已登录：显示用户信息 + 退出
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PageBg)
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("早八", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Brand)
            Spacer(Modifier.height(24.dp))
            Text(
                vm.user.nickname.ifBlank { vm.user.username.ifBlank { "已登录" } },
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary
            )
            if (vm.user.username.isNotBlank()) {
                Text("用户名：${vm.user.username}", fontSize = 14.sp, color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
            }
            if (vm.user.email.isNotBlank()) {
                Text("邮箱：${vm.user.email}", fontSize = 14.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { vm.logout() },
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("退出登录", fontSize = 16.sp)
            }
            vm.msg?.let {
                Text(it, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("早八", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Brand)
        Text(
            if (mode == "login") "登录账号" else "注册账号",
            fontSize = 16.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp)
        )

        if (mode == "login") {
            LoginForm(vm)
        } else {
            RegisterForm(vm)
        }

        TextButton(onClick = { mode = if (mode == "login") "register" else "login" }) {
            Text(
                if (mode == "login") "没有账号？去注册" else "已有账号？去登录",
                color = Brand
            )
        }

        vm.msg?.let {
            Text(it, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun LoginForm(vm: AuthViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Field("用户名", username) { username = it }
    Field("密码", password, isPassword = true) { password = it }

    Button(
        onClick = { vm.login(username.trim(), password) },
        enabled = !vm.busy && username.isNotBlank() && password.isNotBlank(),
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Brand)
    ) {
        Text(if (vm.busy) "登录中…" else "登录", fontSize = 16.sp)
    }
}

@Composable
private fun RegisterForm(vm: AuthViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Field("邮箱", email, keyboardType = KeyboardType.Email) { email = it }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Field("验证码", code, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f)) { code = it }
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = { vm.sendCode(email.trim()) { code = "" } },
            enabled = !vm.busy && email.contains("@"),
            colors = ButtonDefaults.buttonColors(containerColor = Brand)
        ) {
            Text("发送验证码", fontSize = 13.sp)
        }
    }

    Field("用户名", username) { username = it }
    Field("昵称（汉字，可选）", nickname) { nickname = it }
    Field("密码", password, isPassword = true) { password = it }

    Button(
        onClick = {
            val device = DeviceInfo.collect(context)
            vm.register(username.trim(), password, email.trim(), code.trim(), nickname.trim(), device)
        },
        enabled = !vm.busy && email.contains("@") && code.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Brand)
    ) {
        Text(if (vm.busy) "注册中…" else "注册", fontSize = 16.sp)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)
    )
}
