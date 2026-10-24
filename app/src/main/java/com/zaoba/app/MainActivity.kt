package com.zaoba.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

// App 主题色（沿用早八 App 配色）
private val PageBg = Color(0xFFF4F7FB)
private val Brand = Color(0xFF2F6FC0)
private val TextPrimary = Color(0xFF1B2733)
private val TextSecondary = Color(0xFF8A98A8)
private val CardBg = Color(0xFFFFFFFF)

/** 分类展示顺序 */
private val CAT_ORDER = listOf("GPU" to "显卡", "CPU" to "处理器", "SSD" to "固态硬盘", "RAM" to "内存条", "NB" to "笔记本")

class MainViewModel : ViewModel() {
    var models by mutableStateOf<List<HwModel>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                models = HwApi.fetchModels()
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Brand,
                background = PageBg
            )) {
                HwScreen()
            }
        }
    }
}

@Composable
fun HwScreen(vm: MainViewModel = viewModel()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .statusBarsPadding()
    ) {
        // 顶部标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("硬件行情", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            Text("全网实时价", fontSize = 12.sp, color = TextSecondary)
        }

        when {
            vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand)
            }
            vm.error != null -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("加载失败：${vm.error}", color = TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.load() }, colors = ButtonDefaults.buttonColors(containerColor = Brand)) {
                    Text("重试")
                }
            }
            else -> HwList(vm.models)
        }
    }
}

@Composable
fun HwList(models: List<HwModel>) {
    val priced = models.filter { it.price != null }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CAT_ORDER.forEach { (cat, label) ->
            val group = priced.filter { it.cat == cat }
            if (group.isNotEmpty()) {
                item(key = "cat_$cat") {
                    Text(
                        "$label · ${group.size} 款有价",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                items(group.sortedByDescending { it.price }, key = { it.key }) { m ->
                    HwRow(m)
                }
            }
        }
    }
}

@Composable
fun HwRow(m: HwModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(m.model, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                val spec = listOf(m.brand, m.spec).filter { it.isNotBlank() }.joinToString(" · ")
                if (spec.isNotBlank()) {
                    Text(spec, fontSize = 12.sp, color = TextSecondary)
                }
            }
            Text(
                "¥${m.price}",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Brand
            )
            m.platform?.let {
                Text("  $it", fontSize = 12.sp, color = TextSecondary)
            }
        }
    }
}
