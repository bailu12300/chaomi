package com.zaoba.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
private val Brand2 = Theme.Brand2
private val TextPrimary = Theme.TextPrimary
private val TextSecondary = Theme.TextSecondary
private val CardBg = Theme.CardBg

/** 账号体系 ViewModel（登录态持久化） */
class AuthViewModel(app: Application) : AndroidViewModel(app) {
    var busy by mutableStateOf(false)
        private set
    var msg by mutableStateOf<String?>(null)
        private set
    fun showMsg(s: String?) { msg = s }
    // 登录态：从 Session 持久化恢复
    var user by mutableStateOf(Session.load(getApplication()))
        private set
    val loggedIn: Boolean get() = user.isLoggedIn
    val token: String get() = user.token

    // 完整资料（头像/签名/背景）
    var profile by mutableStateOf<JSONObject?>(null)
        private set

    fun loadProfile() {
        if (!loggedIn) return
        viewModelScope.launch {
            try {
                val r = HwApi.getProfile(token)
                if (r.optInt("code") == 0) profile = r.optJSONObject("data")
            } catch (e: Exception) {}
        }
    }

    fun updateProfile(nickname: String? = null, avatar: String? = null, signature: String? = null, background: String? = null, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            busy = true; msg = null
            try {
                val r = HwApi.updateProfile(token, nickname, avatar, signature, background)
                if (r.optInt("code") == 0) {
                    msg = "已保存"
                    loadProfile()
                    onDone?.invoke()
                } else {
                    msg = r.optString("msg", "保存失败")
                }
            } catch (e: Exception) { msg = e.message }
            finally { busy = false }
        }
    }

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
        LaunchedEffect(Unit) { vm.loadProfile() }
        ProfilePage(vm)
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
        Text("我的", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(
            if (mode == "login") "登录账号" else "注册账号",
            fontSize = 15.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp)
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

/** 我的页面：头像 + 昵称 + 签名 + 背景 */
@Composable
private fun ProfilePage(vm: AuthViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val avatarUrl = remember { vm.profile?.optString("avatar")?.ifBlank { null } ?: "" }
    val signature = remember { vm.profile?.optString("signature") ?: "" }
    val nickname = remember { vm.profile?.optString("nickname")?.ifBlank { vm.user.nickname.ifBlank { vm.user.username } } ?: vm.user.nickname.ifBlank { vm.user.username } }

    // 头像选图
    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val bytes = ctx.contentResolver.openInputStream(it)?.readBytes()
                    if (bytes != null) {
                        val r = HwApi.uploadImage(vm.token, bytes, "avatar/${vm.user.username}.jpg")
                        if (r.optInt("code") == 0) {
                            vm.updateProfile(avatar = r.optString("url"))
                        }
                    }
                } catch (e: Exception) { vm.showMsg(e.message) }
            }
        }
    }

    // 背景选图
    val backgroundPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val bytes = ctx.contentResolver.openInputStream(it)?.readBytes()
                    if (bytes != null) {
                        val r = HwApi.uploadImage(vm.token, bytes, "background/${vm.user.username}.jpg")
                        if (r.optInt("code") == 0) {
                            vm.updateProfile(background = r.optString("url"))
                        }
                    }
                } catch (e: Exception) { vm.showMsg(e.message) }
            }
        }
    }

    var editingSignature by remember { mutableStateOf(false) }
    var sigText by remember { mutableStateOf(signature) }
    var editingNickname by remember { mutableStateOf(false) }
    var nickText by remember { mutableStateOf(nickname) }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text("我的", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(Modifier.height(20.dp))

        // 个性背景（点击上传）
        val bgUrl = vm.profile?.optString("background")?.ifBlank { null } ?: ""
        Box(
            Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp))
                .background(if (bgUrl.isBlank()) Brand2 else Brand2)
                .clickable { backgroundPicker.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (bgUrl.isNotBlank()) {
                // 用 WebView 显示背景图
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { c ->
                        android.webkit.WebView(c).apply {
                            settings.javaScriptEnabled = false
                            loadDataWithBaseURL(null, "<html><body style='margin:0'><img src='$bgUrl' style='width:100%;height:120px;object-fit:cover'/></body></html>", "text/html", "utf-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("点击设置个性背景", fontSize = 13.sp, color = TextSecondary)
            }
        }

        Spacer(Modifier.height(16.dp))

        // 头像 + 昵称卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // 头像（点击上传）
                Box(Modifier.size(80.dp).clip(RoundedCornerShape(40.dp)).background(Brand).clickable { avatarPicker.launch("image/*") }, contentAlignment = Alignment.Center) {
                    Text(nickname.take(1), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
                Text("点击更换头像", fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                // 昵称
                if (editingNickname) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = nickText, onValueChange = { nickText = it }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.updateProfile(nickname = nickText) { editingNickname = false } }) { Text("保存") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nickname, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        TextButton(onClick = { nickText = nickname; editingNickname = true }) { Text("改", color = Brand, fontSize = 12.sp) }
                    }
                }
                if (vm.user.username.isNotBlank()) {
                    Text("用户名：${vm.user.username}", fontSize = 13.sp, color = TextSecondary)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 个性签名
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("个性签名", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                if (editingSignature) {
                    OutlinedTextField(value = sigText, onValueChange = { sigText = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("写一句个性签名…") })
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = { vm.updateProfile(signature = sigText) { editingSignature = false } }, colors = ButtonDefaults.buttonColors(containerColor = Brand), modifier = Modifier.weight(1f)) { Text("保存") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { editingSignature = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                    }
                } else {
                    Text(signature.ifBlank { "还没有签名，点击添加" }, fontSize = 14.sp, color = if (signature.isBlank()) TextSecondary else TextPrimary, modifier = Modifier.clickable { sigText = signature; editingSignature = true })
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { vm.logout() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Theme.Up)
        ) { Text("退出登录", fontSize = 15.sp) }
        vm.msg?.let { Text(it, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 12.dp)) }
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
