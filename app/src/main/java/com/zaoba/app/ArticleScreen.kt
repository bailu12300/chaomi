package com.zaoba.app

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

private val PageBg = Theme.PageBg
private val Brand = Theme.Brand
private val TextPrimary = Theme.TextPrimary
private val TextSecondary = Theme.TextSecondary
private val CardBg = Theme.CardBg

/** 文章列表 ViewModel */
class ArticleViewModel : ViewModel() {
    var articles by mutableStateOf<List<HwApi.Article>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                articles = HwApi.articleList()
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally { loading = false }
        }
    }
}

/** 文章列表页 */
@Composable
fun ArticleScreen(onBack: () -> Unit, vm: ArticleViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    var detailId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding()
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← 返回", color = Brand) }
            Text("资讯", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }

        when {
            detailId != null -> ArticleDetail(detailId!!) { detailId = null }
            vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand)
            }
            vm.error != null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(vm.error ?: "", color = TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.load() }, colors = ButtonDefaults.buttonColors(containerColor = Brand)) { Text("重试") }
            }
            vm.articles.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无文章", color = TextSecondary, fontSize = 14.sp)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(vm.articles, key = { it.id }) { a ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { detailId = a.id },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(a.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 2)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a.category, fontSize = 12.sp, color = Brand)
                                Spacer(Modifier.weight(1f))
                                Text("${a.authorName} · ${a.viewCount} 阅读", fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                    }
                }
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
    var meta by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(id) {
        try {
            val r = HwApi.articleDetail(id)
            val d = r.optJSONObject("data")
            if (d != null) {
                title = d.optString("title")
                content = d.optString("content")
                author = d.optString("authorName")
                meta = "${d.optString("category")} · ${d.optInt("viewCount")} 阅读"
            }
        } catch (e: Exception) {
        } finally { loading = false }
    }

    Column(Modifier.fillMaxSize().background(PageBg).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
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
            Column(
                Modifier.fillMaxSize().verticalScroll(sc).padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 30.sp)
                Spacer(Modifier.height(8.dp))
                Text("$author · $meta", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                Text(content, fontSize = 16.sp, color = TextPrimary, lineHeight = 26.sp)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}
