package com.zaoba.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
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

/** 发布表单 */
@Composable
fun PublishForm(token: String, onDone: () -> Unit, onCancel: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("资讯") }
    var content by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("标题") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = category, onValueChange = { category = it },
            label = { Text("分类") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = content, onValueChange = { content = it },
            label = { Text("正文内容") },
            modifier = Modifier.fillMaxWidth().height(260.dp)
        )
        Spacer(Modifier.height(16.dp))
        Row {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val r = HwApi.articleCreate(token, title.trim(), content.trim(), category.trim())
                            if (r.optInt("code") == 0) { onDone() }
                            else { msg = r.optString("msg", "发布失败") }
                        } catch (e: Exception) { msg = e.message }
                        finally { busy = false }
                    }
                },
                enabled = !busy && title.isNotBlank() && content.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                modifier = Modifier.weight(1f)
            ) { Text(if (busy) "发布中…" else "发布", fontSize = 16.sp) }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
        }
        msg?.let { Text(it, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 12.dp)) }
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val user = remember { Session.load(ctx) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(id) {
        try {
            val r = HwApi.articleDetail(id)
            val d = r.optJSONObject("data")
            if (d != null) {
                title = d.optString("title")
                content = d.optString("content")
                author = d.optString("authorName")
                category = d.optString("category")
                viewCount = d.optInt("viewCount")
                createdAt = d.optLong("createdAt")
                sourceUrl = d.optString("sourceUrl")
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
        // 公众号 HTML：用 WebView 渲染
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                        "<style>img{max-width:100%;height:auto}body{font-size:16px;line-height:1.7;color:#2B2118;margin:0;padding:0}</style></head><body>" +
                        content + "</body></html>"
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.fillMaxWidth().height(android.view.ViewGroup.LayoutParams.WRAP_CONTENT.dp)
        )
    } else {
        content.split("\n").forEach { para ->
            if (para.isNotBlank()) {
                Text(para.trim(), fontSize = 16.sp, color = TextPrimary, lineHeight = 27.sp)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
