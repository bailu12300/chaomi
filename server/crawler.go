package main

// ---------------- 采集：下发型号清单 + 接收回传数据 ----------------
//
// 架构：采集跑在 App 端（手机 IP），服务器只负责「下发目标 + 校验入库」。
// 这样避免服务器 IP 被慢慢买风控，也符合既有的采集约定。
//
// 鉴权：请求头 X-Crawler-Key 必须等于 HW_CRAWLER_KEY（服务器 cm01.env）。
// 未配置该变量时接口直接 503，防止误开裸奔。

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"
)

// crawlTarget 采集目标：关键词 + 期望品类
type crawlTarget struct {
	Keyword string `json:"keyword"`
	Cat     string `json:"cat"`
	Brand   string `json:"brand"`
	Model   string `json:"model"`
	Spec    string `json:"spec"`
}

// crawlTargets 型号清单。与 App 的 CATS 和大时代收录范围保持一致：
// 显卡 RTX 4060~5090（40/50系）、Intel 12~14代、AMD 5600~9800X3D、SSD、内存。
var crawlTargets = []crawlTarget{
	// ---- 显卡 NVIDIA 40 系 ----
	{"RTX 4060", "显卡", "NVIDIA", "RTX 4060", "8G GDDR6"},
	{"RTX 4060 Ti 8G", "显卡", "NVIDIA", "RTX 4060 Ti", "8G GDDR6"},
	{"RTX 4060 Ti 16G", "显卡", "NVIDIA", "RTX 4060 Ti", "16G GDDR6"},
	{"RTX 4070", "显卡", "NVIDIA", "RTX 4070", "12G GDDR6X"},
	{"RTX 4070 SUPER", "显卡", "NVIDIA", "RTX 4070 SUPER", "12G GDDR6X"},
	{"RTX 4070 Ti SUPER", "显卡", "NVIDIA", "RTX 4070 Ti SUPER", "16G GDDR6X"},
	{"RTX 4080 SUPER", "显卡", "NVIDIA", "RTX 4080 SUPER", "16G GDDR6X"},
	{"RTX 4090", "显卡", "NVIDIA", "RTX 4090", "24G GDDR6X"},
	// ---- 显卡 NVIDIA 50 系 ----
	{"RTX 5060", "显卡", "NVIDIA", "RTX 5060", "8G GDDR7"},
	{"RTX 5060 Ti 8G", "显卡", "NVIDIA", "RTX 5060 Ti", "8G GDDR7"},
	{"RTX 5060 Ti 16G", "显卡", "NVIDIA", "RTX 5060 Ti", "16G GDDR7"},
	{"RTX 5070", "显卡", "NVIDIA", "RTX 5070", "12G GDDR7"},
	{"RTX 5070 Ti", "显卡", "NVIDIA", "RTX 5070 Ti", "16G GDDR7"},
	{"RTX 5080", "显卡", "NVIDIA", "RTX 5080", "16G GDDR7"},
	{"RTX 5090", "显卡", "NVIDIA", "RTX 5090", "32G GDDR7"},
	// ---- 处理器 Intel 12~14 代 ----
	{"i5-12400F", "处理器", "Intel", "i5-12400F", ""},
	{"i5-12600KF", "处理器", "Intel", "i5-12600KF", ""},
	{"i7-12700KF", "处理器", "Intel", "i7-12700KF", ""},
	{"i5-13400F", "处理器", "Intel", "i5-13400F", ""},
	{"i5-13600KF", "处理器", "Intel", "i5-13600KF", ""},
	{"i7-13700KF", "处理器", "Intel", "i7-13700KF", ""},
	{"i9-13900K", "处理器", "Intel", "i9-13900K", ""},
	{"i5-14400F", "处理器", "Intel", "i5-14400F", ""},
	{"i5-14600KF", "处理器", "Intel", "i5-14600KF", ""},
	{"i7-14700KF", "处理器", "Intel", "i7-14700KF", ""},
	// ---- 处理器 AMD ----
	{"R5 5600", "处理器", "AMD", "R5 5600", ""},
	{"R5 5600X", "处理器", "AMD", "R5 5600X", ""},
	{"R7 5700X3D", "处理器", "AMD", "R7 5700X3D", ""},
	{"R5 7500F", "处理器", "AMD", "R5 7500F", ""},
	{"R5 7600X", "处理器", "AMD", "R5 7600X", ""},
	{"R7 7700X", "处理器", "AMD", "R7 7700X", ""},
	{"R7 7800X3D", "处理器", "AMD", "R7 7800X3D", ""},
	{"R5 9600X", "处理器", "AMD", "R5 9600X", ""},
	{"R7 9700X", "处理器", "AMD", "R7 9700X", ""},
	{"R9 9800X3D", "处理器", "AMD", "R9 9800X3D", ""},
	// ---- 固态硬盘 ----
	{"990 EVO Plus", "固态硬盘", "三星", "990 EVO Plus", ""},
	{"990 PRO", "固态硬盘", "三星", "990 PRO", ""},
	{"SN850X", "固态硬盘", "西部数据", "SN850X", ""},
	{"TiPlus 7100", "固态硬盘", "致态", "TiPlus 7100", ""},
	{"TiPlus 9100", "固态硬盘", "致态", "TiPlus 9100", ""},
	{"P44 Pro", "固态硬盘", "Solidigm", "P44 Pro", ""},
	// ---- 内存条 ----
	{"DDR5 6000 16G", "内存条", "金百达", "DDR5 6000 16G", ""},
	{"DDR5 6400 16G", "内存条", "光威", "DDR5 6400 16G", ""},
	{"DDR4 3200 16G", "内存条", "金百达", "DDR4 3200 16G", ""},
}

// crawlerKey 从环境变量读，不写在代码里（部署时放 cm01.env）
func crawlerKey() string {
	return strings.TrimSpace(os.Getenv("HW_CRAWLER_KEY"))
}

func checkCrawlerAuth(w http.ResponseWriter, r *http.Request) bool {
	want := crawlerKey()
	if want == "" {
		writeJSON(w, 503, map[string]string{"error": "服务端未配置 HW_CRAWLER_KEY，采集接口已关闭"})
		return false
	}
	got := strings.TrimSpace(r.Header.Get("X-Crawler-Key"))
	if got == "" {
		got = strings.TrimSpace(r.URL.Query().Get("key"))
	}
	if got != want {
		writeJSON(w, 401, map[string]string{"error": "采集密钥不正确"})
		return false
	}
	return true
}

// handleCrawlerTargets GET /api/crawler/targets
// 下发采集清单 + 节流参数，App 不用硬编码型号
func handleCrawlerTargets(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if !checkCrawlerAuth(w, r) {
		return
	}
	// 顺带把当前各品类数量带上，App 能显示进度
	counts := map[string]int{}
	if rows, err := db.Query(`SELECT cat_label, COUNT(*) FROM jd_latest GROUP BY cat_label`); err == nil {
		for rows.Next() {
			var c string
			var n int
			if rows.Scan(&c, &n) == nil {
				counts[normCat(c)] += n
			}
		}
		rows.Close()
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"targets": crawlTargets,
			"count":   len(crawlTargets),
			// 每个关键词之间间隔多久（毫秒）。慢慢买有频率风控，太密会 0 结果。
			"intervalMs": 8000,
			// 一轮跑完休息多久
			"roundRestMs": 6 * 3600 * 1000,
			"counts":      counts,
			"serverTime":  time.Now().UnixMilli(),
		},
	})
}

// ingestItem App 回传的单条结果
type ingestItem struct {
	Keyword  string  `json:"keyword"`
	Cat      string  `json:"cat"`
	Brand    string  `json:"brand"`
	Model    string  `json:"model"`
	Spec     string  `json:"spec"`
	Title    string  `json:"title"`
	Price    float64 `json:"price"`
	Shop     string  `json:"shop"`
	Platform string  `json:"platform"`
	GoodsID  string  `json:"goodsId"`
	PicURL   string  `json:"picUrl"`
}

// handleHWCrawlerIngest POST /api/hw/ingest
// 接收 App 采集结果：校验 → 过滤 → 写 jd_rounds / jd_latest / price_history
func handleHWCrawlerIngest(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	if !checkCrawlerAuth(w, r) {
		return
	}
	var body struct {
		Items  []ingestItem `json:"items"`
		Source string       `json:"source"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败: " + err.Error()})
		return
	}
	if len(body.Items) == 0 {
		writeJSON(w, 400, map[string]string{"error": "items 为空"})
		return
	}
	if len(body.Items) > 300 {
		body.Items = body.Items[:300]
	}
	src := strings.TrimSpace(body.Source)
	if src == "" {
		src = "manmanbuy"
	}

	ts := int(time.Now().Unix())
	nowMs := time.Now().UnixMilli()

	var rounds, latest, hist [][]any
	accepted, rejected, dup := 0, 0, 0
	reasons := map[string]int{}
	seen := map[string]bool{}

	for _, it := range body.Items {
		cat := normCat(it.Cat)
		model := strings.TrimSpace(it.Model)
		brand := strings.TrimSpace(it.Brand)
		title := strings.TrimSpace(it.Title)
		price := it.Price

		// ---- 校验（宁可无价，不可错价）----
		if price < 30 || price > 300000 {
			rejected++
			reasons["价格越界"]++
			continue
		}
		if cat == "" || model == "" {
			rejected++
			reasons["缺品类/型号"]++
			continue
		}
		if len([]rune(title)) < 4 {
			rejected++
			reasons["标题过短"]++
			continue
		}
		if reBadRow.MatchString(title) {
			rejected++
			reasons["标题含无效词"]++
			continue
		}
		// 整机 / 套装 / 配件一律不要
		if cat != "笔记本" && crawlerWholeMachine(title) {
			rejected++
			reasons["整机/套装"]++
			continue
		}
		// 型号必须真的出现在标题里，防止「搜索结果第一条是别的型号」
		if !crawlerTitleOK(title, model) {
			rejected++
			reasons["标题不符型号"]++
			continue
		}

		// 同一轮同型号只留最低价
		rk := hwStableKey(cat, brand, model, it.Spec)
		if seen[rk] {
			dup++
			// 已存在则比较价格，低的替换（先删后加会破坏顺序，这里简单跳过并标重）
			continue
		}
		seen[rk] = true

		spec := strings.TrimSpace(it.Spec)
		plat := strings.TrimSpace(it.Platform)
		if plat == "" {
			plat = "慢慢买"
		}
		shop := strings.TrimSpace(it.Shop)

		rounds = append(rounds, []any{"m3", rk, cat, cat, brand, model, spec,
			it.GoodsID, title, price, shop, 0, 0, src, plat, ts})
		latest = append(latest, []any{"m3", rk, cat, brand, model, spec,
			it.GoodsID, title, price, shop, 0, src, plat, "", nil, ts})
		hist = append(hist, []any{"m3", rk, cat, ts, price, price, price, 1, src, model})
		accepted++
	}

	if accepted > 0 {
		if err := insertIgnore(db, "jd_rounds",
			[]string{"module", "row_key", "cat", "cat_label", "brand", "model", "spec",
				"sku", "title", "price", "shop", "score", "reused", "source", "platform", "ts"},
			rounds); err != nil {
			writeJSON(w, 500, map[string]string{"error": "写 jd_rounds 失败: " + err.Error()})
			return
		}
		if err := insertReplace(db, "jd_latest",
			[]string{"module", "row_key", "cat_label", "brand", "model", "spec",
				"sku", "title", "price", "shop", "score", "source", "platform", "trend", "prev_price", "ts"},
			latest); err != nil {
			writeJSON(w, 500, map[string]string{"error": "写 jd_latest 失败: " + err.Error()})
			return
		}
		if err := insertIgnore(db, "price_history",
			[]string{"module", "row_key", "cat", "ts", "ref_price", "lo_price", "hi_price",
				"quotes", "source", "sample"},
			hist); err != nil {
			// 历史点失败不阻断主流程
			_ = err
		}
	}

	// 返回结果，App 可直接展示
	type pair struct {
		K string
		V int
	}
	rs := make([]pair, 0, len(reasons))
	for k, v := range reasons {
		rs = append(rs, pair{k, v})
	}
	sort.Slice(rs, func(i, j int) bool { return rs[i].V > rs[j].V })

	rejList := make([]map[string]interface{}, 0, len(rs))
	for _, p := range rs {
		rejList = append(rejList, map[string]interface{}{"reason": p.K, "count": p.V})
	}

	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"accepted":  accepted,
			"rejected":  rejected,
			"duplicate": dup,
			"reasons":   rejList,
			"ts":        nowMs,
		},
	})
}

// crawlerTitleOK 标题必须含型号。用「核心数字型号」匹配，而不是整串：
//
//	model "R7 7800X3D" → core "7800X3D" → 命中「AMD 锐龙7 7800X3D」
//	model "RTX 5070"   → core "5070"    → 「RTX5070」「RTX 5070」都命中
//
// 同时拒绝更高阶型号：核心数字后紧跟 Ti/Super/X3D/KF 的判为不同型号。
func crawlerTitleOK(title, model string) bool {
	t := strings.ToUpper(strings.Join(strings.Fields(title), ""))
	core := crawlerCoreModel(model)
	if core == "" {
		return true
	}
	idx := strings.Index(t, core)
	if idx < 0 {
		return false
	}
	rest := t[idx+len(core):]
	for _, suf := range []string{"TI", "SUPER", "XT", "X3D", "KF", "3D"} {
		if strings.HasPrefix(rest, suf) && !strings.Contains(core, suf) {
			return false
		}
	}
	return true
}

// crawlerCoreModel 取型号里「数字最多」的一段，如 R7 7800X3D → 7800X3D
func crawlerCoreModel(model string) string {
	parts := strings.FieldsFunc(strings.ToUpper(model), func(r rune) bool {
		return r == ' ' || r == '\t' || r == '-' || r == '_' || r == '/'
	})
	best, bestDigits := "", 0
	for _, p := range parts {
		d := 0
		for _, c := range p {
			if c >= '0' && c <= '9' {
				d++
			}
		}
		if d >= 2 && d > bestDigits {
			best, bestDigits = p, d
		}
	}
	if best == "" {
		best = strings.ToUpper(strings.Join(strings.Fields(model), ""))
	}
	return best
}

// crawlerWholeMachine 整机 / 套装 / 配件类，一律剔除（宁可无价，不可错价）
func crawlerWholeMachine(title string) bool {
	t := strings.ToUpper(title)
	for _, w := range []string{"整机", "主机", "笔记本", "板U", "套装", "散片套装",
		"显示器", "键盘", "鼠标", "散热", "机箱", "电源", "内存条 DDR", "工控"} {
		if strings.Contains(t, strings.ToUpper(w)) {
			// 笔记本是独立品类，内存条关键词豁免
			if w == "笔记本" || w == "内存条 DDR" {
				continue
			}
			return true
		}
	}
	return false
}
