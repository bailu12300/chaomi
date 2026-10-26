package main

// ---------------- Amazon 显卡价格采集（RTX 4060 ~ RTX 5090）----------------
//
// 数据来源：videocardprices.com 的 Amazon 价格追踪页
//   https://www.videocardprices.com/card/nvidia-rtx-5090/
//
// 为什么不用直连 amazon.com：
//   实测从服务器（机房 IP）访问 Amazon，搜索页返回 503，
//   商品页能拿到标题但 corePrice 区块被清空 —— 价格对机房 IP 隐藏。
//   这个站点把 Amazon 的实时价以 schema.org JSON-LD 结构化输出
//   （name / offers.price / offers.url=Amazon dp 链接 / ASIN），
//   稳定可解析，且明确标注数据来自 Amazon。
//
// 只收录 40/50 系消费卡（用户要求 4060~5090），每 2 小时一轮。

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 45Series 收录清单：slug → 型号 / 规格
// 只放站点实际有追踪的型号（其余 404，见 fetchOne 的降级处理）
var gpuTargets = []struct {
	Slug  string
	Model string
	Spec  string
}{
	{"nvidia-rtx-4060", "4060", "8GB GDDR6"},
	{"nvidia-rtx-4060-ti", "4060 Ti", "8/16GB GDDR6"},
	{"nvidia-rtx-4070", "4070", "12GB GDDR6X"},
	{"nvidia-rtx-4070-super", "4070 SUPER", "12GB GDDR6X"},
	{"nvidia-rtx-4070-ti", "4070 Ti", "12GB GDDR6X"},
	{"nvidia-rtx-4070-ti-super", "4070 Ti SUPER", "16GB GDDR6X"},
	{"nvidia-rtx-4080", "4080", "16GB GDDR6X"},
	{"nvidia-rtx-4080-super", "4080 SUPER", "16GB GDDR6X"},
	{"nvidia-rtx-4090", "4090", "24GB GDDR6X"},
	{"nvidia-rtx-5050", "5050", "8GB GDDR6"},
	{"nvidia-rtx-5060", "5060", "8GB GDDR7"},
	{"nvidia-rtx-5060-ti", "5060 Ti", "8/16GB GDDR7"},
	{"nvidia-rtx-5070", "5070", "12GB GDDR7"},
	{"nvidia-rtx-5070-ti", "5070 Ti", "16GB GDDR7"},
	{"nvidia-rtx-5080", "5080", "16GB GDDR7"},
	{"nvidia-rtx-5090", "5090", "32GB GDDR7"},
}

const (
	cardPageURL   = "https://www.videocardprices.com/card/%s/"
	collectEvery  = 2 * time.Hour // 用户要求：2 小时一轮
	fxRefreshEvery = time.Hour
)

// ===== 汇率 =====

type fxState struct {
	mu     sync.RWMutex
	rate   float64
	at     int64
	source string
}

var fx = &fxState{rate: 7.1, source: "fallback"}

func refreshFX() {
	type resp struct {
		Rates map[string]float64 `json:"rates"`
	}
	for _, u := range []string{
		"https://open.er-api.com/v6/latest/USD",
		"https://api.exchangerate-api.com/v4/latest/USD",
	} {
		req, err := http.NewRequest("GET", u, nil)
		if err != nil {
			continue
		}
		req.Header.Set("User-Agent", "chaomi/1.0")
		r, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
		if err != nil {
			continue
		}
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		r.Body.Close()
		var j resp
		if json.Unmarshal(body, &j) != nil {
			continue
		}
		if cny, ok := j.Rates["CNY"]; ok && cny > 3 && cny < 15 {
			fx.mu.Lock()
			fx.rate, fx.at, fx.source = cny, time.Now().UnixMilli(), u
			fx.mu.Unlock()
			logf("[fx] USD/CNY = %.4f", cny)
			return
		}
	}
	logf("[fx] 汇率刷新失败，沿用 %.4f", fx.rate)
}

func fxRate() (float64, int64, string) {
	fx.mu.RLock()
	defer fx.mu.RUnlock()
	return fx.rate, fx.at, fx.source
}

// ===== 表 =====

func ensureAmazonGPUSchema(db *sql.DB) error {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS amz_gpu(
		id BIGINT AUTO_INCREMENT PRIMARY KEY,
		row_key VARCHAR(64) NOT NULL,
		model VARCHAR(64) NOT NULL,
		model_full VARCHAR(128) NOT NULL,
		spec VARCHAR(64) DEFAULT '',
		title VARCHAR(512) DEFAULT '',
		price_usd DECIMAL(12,2) NOT NULL,
		price_cny DECIMAL(12,2) NOT NULL,
		fx_rate DECIMAL(10,4) NOT NULL,
		asin VARCHAR(16) DEFAULT '',
		amz_url VARCHAR(512) DEFAULT '',
		source_url VARCHAR(512) DEFAULT '',
		availability VARCHAR(32) DEFAULT '',
		image VARCHAR(512) DEFAULT '',
		ts BIGINT NOT NULL,
		UNIQUE KEY uk_row_key(row_key),
		INDEX idx_ts(ts)
	) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`); err != nil {
		return err
	}
	_, err := db.Exec(`CREATE TABLE IF NOT EXISTS amz_gpu_history(
		id BIGINT AUTO_INCREMENT PRIMARY KEY,
		row_key VARCHAR(64) NOT NULL,
		model VARCHAR(64) NOT NULL,
		price_usd DECIMAL(12,2) NOT NULL,
		price_cny DECIMAL(12,2) NOT NULL,
		fx_rate DECIMAL(10,4) NOT NULL,
		ts BIGINT NOT NULL,
		INDEX idx_key_ts(row_key, ts)
	) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`)
	return err
}

// ===== 采集 =====

type cardInfo struct {
	Name    string  `json:"name"`
	Model   string  `json:"model"`
	ASIN    string  `json:"asin"`
	Price   float64 `json:"price"`
	Cur     string  `json:"cur"`
	Avail   string  `json:"avail"`
	AmzURL  string  `json:"amzUrl"`
	Image   string  `json:"image"`
	OK      bool    `json:"ok"`
	Err     string  `json:"err,omitempty"`
}

// parseCardJSONLD 从页面里抠 schema.org Product
func parseCardJSONLD(html string) *cardInfo {
	reLD := regexp.MustCompile(`(?is)<script[^>]*application/ld\+json[^>]*>(.*?)</script>`)
	for _, m := range reLD.FindAllStringSubmatch(html, -1) {
		txt := strings.TrimSpace(m[1])
		var raw interface{}
		if json.Unmarshal([]byte(txt), &raw) != nil {
			continue
		}
		var visit func(v interface{}) *cardInfo
		visit = func(v interface{}) *cardInfo {
			switch t := v.(type) {
			case []interface{}:
				for _, it := range t {
					if c := visit(it); c != nil {
						return c
					}
				}
			case map[string]interface{}:
				if typ, _ := t["@type"].(string); typ == "Product" {
					c := &cardInfo{OK: true}
					c.Name, _ = t["name"].(string)
					c.Model, _ = t["model"].(string)
					c.Image, _ = t["image"].(string)
					if id, ok := t["identifier"].(map[string]interface{}); ok {
						c.ASIN, _ = id["value"].(string)
					}
					if off, ok := t["offers"].(map[string]interface{}); ok {
						switch p := off["price"].(type) {
						case float64:
							c.Price = p
						case string:
							c.Price, _ = strconv.ParseFloat(p, 64)
						}
						c.Cur, _ = off["priceCurrency"].(string)
						c.AmzURL, _ = off["url"].(string)
						if av, ok := off["availability"].(string); ok {
							if i := strings.LastIndex(av, "/"); i >= 0 {
								av = av[i+1:]
							}
							c.Avail = av
						}
					}
					if c.Price > 0 {
						return c
					}
				}
			}
			return nil
		}
		if c := visit(raw); c != nil {
			return c
		}
	}
	return nil
}

func fetchOne(slug string) *cardInfo {
	u := fmt.Sprintf(cardPageURL, slug)
	req, err := http.NewRequest("GET", u, nil)
	if err != nil {
		return &cardInfo{Err: err.Error()}
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "+
		"AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36")
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	r, err := (&http.Client{Timeout: 20 * time.Second}).Do(req)
	if err != nil {
		return &cardInfo{Err: err.Error()}
	}
	defer r.Body.Close()
	if r.StatusCode == 404 {
		return &cardInfo{Err: "该型号未收录"}
	}
	if r.StatusCode != 200 {
		return &cardInfo{Err: fmt.Sprintf("HTTP %d", r.StatusCode)}
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 2<<20))
	if err != nil {
		return &cardInfo{Err: "读取失败"}
	}
	c := parseCardJSONLD(string(body))
	if c == nil || c.Price <= 0 {
		return &cardInfo{Err: "页面无价格数据"}
	}
	c.OK = true
	return c
}

var amzLast = struct {
	mu      sync.RWMutex
	at      int64
	ok      int
	total   int
	msg     string
	missing []string
}{}

func amzStatus() map[string]interface{} {
	amzLast.mu.RLock()
	defer amzLast.mu.RUnlock()
	rate, fxAt, fxSrc := fxRate()
	return map[string]interface{}{
		"lastAt":    amzLast.at,
		"okCount":   amzLast.ok,
		"total":     amzLast.total,
		"missing":   amzLast.missing,
		"message":   amzLast.msg,
		"intervalH": 2,
		"fxRate":    rate,
		"fxAt":      fxAt,
		"fxSource":  fxSrc,
		"source":    "Amazon（经 videocardprices 追踪）",
	}
}

// collectAmazon 抓一轮全部型号
func collectAmazon(db *sql.DB) (int, error) {
	rate, _, _ := fxRate()
	now := time.Now().UnixMilli()

	saved := 0
	var missing []string

	for _, t := range gpuTargets {
		c := fetchOne(t.Slug)
		if c == nil || !c.OK {
			missing = append(missing, t.Model)
			if c != nil && c.Err != "" {
				logf("[amz] %-12s 跳过：%s", t.Model, c.Err)
			}
			time.Sleep(800 * time.Millisecond) // 温和一点，别把站点打挂
			continue
		}
		cny := c.Price * rate
		key := strings.ReplaceAll(t.Model, " ", "-")

		if _, err := db.Exec(`INSERT INTO amz_gpu
			(row_key,model,model_full,spec,title,price_usd,price_cny,fx_rate,
			 asin,amz_url,source_url,availability,image,ts)
			VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
			ON DUPLICATE KEY UPDATE
			price_usd=VALUES(price_usd), price_cny=VALUES(price_cny),
			fx_rate=VALUES(fx_rate), title=VALUES(title), spec=VALUES(spec),
			asin=VALUES(asin), amz_url=VALUES(amz_url),
			availability=VALUES(availability), image=VALUES(image), ts=VALUES(ts)`,
			key, t.Model, "RTX "+t.Model, t.Spec, c.Name, c.Price, cny, rate,
			c.ASIN, c.AmzURL, fmt.Sprintf(cardPageURL, t.Slug),
			c.Avail, c.Image, now); err != nil {
			logf("[amz] %s 写入失败: %v", t.Model, err)
			continue
		}
		_, _ = db.Exec(`INSERT INTO amz_gpu_history
			(row_key,model,price_usd,price_cny,fx_rate,ts) VALUES(?,?,?,?,?,?)`,
			key, t.Model, c.Price, cny, rate, now)
		saved++
		time.Sleep(800 * time.Millisecond)
	}

	amzLast.mu.Lock()
	amzLast.at = now
	amzLast.ok = saved
	amzLast.total = len(gpuTargets)
	amzLast.missing = missing
	if saved == 0 {
		amzLast.msg = "全部失败，请检查网络或数据源"
	} else {
		amzLast.msg = fmt.Sprintf("成功 %d/%d 个型号", saved, len(gpuTargets))
	}
	amzLast.mu.Unlock()

	logf("[amz] 采集完成：%d/%d，汇率 %.4f", saved, len(gpuTargets), rate)
	if saved == 0 {
		return 0, fmt.Errorf("全部型号都未取到价格")
	}
	return saved, nil
}

func startAmazonLoop(db *sql.DB) {
	go func() {
		time.Sleep(5 * time.Second)
		refreshFX()
		if _, err := collectAmazon(db); err != nil {
			logf("[amz] 首次采集失败: %v", err)
		}
		fxT := time.NewTicker(fxRefreshEvery)
		amzT := time.NewTicker(collectEvery)
		for {
			select {
			case <-fxT.C:
				refreshFX()
			case <-amzT.C:
				if _, err := collectAmazon(db); err != nil {
					logf("[amz] 周期采集失败: %v", err)
				}
			}
		}
	}()
}

// ===== API =====

// handleAmazonGPUs GET /api/gpu/list
func handleAmazonGPUs(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	rows, err := db.Query(`SELECT row_key,model,model_full,spec,title,price_usd,
		price_cny,fx_rate,asin,amz_url,availability,image,ts FROM amz_gpu`)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()

	list := []map[string]interface{}{}
	for rows.Next() {
		var key, model, full, spec, title, asin, amzURL, avail, image string
		var usd, cny, rate float64
		var ts int64
		if rows.Scan(&key, &model, &full, &spec, &title, &usd, &cny, &rate,
			&asin, &amzURL, &avail, &image, &ts) != nil {
			continue
		}
		list = append(list, map[string]interface{}{
			"key": key, "model": model, "modelFull": full, "spec": spec,
			"title": title, "priceUsd": usd, "priceCny": cny, "fxRate": rate,
			"asin": asin, "amazonUrl": amzURL, "availability": avail,
			"image": image, "updatedAt": ts,
		})
	}

	// 按型号数字排序：4060 < 4070 < ... < 5090
	sort.Slice(list, func(i, j int) bool {
		a := numOrder(list[i]["model"].(string))
		b := numOrder(list[j]["model"].(string))
		if a != b {
			return a < b
		}
		// 同数字时：非 Ti 在前（5060 在 5060 Ti 之前）
		return !strings.Contains(list[i]["model"].(string), "Ti") &&
			strings.Contains(list[j]["model"].(string), "Ti")
	})

	rate, fxAt, fxSrc := fxRate()
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"count": len(list),
			"list":  list,
			"fx": map[string]interface{}{
				"rate": rate, "at": fxAt, "source": fxSrc, "pair": "USD/CNY",
			},
			"status": amzStatus(),
			"note":   "价格为 Amazon 美国站显卡零售价（美元）；人民币按实时汇率换算",
		},
	})
}

// numOrder 型号排序权重：5090 → 5090，4060 Ti → 4060
func numOrder(model string) int {
	var b strings.Builder
	for _, r := range model {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		} else if b.Len() > 0 {
			break
		}
	}
	n, _ := strconv.Atoi(b.String())
	return n
}

// handleAmazonGPUHistory GET /api/gpu/history?key=5090
func handleAmazonGPUHistory(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	key := strings.TrimSpace(r.URL.Query().Get("key"))
	if key == "" {
		writeJSON(w, 400, map[string]string{"error": "缺少 key"})
		return
	}
	rows, err := db.Query(`SELECT ts,price_usd,price_cny FROM amz_gpu_history
		WHERE row_key=? ORDER BY ts ASC LIMIT 500`, key)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()

	points := []map[string]interface{}{}
	var lo, hi, last float64
	for rows.Next() {
		var ts int64
		var usd, cny float64
		if rows.Scan(&ts, &usd, &cny) != nil {
			continue
		}
		points = append(points, map[string]interface{}{"ts": ts, "usd": usd, "cny": cny})
		if lo == 0 || usd < lo {
			lo = usd
		}
		if usd > hi {
			hi = usd
		}
		last = usd
	}
	rate, _, _ := fxRate()
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"key": key, "points": points,
			"minUsd": lo, "maxUsd": hi, "lastUsd": last, "fxRate": rate,
		},
	})
}

// handleTriggerAmazon POST /api/gpu/collect
func handleTriggerAmazon(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	n, err := collectAmazon(db)
	if err != nil {
		writeJSON(w, 502, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0, "data": map[string]interface{}{"saved": n, "status": amzStatus()},
	})
}
