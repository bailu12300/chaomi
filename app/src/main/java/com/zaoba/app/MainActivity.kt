package com.zaoba.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.text.DecimalFormat

private val PageBg = Theme.PageBg
private val Brand = Theme.Brand
private val Brand2 = Theme.Brand2
private val TextPrimary = Theme.TextPrimary
private val TextSecondary = Theme.TextSecondary
private val CardBg = Theme.CardBg
private val Line = Theme.Line

private val priceFmt = DecimalFormat("#,###.##")

/** 分类 */
private val CATS = listOf(
    "GPU" to "显卡", "CPU" to "处理器", "SSD" to "固态硬盘", "RAM" to "内存条", "NB" to "笔记本"
)

class MainViewModel : ViewModel() {
    var models by mutableStateOf<List<HwModel>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var crawlRunning by mutableStateOf(false); private set
    var crawlDone by mutableStateOf(0); private set
    var crawlTotal by mutableStateOf(0); private set
    var crawlCurrent by mutableStateOf(""); private set
    var lastDoneAt by mutableStateOf(0L); private set

    init { load(); startPolling() }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                loadStatus()
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            loading = true; error = null
            try { models = HwApi.fetchModels() }
            catch (e: Exception) { error = e.message ?: "加载失败" }
            finally { loading = false }
        }
    }

    fun loadStatus() {
        viewModelScope.launch {
            try {
                val s = HwApi.fetchStatus()
                crawlRunning = s.running
                crawlDone = s.done
                crawlTotal = s.total
                crawlCurrent = s.current
                lastDoneAt = s.lastDoneAt
            } catch (e: Exception) {}
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrawlScheduler.schedule(applicationContext)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Brand, background = PageBg, surface = CardBg
            )) {
                AppRoot()
            }
        }
    }
}

/** 顶层：底部导航 + 三个 tab */
@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(0) }  // 0 行情 1 资讯 2 我的

    Scaffold(
        containerColor = PageBg,
        bottomBar = {
            NavigationBar(containerColor = CardBg) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("📈", fontSize = 18.sp) },
                    label = { Text("行情") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Text("📰", fontSize = 18.sp) },
                    label = { Text("资讯") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Text("👤", fontSize = 18.sp) },
                    label = { Text("我的") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HwScreen()
                1 -> ArticleScreen(onBack = {})
                2 -> AuthScreen()
            }
        }
    }
}

@Composable
fun HwScreen(vm: MainViewModel = viewModel()) {
    var selectedCat by remember { mutableStateOf<String?>(null) }  // null = 全部

    Column(Modifier.fillMaxSize().background(PageBg).statusBarsPadding()) {
        // 顶部
        Header(vm)

        // 分类筛选
        CatFilter(selectedCat) { selectedCat = it }

        when {
            vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand)
            }
            vm.error != null -> ErrorView(vm.error ?: "") { vm.load() }
            else -> {
                val priced = vm.models.filter { it.price != null }
                val filtered = if (selectedCat == null) priced else priced.filter { it.cat == selectedCat }
                HwList(filtered)
            }
        }
    }
}

@Composable
private fun Header(vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Logo + 名称
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Brand),
                contentAlignment = Alignment.Center
            ) {
                Text("早", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("早八", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("硬件行情", fontSize = 12.sp, color = TextSecondary)
            }
            Spacer(Modifier.weight(1f))
            // LIVE 徽章
            if (vm.crawlRunning) {
                Surface(
                    color = Theme.Up.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp),
                    contentColor = Theme.Up
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Theme.Up))
                        Spacer(Modifier.width(4.dp))
                        Text("采集中", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Text(
                    if (vm.lastDoneAt > 0) "更新于 ${HwApi.fmtTime(vm.lastDoneAt)}" else "待采集",
                    fontSize = 12.sp, color = TextSecondary
                )
            }
        }

        // 采集进度（始终显示，采完写满，下轮重置）
        if (vm.crawlTotal > 0) {
            val finished = !vm.crawlRunning && vm.crawlDone >= vm.crawlTotal
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (finished) 1f else vm.crawlDone.toFloat() / vm.crawlTotal },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = if (finished) Theme.Down else Brand,
                trackColor = Brand.copy(alpha = 0.12f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (finished) "本轮采集完成 ${vm.crawlDone}/${vm.crawlTotal}"
                else if (vm.crawlRunning) "正在采集 ${vm.crawlDone}/${vm.crawlTotal} · ${vm.crawlCurrent}"
                else "已采集 ${vm.crawlDone}/${vm.crawlTotal}（暂停，等待继续）",
                fontSize = 11.sp, color = TextSecondary
            )
        }
    }
}

@Composable
private fun CatFilter(selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CatChip("全部", selected == null) { onSelect(null) }
        CATS.forEach { (cat, label) ->
            CatChip(label, selected == cat) { onSelect(cat) }
        }
    }
}

@Composable
private fun CatChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) Brand else CardBg,
        contentColor = if (active) Color.White else TextSecondary,
        shape = RoundedCornerShape(16.dp),
        border = if (active) null else androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ErrorView(msg: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("加载失败：$msg", color = TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Brand)) { Text("重试") }
    }
}

@Composable
private fun HwList(models: List<HwModel>) {
    if (models.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无数据，等待首次采集", color = TextSecondary, fontSize = 14.sp)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(models.sortedByDescending { it.price }, key = { it.key }) { m ->
            HwCard(m)
        }
    }
}

@Composable
private fun HwCard(m: HwModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 型号 + 规格
            Column(Modifier.weight(1.2f)) {
                Text(
                    m.model,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val spec = listOf(m.brand, m.spec).filter { it.isNotBlank() }.joinToString(" · ")
                if (spec.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(spec, fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // 迷你走势图（一周 14 点，居中）
            if (m.trends.size >= 2) {
                Sparkline(m.trends, m.trend, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.width(12.dp))
            // 价格 + 涨跌（右对齐，留出价格字段位置）
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (m.trend) {
                        "up" -> Text("▲", fontSize = 12.sp, color = Theme.Up)
                        "down" -> Text("▼", fontSize = 12.sp, color = Theme.Down)
                    }
                    if (m.trend != "flat") Spacer(Modifier.width(3.dp))
                    Text(
                        "¥${priceFmt.format(m.price)}",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = when (m.trend) {
                            "up" -> Theme.Up
                            "down" -> Theme.Down
                            else -> Brand
                        }
                    )
                }
                // 上一轮价格
                m.prevPrice?.let { pp ->
                    Spacer(Modifier.height(2.dp))
                    Text("上轮 ¥${priceFmt.format(pp)}", fontSize = 10.sp, color = TextSecondary)
                }
                m.platform?.let {
                    Spacer(Modifier.height(2.dp))
                    Surface(color = Theme.Brand2.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
                        Text(it, fontSize = 11.sp, color = Brand, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
            }
        }
    }
}

/** 迷你走势图（sparkline），Canvas 绘制折线 */
@Composable
private fun Sparkline(points: List<Double>, trend: String, modifier: Modifier = Modifier) {
    val lineColor = when (trend) {
        "up" -> Theme.Up
        "down" -> Theme.Down
        else -> Brand
    }
    Canvas(modifier = modifier.height(36.dp)) {
        if (points.size < 2) return@Canvas
        val min = points.minOrNull() ?: 0.0
        val max = points.maxOrNull() ?: 1.0
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - min) / range).toFloat() * (size.height - 4) - 2
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2f))
        // 最后一点画个小圆点
        val lx = (points.size - 1) * stepX
        val ly = size.height - ((points.last() - min) / range).toFloat() * (size.height - 4) - 2
        drawCircle(color = lineColor, radius = 3f, center = Offset(lx, ly))
    }
}
