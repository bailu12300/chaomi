package com.zaoba.app

import org.json.JSONObject

/**
 * 硬件型号 + 价格数据模型。
 * 字段与后端 /api/hw/models 返回对齐。
 *
 * ★ 本次增量只**新增字段**，且全部带默认值：
 *   老后端（不下发这些字段）→ opt* 取默认值 → 行为与升级前完全一致，不会崩。
 */
data class HwModel(
    val key: String,
    val cat: String,
    val catLabel: String,
    val brand: String,
    val model: String,
    val spec: String,
    val keyword: String,
    val price: Double?,
    val platform: String?,
    val trend: String = "flat",
    val prevPrice: Double? = null,
    val trends: List<Double> = emptyList(),
    /** 搜索结果里没有可精确匹配的单型号商品 → 价格不可信，列表显示「暂无准确报价」 */
    val noMatch: Boolean = false,
    /** 上次成功采集时间戳（0 = 从未采过）。用于「最久没更新的优先采」 */
    val updatedAt: Long = 0L,

    // ---------------- 以下为本次增量（P0-BE-01 / P1-BE-05 / P0-BE-03） ----------------
    /**
     * 候选关键词组（3~4 个）。`keywords[0] === keyword`，按序轮询。
     * 空列表 = 老后端 → 调用方回退到 `listOf(keyword)`。
     */
    val keywords: List<String> = emptyList(),
    /** 服务端判定的「长期无匹配」→ 降频到 36h（仅诊断/执行用，不改变展示） */
    val lowPriority: Boolean = false,
    /** 服务端建议的下次采集时间（epoch ms）；0 = 无约束 */
    val nextDueAt: Long = 0L,
    /** 是否处于价格熔断观察期（展示语义仍用 price，不改 UI） */
    val priceSuspect: Boolean = false,
    /** 最近一次采集**尝试**时间（任何结果都推进）；nextDueAt 的唯一基准 */
    val lastCrawlAt: Long = 0L,
    /** 'ok' | 'noMatch' | 'parseEmpty' | 'suspect' | 'unknown'（诊断用） */
    val priceStatus: String = ""
) {
    /** 实际可用的候选词：老后端回退单关键词，且过滤空串（防御脏数据） */
    val keywordList: List<String>
        get() {
            val list = keywords.filter { it.isNotBlank() }
            if (list.isNotEmpty()) return list
            return if (keyword.isNotBlank()) listOf(keyword) else emptyList()
        }

    companion object {
        fun fromJson(o: JSONObject): HwModel {
            val p = if (o.isNull("price")) null else o.optDouble("price")
            val pp = if (o.isNull("prevPrice")) null else o.optDouble("prevPrice")
            val ta = o.optJSONArray("trends")
            // ★ trends 必须仍是数字数组：元素一旦变成对象，这里会逐项取到 NaN/0，折线就废了
            val trends = if (ta == null) emptyList() else (0 until ta.length()).map { ta.optDouble(it) }
            val ka = o.optJSONArray("keywords")
            val kws = if (ka == null) emptyList() else (0 until ka.length()).mapNotNull {
                val s = ka.optString(it, "")
                if (s.isBlank()) null else s
            }
            return HwModel(
                key = o.optString("key"),
                cat = o.optString("cat"),
                catLabel = o.optString("catLabel"),
                brand = o.optString("brand"),
                model = o.optString("model"),
                spec = o.optString("spec"),
                keyword = o.optString("keyword"),
                price = p,
                platform = o.optString("platform").ifBlank { null },
                trend = o.optString("trend", "flat"),
                prevPrice = pp,
                trends = trends,
                noMatch = o.optBoolean("noMatch", false),
                updatedAt = o.optLong("updatedAt", 0L),
                keywords = kws,
                lowPriority = o.optBoolean("lowPriority", false),
                nextDueAt = o.optLong("nextDueAt", 0L),
                priceSuspect = o.optBoolean("priceSuspect", false),
                lastCrawlAt = o.optLong("lastCrawlAt", 0L),
                priceStatus = o.optString("priceStatus", "")
            )
        }
    }
}
