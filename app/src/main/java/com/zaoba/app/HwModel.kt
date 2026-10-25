package com.zaoba.app

import org.json.JSONObject

/**
 * 硬件型号 + 价格数据模型。
 * 字段与后端 /api/hw/models 返回对齐。
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
    val updatedAt: Long = 0L
) {
    companion object {
        fun fromJson(o: JSONObject): HwModel {
            val p = if (o.isNull("price")) null else o.optDouble("price")
            val pp = if (o.isNull("prevPrice")) null else o.optDouble("prevPrice")
            val ta = o.optJSONArray("trends")
            val trends = if (ta == null) emptyList() else (0 until ta.length()).map { ta.optDouble(it) }
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
                updatedAt = o.optLong("updatedAt", 0L)
            )
        }
    }
}
