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
            // 发布入口（登录后可见）
            if (user.isLoggedIn) {
                TextButton(onClick = { showPublish = true }) {
                    Text("＋发布", color = Brand, fontSize = 14.sp)
                }
            }
        }

        when {
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
                Spacer(Modifier.width(8.dp))
                Text(fmtTime(a.createdAt), fontSize = 11.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(10.dp))
            Text(a.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 2, lineHeight = 22.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.authorName, fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.weight(1f))
                Text("${a.viewCount} 阅读", fontSize = 12.sp, color = TextSecondary)
            }
        }
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
    var loading by remember { mutableStateOf(true) }

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
            }
        } catch (e: Exception) {
        } finally { loading = false }
    }

    Column(Modifier.fillMaxSize().background(PageBg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← 返回", color = Brand) }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand)
            }
        } else {
            val sc = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(sc).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 32.sp)
                Spacer(Modifier.height(12.dp))
                // 元信息
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(category, fontSize = 12.sp, color = Brand, modifier = Modifier.background(Theme.Brand2.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(author, fontSize = 13.sp, color = TextSecondary)
                    Spacer(Modifier.weight(1f))
                    Text(fmtTime(createdAt), fontSize = 12.sp, color = TextSecondary)
                }
                Spacer(Modifier.height(4.dp))
                Text("$viewCount 阅读", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                // 分隔线
                HorizontalDivider(color = Theme.Line)
                Spacer(Modifier.height(16.dp))
                // 正文（按空行分段，每段缩进）
                content.split("\n").forEach { para ->
                    if (para.isNotBlank()) {
                        Text(para.trim(), fontSize = 16.sp, color = TextPrimary, lineHeight = 27.sp)
                        Spacer(Modifier.height(12.dp))
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}
