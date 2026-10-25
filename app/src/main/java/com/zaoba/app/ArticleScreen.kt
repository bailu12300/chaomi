package com.zaoba.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val PageBg = Theme.PageBg
private val Brand = Theme.Brand
private val TextPrimary = Theme.TextPrimary
private val TextSecondary = Theme.TextSecondary
private val CardBg = Theme.CardBg

fun fmtTime(ts: Long): String {
    if (ts <= 0) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}

/** 文章列表 ViewModel */
class ArticleViewModel(app: Application) : AndroidViewModel(app) {
    var articles by mutableStateOf<List<HwApi.Article>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true; error = null
            try { articles = HwApi.articleList() }
            catch (e: Exception) { error = e.message ?: "加载失败" }
            finally { loading = false }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true; error = null
            try { articles = HwApi.articleList() }
            catch (e: Exception) { error = e.message ?: "加载失败" }
            finally { refreshing = false }
        }
    }
}

/** 文章列表页 */
@Composable
fun ArticleScreen(onBack: () -> Unit, vm: ArticleViewModel = viewModel()) {
    var detailId by remember { mutableStateOf<String?>(null) }
    var showPublish by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val user = remember { Session.load(ctx) }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding()
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("资讯", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.refresh() }) {
                Text("刷新", color = Brand, fontSize = 14.sp)
            }
            // 发布 + 导入入口（登录后可见）
            if (user.isLoggedIn) {
                TextButton(onClick = { showImport = true }) {
                    Text("导入", color = Brand, fontSize = 14.sp)
                }
                TextButton(onClick = { showPublish = true }) {
                    Text("＋发布", color = Brand, fontSize = 14.sp)
                }
            }
        }

        when {
            showImport -> ImportWechatDialog(
                token = user.token,
                onDone = { showImport = false; vm.refresh() },
                onCancel = { showImport = false }
            )
            showPublish -> PublishForm(
                token = user.token,
                onDone = { showPublish = false; vm.refresh() },
                onCancel = { showPublish = false }
            )
            detailId != null -> ArticleDetail(detailId!!) { detailId = null }
            vm.loading && vm.articles.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand)
            }
            vm.error != null && vm.articles.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(vm.error ?: "", color = TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.load() }, colors = ButtonDefaults.buttonColors(containerColor = Brand)) { Text("重试") }
            }
            vm.articles.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无文章，点击右上角「发布」写第一篇", color = TextSecondary, fontSize = 14.sp)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(vm.articles, key = { it.id }) { a ->
                    ArticleCard(a) { detailId = a.id }
                }
            }
        }
    }
}

@Composable
private fun ArticleCard(a: HwApi.Article, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.category, fontSize = 11.sp, color = Brand, modifier = Modifier.background(Theme.Brand2.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                if (a.source == "wechat") {
                    Spacer(Modifier.width(6.dp))
                    Text("公众号", fontSize = 11.sp, color = Theme.Down, modifier = Modifier.background(Theme.Down.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(fmtTime(a.createdAt), fontSize = 11.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(10.dp))
            Text(a.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 2, lineHeight = 22.sp)
            // 话题标签
            if (a.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.tags.take(4).forEach { t ->
                        Text("#$t#", fontSize = 12.sp, color = Brand)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.authorName, fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.weight(1f))
                Text("${a.viewCount} 阅读", fontSize = 12.sp, color = TextSecondary)
            }
        }
    }
}

/** 公众号导入对话框 */
@Composable
fun ImportWechatDialog(token: String, onDone: () -> Unit, onCancel: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("导入公众号文章", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("粘贴公众号文章链接，自动解析正文到本站", fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("公众号链接 mp.weixin.qq.com/s/...") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Row {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val r = HwApi.importWechat(token, url.trim())
                            if (r.optInt("code") == 0) {
                                onDone()
                            } else {
                                msg = r.optString("msg", "导入失败")
                            }
                        } catch (e: Exception) { msg = e.message }
                        finally { busy = false }
                    }
                },
                enabled = !busy && url.trim().startsWith("https://mp.weixin.qq.com/"),
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                modifier = Modifier.weight(1f)
            ) { Text(if (busy) "解析中…" else "导入", fontSize = 16.sp) }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
        }
        msg?.let { Text(it, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 12.dp)) }
    }
}

/**
 * 文章编辑器（新建 / 编辑共用）。
 *
 * @param editId 非空 = 编辑已有文章；null = 新建
 *
 * 相较早期版本的改进：
 *   · 工具栏插入**光标位置**（早期是追加到末尾，改一句话要手动挪光标）
 *   · 支持编辑已有文章（作者本人/管理员）
 *   · 预览模式：边写边看 Markdown 渲染效果
 *   · 字数统计 + 未保存提示
 */
@Composable
fun PublishForm(
    token: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    editId: String? = null,
    initialTitle: String = "",
    initialContent: String = "",
    initialCategory: String = ""
) {
    val isEdit = !editId.isNullOrBlank()
    var title by remember { mutableStateOf(initialTitle) }
    var category by remember { mutableStateOf(initialCategory.ifBlank { "资讯" }) }
    // 用 TextFieldValue 才能拿到光标位置，实现「插入到光标处」
    var body by remember { mutableStateOf(TextFieldValue(initialContent)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showEmoji by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    /** 在光标处插入文本；若当前有选中内容则替换 */
    fun insert(text: String) {
        val sel = body.selection
        val newText = body.text.substring(0, sel.start) + text + body.text.substring(sel.end)
        body = TextFieldValue(
            text = newText,
            selection = TextRange(sel.start + text.length)
        )
    }

    /** 用整段包裹选中内容（如加粗、行内代码）；无选中则插入占位 */
    fun wrap(prefix: String, suffix: String, placeholder: String) {
        val sel = body.selection
        val selected = body.text.substring(sel.start, sel.end)
        val inner = if (selected.isBlank()) placeholder else selected
        val newText = body.text.substring(0, sel.start) + prefix + inner + suffix + body.text.substring(sel.end)
        body = TextFieldValue(
            text = newText,
            selection = TextRange(sel.start + prefix.length, sel.start + prefix.length + inner.length)
        )
    }

    // 图片选择
    val imgPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val bytes = ctx.contentResolver.openInputStream(it)?.readBytes()
                    if (bytes != null) {
                        val ext = it.lastPathSegment?.substringAfterLast('.', "jpg") ?: "jpg"
                        val path = "article/${System.currentTimeMillis()}.$ext"
                        msg = "图片上传中…"
                        val r = HwApi.uploadImage(token, bytes, path)
                        if (r.optInt("code") == 0) {
                            insert("\n![图片](${r.optString("url")})\n")
                            msg = null
                        } else msg = r.optString("msg", "上传失败")
                    }
                } catch (e: Exception) { msg = e.message }
            }
        }
    }

    // 视频选择（≤1分钟 ≤250MB）
    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val bytes = ctx.contentResolver.openInputStream(it)?.readBytes()
                    if (bytes != null) {
                        if (bytes.size > 250 * 1024 * 1024) {
                            msg = "视频不能超过 250MB"
                            return@launch
                        }
                        val ext = it.lastPathSegment?.substringAfterLast('.', "mp4") ?: "mp4"
                        val name = "video_${System.currentTimeMillis()}.$ext"
                        msg = "视频上传中…"
                        val r = HwApi.uploadVideo(token, bytes, name)
                        if (r.optInt("code") == 0) {
                            insert("\n[视频](${r.optString("url")})\n")
                            msg = null
                        } else msg = r.optString("msg", "上传失败")
                    }
                } catch (e: Exception) { msg = e.message }
            }
        }
    }

    // 未保存提示
    val dirty = title != initialTitle || body.text != initialContent || category != (initialCategory.ifBlank { "资讯" })

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        // 顶部：模式标题 + 预览切换
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isEdit) "编辑文章" else "发布文章",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { preview = !preview }) {
                Text(if (preview) "继续编辑" else "预览", color = Brand, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (!preview) {
            OutlinedTextField(
                value = title, onValueChange = { if (it.length <= 100) title = it },
                label = { Text("标题") }, singleLine = true,
                supportingText = { Text("${title.length}/100", fontSize = 11.sp, color = TextSecondary) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            // 分类快捷选择
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("资讯", "显卡", "CPU", "笔记本", "固态硬盘", "内存条", "评测").forEach { c ->
                    val on = category == c
                    Surface(
                        onClick = { category = c },
                        color = if (on) Brand else CardBg,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Brand else Theme.Line)
                    ) {
                        Text(
                            c, fontSize = 12.sp,
                            color = if (on) Color.White else TextPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // 富文本工具栏
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ToolBtn("加粗") { wrap("**", "**", "粗体") }
                ToolBtn("行内码") { wrap("`", "`", "code") }
                ToolBtn("代码块") { insert("\n```\n\n```\n") }
                ToolBtn("链接") { insert("[标题](https://)") }
                ToolBtn("话题") { insert(" #话题# ") }
                ToolBtn("😀") { showEmoji = !showEmoji }
                ToolBtn("图片") { imgPicker.launch("image/*") }
                ToolBtn("视频") { videoPicker.launch("video/*") }
                ToolBtn("引用") { insert("\n> ") }
                ToolBtn("列表") { insert("\n- ") }
                ToolBtn("标题") { insert("\n## ") }
                ToolBtn("分割线") { insert("\n\n---\n\n") }
            }

            // 表情选择器
            if (showEmoji) {
                Spacer(Modifier.height(8.dp))
                EmojiPanel { emoji -> insert(emoji) }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = body, onValueChange = { body = it },
                label = { Text("正文（支持 Markdown）") },
                supportingText = {
                    Text("${body.text.length} 字", fontSize = 11.sp, color = TextSecondary)
                },
                modifier = Modifier.fillMaxWidth().height(300.dp)
            )
        } else {
            // 预览：直接复用详情页的渲染器
            Surface(
                color = CardBg, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    if (title.isNotBlank()) {
                        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (body.text.isBlank()) {
                        Text("（还没有内容）", fontSize = 14.sp, color = TextSecondary)
                    } else {
                        MarkdownContent(body.text)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row {
            Button(
                onClick = {
                    busy = true; msg = null
                    scope.launch {
                        try {
                            val r = if (isEdit) {
                                HwApi.articleUpdate(token, editId!!, title.trim(), body.text.trim(), category.trim())
                            } else {
                                HwApi.articleCreate(token, title.trim(), body.text.trim(), category.trim())
                            }
                            if (r.optInt("code") == 0) { onDone() }
                            else { msg = r.optString("msg", if (isEdit) "保存失败" else "发布失败") }
                        } catch (e: Exception) { msg = e.message }
                        finally { busy = false }
                    }
                },
                enabled = !busy && title.isNotBlank() && body.text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    when {
                        busy && isEdit -> "保存中…"
                        busy -> "发布中…"
                        isEdit -> "保存修改"
                        else -> "发布"
                    },
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
        }
        if (dirty) {
            Spacer(Modifier.height(8.dp))
            Text("有未保存的修改", fontSize = 11.sp, color = Theme.Up)
        }
        msg?.let { Text(it, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 12.dp)) }
    }
}

/** 工具栏按钮 */
@Composable
private fun ToolBtn(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = CardBg,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Theme.Line)
    ) {
        Text(label, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** 表情面板（常用 emoji） */
@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    val emojis = listOf("😀","😂","🤣","😊","😍","😘","😎","🤔","😅","😭","😡","👍","👎","🙏","💪","👏","🎉","❤️","🔥","💯","⭐","✨","🎁","🌹","🐂","🚀","💰","📈","📉","🛠️","💻","🖥️","🎮")
    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            emojis.forEach { e ->
                Text(e, fontSize = 22.sp, modifier = Modifier.clickable { onPick(e) })
            }
        }
    }
}

/** 文章详情页 */
@Composable
fun ArticleDetail(id: String, onBack: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var viewCount by remember { mutableStateOf(0) }
    var createdAt by remember { mutableStateOf(0L) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var sourceUrl by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var comments by remember { mutableStateOf<List<HwApi.Comment>>(emptyList()) }
    var likeTotal by remember { mutableStateOf(0) }
    var liked by remember { mutableStateOf(false) }
    var commentText by remember { mutableStateOf("") }
    // 编辑/删除
    var canEdit by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val user = remember { Session.load(ctx) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(id, reloadKey) {
        loading = true
        try {
            // 带 token 才能拿到 canEdit（作者本人/管理员）
            val r = HwApi.articleDetail(id, user.token.takeIf { user.isLoggedIn })
            val d = r.optJSONObject("data")
            if (d != null) {
                title = d.optString("title")
                content = d.optString("content")
                author = d.optString("authorName")
                category = d.optString("category")
                viewCount = d.optInt("viewCount")
                createdAt = d.optLong("createdAt")
                sourceUrl = d.optString("sourceUrl")
                canEdit = d.optBoolean("canEdit")
                val ta = d.optJSONArray("tags")
                tags = if (ta == null) emptyList() else (0 until ta.length()).map { ta.optString(it) }
            }
        } catch (e: Exception) {
        } finally { loading = false }
        // 评论 + 点赞
        try { comments = HwApi.articleComments(id) } catch (e: Exception) {}
        try {
            val li = HwApi.likeInfo(id)
            likeTotal = li.optJSONObject("data")?.optInt("total") ?: 0
        } catch (e: Exception) {}
    }

    // 编辑模式：直接复用编辑器（编辑成功或取消都回到详情）
    if (editing) {
        Column(Modifier.fillMaxSize().background(PageBg).statusBarsPadding()) {
            PublishForm(
                token = user.token,
                onDone = { editing = false; reloadKey++ },
                onCancel = { editing = false },
                editId = id,
                initialTitle = title,
                initialContent = content,
                initialCategory = category
            )
        }
        return
    }

    // 删除确认
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除文章") },
            text = { Text("确定删除《$title》？删除后其他人将无法查看。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            val r = HwApi.articleDelete(user.token, id)
                            if (r.optInt("code") == 0) onBack()
                        } catch (e: Exception) {}
                    }
                }) { Text("删除", color = Theme.Up) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(PageBg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← 返回", color = Brand) }
            Spacer(Modifier.weight(1f))
            if (sourceUrl.isNotBlank()) {
                TextButton(onClick = { /* 打开原文浏览器 */ }) { Text("原文", color = Brand) }
            }
            // 作者本人/管理员：编辑 + 删除
            if (canEdit) {
                TextButton(onClick = { editing = true }) { Text("编辑", color = Brand) }
                TextButton(onClick = { confirmDelete = true }) { Text("删除", color = Theme.Up) }
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                item {
                    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 32.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(category, fontSize = 12.sp, color = Brand, modifier = Modifier.background(Theme.Brand2.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(author, fontSize = 13.sp, color = TextSecondary)
                        Spacer(Modifier.weight(1f))
                        Text(fmtTime(createdAt), fontSize = 12.sp, color = TextSecondary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("$viewCount 阅读 · $likeTotal 赞", fontSize = 12.sp, color = TextSecondary)
                    // 话题标签
                    if (tags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { t -> Text("#$t#", fontSize = 13.sp, color = Brand) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Theme.Line)
                    Spacer(Modifier.height(16.dp))
                    // 正文（HTML 用 WebView 渲染，支持公众号图片）
                    ArticleContent(content)
                }

                // 点赞按钮
                item {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!user.isLoggedIn) return@OutlinedButton
                                scope.launch {
                                    try {
                                        val r = HwApi.toggleLike(user.token, id)
                                        val d = r.optJSONObject("data")
                                        if (d != null) {
                                            liked = d.optBoolean("liked")
                                            likeTotal = d.optInt("total")
                                        }
                                    } catch (e: Exception) {}
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (liked) Theme.Up else Brand
                            )
                        ) {
                            Text(if (liked) "❤ 已赞 $likeTotal" else "♡ 点赞 $likeTotal")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // 评论标题
                item {
                    Text("评论 ${comments.size}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                }

                // 评论列表
                items(comments, key = { it.id }) { c ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(Brand), contentAlignment = Alignment.Center) {
                                Text((c.authorName.ifBlank { "匿" }).take(1), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(c.authorName.ifBlank { "匿名" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text(fmtTime(c.createdAt), fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(c.content, fontSize = 15.sp, color = TextPrimary, lineHeight = 22.sp)
                        HorizontalDivider(color = Theme.Line, modifier = Modifier.padding(top = 8.dp))
                    }
                }

                // 评论输入
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = commentText, onValueChange = { commentText = it },
                            placeholder = { Text(if (user.isLoggedIn) "写评论…" else "登录后可评论") },
                            modifier = Modifier.weight(1f),
                            enabled = user.isLoggedIn
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (!user.isLoggedIn || commentText.isBlank()) return@Button
                                scope.launch {
                                    try {
                                        val r = HwApi.addComment(user.token, id, commentText.trim())
                                        if (r.optInt("code") == 0) {
                                            commentText = ""
                                            comments = HwApi.articleComments(id)
                                        }
                                    } catch (e: Exception) {}
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Brand)
                        ) { Text("发送") }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

/** 文章正文（HTML 用 WebView 渲染） */
@Composable
private fun ArticleContent(content: String) {
    val isHtml = content.contains("<") && content.contains(">")
    if (isHtml) {
        HtmlArticle(content)
    } else {
        MarkdownContent(content)
    }
}

/**
 * 公众号 HTML 正文渲染。
 * 关键修复：
 *  1. 公众号图片用 data-src 懒加载 → 转成 src 才能显示
 *  2. WebView 高度动态测量（之前用 WRAP_CONTENT.dp = -2dp 导致高度为 0，内容"什么都没显示"）
 *  3. baseUrl 设为微信域名，绕过 mmbiz.qpic.cn 图片防盗链
 */
@Composable
private fun HtmlArticle(html: String) {
    var heightPx by remember { mutableStateOf(800) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.blockNetworkImage = false
                // 禁用 WebView 自身滚动，交给外层 LazyColumn
                isVerticalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                        // 多次测量：图片加载慢时首次不准，300ms / 1200ms / 2500ms 取最大
                        listOf(300L, 1200L, 2500L).forEach { delayMs ->
                            view.postDelayed({
                                val h = view.contentHeight
                                if (h > heightPx) heightPx = h
                            }, delayMs)
                        }
                    }
                }
                // data-src 懒加载图片 → src
                val fixed = html.replace("data-src=", "src=")
                val page = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<style>img{max-width:100%!important;height:auto!important;display:block;margin:8px 0}" +
                    "body{font-size:16px;line-height:1.8;color:#2B2118;margin:0;padding:0;word-wrap:break-word}" +
                    "p{margin:0 0 12px}</style></head><body>" + fixed + "</body></html>"
                // baseUrl 用微信域名，带正确 Referer 绕过图片防盗链
                loadDataWithBaseURL("https://mp.weixin.qq.com", page, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth().height(with(density) { heightPx.toDp() })
    )
}

/** 简易 Markdown 渲染（代码块/链接/图片/粗体/引用/列表/话题） */
@Composable
private fun MarkdownContent(content: String) {
    val lines = content.split("\n")
    var inCode = false
    var i = 0
    Column {
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trim().startsWith("```") -> {
                    if (!inCode) {
                        inCode = true
                        // 代码块开始
                    } else {
                        inCode = false
                    }
                    i++
                }
                inCode -> {
                    // 代码块内容：等宽字体 + 背景
                    Text(
                        line,
                        fontSize = 13.sp, color = TextPrimary,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().background(Theme.Line.copy(alpha = 0.3f), RoundedCornerShape(4.dp)).padding(8.dp)
                    )
                    i++
                }
                line.trim().startsWith("> ") -> {
                    // 引用
                    Text(line.trim().removePrefix("> "), fontSize = 15.sp, color = TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(start = 10.dp).background(Theme.Line.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).padding(8.dp))
                    Spacer(Modifier.height(8.dp))
                    i++
                }
                line.trim().startsWith("- ") -> {
                    // 列表
                    Text("• " + line.trim().removePrefix("- "), fontSize = 15.sp, color = TextPrimary, lineHeight = 24.sp)
                    i++
                }
                line.trim().startsWith("![") && line.contains("](") -> {
                    // 图片 ![alt](url)
                    val url = line.substringAfter("](", "").substringBefore(")")
                    if (url.isNotBlank()) {
                        AsyncImage(url)
                    }
                    i++
                }
                line.trim().startsWith("[视频](") && line.contains(")") -> {
                    // 视频 [视频](url)
                    val url = line.substringAfter("](", "").substringBefore(")")
                    if (url.isNotBlank()) {
                        VideoPlayer(url)
                    }
                    i++
                }
                line.isNotBlank() -> {
                    RichText(line)
                    Spacer(Modifier.height(10.dp))
                    i++
                }
                else -> i++
            }
        }
    }
}

/** 富文本行：话题#标签#、粗体、链接 */
@Composable
private fun RichText(line: String) {
    // 话题 #标签# 高亮
    val topicRegex = Regex("#([^#\\s]{1,30})#")
    val linkRegex = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    // 简单处理：先渲染话题高亮，粗体，链接用 brand 色
    // 这里用 buildAnnotatedString 混合样式
    val annotated = buildAnnotatedString {
        var idx = 0
        val combined = Regex("(#([^#\\s]{1,30})#)|(\\[([^\\]]+)\\]\\(([^)]+)\\))|(\\*\\*([^*]+)\\*\\*)")
        for (m in combined.findAll(line)) {
            append(line.substring(idx, m.range.first))
            when {
                m.groups[2] != null -> withStyle(androidx.compose.ui.text.SpanStyle(color = Brand, fontWeight = FontWeight.SemiBold)) { append(m.value) }
                m.groups[4] != null -> withStyle(androidx.compose.ui.text.SpanStyle(color = Brand, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) { append(m.groups[4]!!.value) }
                m.groups[6] != null -> withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groups[6]!!.value) }
            }
            idx = m.range.last + 1
        }
        append(line.substring(idx))
    }
    Text(annotated, fontSize = 16.sp, color = TextPrimary, lineHeight = 27.sp)
}

/** 异步加载图片 */
@Composable
private fun AsyncImage(url: String) {
    // 用 WebView 或 Coil；这里简化为占位 + 链接（不引 Coil 依赖）
    // 实际用 AndroidView 加载单图
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = false
                loadDataWithBaseURL(null, "<html><body style='margin:0'><img src='$url' style='max-width:100%;height:auto'/></body></html>", "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth().height(200.dp)
    )
    Spacer(Modifier.height(10.dp))
}

/** 视频播放（成熟方案：Android 原生 VideoView） */
@Composable
private fun VideoPlayer(url: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { android.widget.MediaController(ctx) }
    Column(Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp))) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c ->
                android.widget.VideoView(c).apply {
                    setMediaController(controller)
                    setVideoPath(url)
                    setOnPreparedListener { mp -> mp.isLooping = false }
                    start()
                }
            },
            modifier = Modifier.fillMaxWidth().height(220.dp)
        )
    }
    Spacer(Modifier.height(10.dp))
}
