package main

// ---------------- GPU 价格统一采集：零售卡 + 云 GPU ----------------
//
// 两个来源，一个表，用 kind 区分，不混淆口径：
//   kind=retail  Amazon 美国站零售价（$ / 张）        —— 经 videocardprices 追踪
//   kind=cloud   VoltageGPU 云 GPU 租赁价（$ / GPU / 小时）
//
// 关于「有争议的价」：
//   零售卡价格与官方指导价（MSRP）偏离过大时仍然采集，但打上 disputed 标记
//   并给出原因（如「溢价 272%」），供用户自行判断，不做静默丢弃。
//   云 GPU 是按小时租赁价，与零售价口径不同，不参与 MSRP 争议判定，
//   只按 kind=cloud 明确标注。

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

const (
	kindRetail = "retail"
	kindCloud  = "cloud"

	collectEvery   = 2 * time.Hour
	fxRefreshEvery = time.Hour

	amazonCardURL   = "https://www.videocardprices.com/card/%s/"
	voltageSnapURL  = "https://voltagegpu.com/api/pricing/snapshot"
)

// msrpUSD 官方指导价（美元），用于判定「有争议」
// 数据来源：NVIDIA 官方发布价，FE 版为准
var msrpUSD = map[string]float64{
	"4060": 299, "4060 Ti": 399,
	"4070": 599, "4070 SUPER": 599, "4070 Ti": 799, "4070 Ti SUPER": 799,
	"4080": 1199, "4080 SUPER": 999, "4090": 1599,
	"5050": 249, "5060": 299, "5060 Ti": 379,
	"5070": 549, "5070 Ti": 749, "5080": 999, "5090": 1999,
}

// gpuTarget 零售卡采集目标
var retailTargets = []struct {
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

var reRTX4550 = regexp.MustCompile(`RTX\s*(40\d0|50\d0)\s*(Ti|SUPER|Super|super)?`)

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

func ensureGPUSchema(db *sql.DB) error {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS gpu_price(
		id BIGINT AUTO_INCREMENT PRIMARY KEY,
		row_key VARCHAR(80) NOT NULL,
		kind VARCHAR(16) NOT NULL,
		model VARCHAR(64) NOT NULL,
		model_full VARCHAR(128) NOT NULL,
		spec VARCHAR(64) DEFAULT '',
		title VARCHAR(512) DEFAULT '',
		price_usd DECIMAL(12,4) NOT NULL,
		price_cny DECIMAL(12,2) NOT NULL,
		fx_rate DECIMAL(10,4) NOT NULL,
		unit VARCHAR(32) DEFAULT '',
		msrp_usd DECIMAL(10,2) DEFAULT 0,
		deviation DECIMAL(8,2) DEFAULT 0,
		disputed TINYINT DEFAULT 0,
		dispute_reason VARCHAR(128) DEFAULT '',
		source VARCHAR(64) DEFAULT '',
		buy_url VARCHAR(512) DEFAULT '',
		source_url VARCHAR(512) DEFAULT '',
		extra VARCHAR(255) DEFAULT '',
		availability VARCHAR(32) DEFAULT '',
		image VARCHAR(512) DEFAULT '',
		ts BIGINT NOT NULL,
		UNIQUE KEY uk_row_key(row_key),
		INDEX idx_kind(kind),
		INDEX idx_ts(ts)
	) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`); err != nil {
		return err
	}
	_, err := db.Exec(`CREATE TABLE IF NOT EXISTS gpu_price_history(
		id BIGINT AUTO_INCREMENT PRIMARY KEY,
		row_key VARCHAR(80) NOT NULL,
		kind VARCHAR(16) NOT NULL,
		model VARCHAR(64) NOT NULL,
		price_usd DECIMAL(12,4) NOT NULL,
		price_cny DECIMAL(12,2) NOT NULL,
		fx_rate DECIMAL(10,4) NOT NULL,
		disputed TINYINT DEFAULT 0,
		ts BIGINT NOT NULL,
		INDEX idx_key_ts(row_key, ts)
	) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`)
	return err
}

// ===== 争议判定 =====

// judgeDispute 用 MSRP 判断零售价是否「有争议」
// 超过 30% 溢价或低于 30% 折价都算，仍会采集，只是打标
func judgeDispute(model string, price float64) (bool, float64, string) {
	msrp := msrpUSD[model]
	if msrp <= 0 || price <= 0 {
		return false, 0, ""
	}
	dev := (price - msrp) / msrp * 100
	if dev >= 30 {
		return true, round2(dev), fmt.Sprintf("溢价 %.0f%%（官方指导价 $%.0f）", dev, msrp)
	}
	if dev <= -30 {
		return true, round2(dev), fmt.Sprintf("低于指导价 %.0f%%（官方指导价 $%.0f）", -dev, msrp)
	}
	return false, round2(dev), ""
}

func round2(v float64) float64 {
	return float64(int(v*100+0.5*sign(v))) / 100
}

func sign(v float64) float64 {
	if v < 0 {
		return -1
	}
	return 1
}

// ===== 零售卡采集（Amazon，经 videocardprices 追踪）=====

type cardInfo struct {
	Name   string  `json:"name"`
	Model  string  `json:"model"`
	ASIN   string  `json:"asin"`
	Price  float64 `json:"price"`
	Cur    string  `json:"cur"`
	Avail  string  `json:"avail"`
	AmzURL string  `json:"amzUrl"`
	Image  string  `json:"image"`
	OK     bool    `json:"ok"`
	Err    string  `json:"err,omitempty"`
}

func parseCardJSONLD(html string) *cardInfo {
	reLD := regexp.MustCompile(`(?is)<script[^>]*application/ld\+json[^>]*>(.*?)</script>`)
	for _, m := range reLD.FindAllStringSubmatch(html, -1) {
		var raw interface{}
		if json.Unmarshal([]byte(strings.TrimSpace(m[1])), &raw) != nil {
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

func fetchRetailOne(slug string) *cardInfo {
	u := fmt.Sprintf(amazonCardURL, slug)
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

// ===== 云 GPU 采集（VoltageGPU）=====

type voltGPU struct {
	Model        string  `json:"model"`
	VRAM         string  `json:"vram"`
	PricePerHour float64 `json:"pricePerHour"`
	Available    int     `json:"available"`
	Label        string  `json:"label"`
}

type voltSnapshot struct {
	GPUs []voltGPU `json:"gpus"`
	Meta struct {
		UpdatedAt string `json:"updatedAt"`
		Stale     bool   `json:"stale"`
	} `json:"meta"`
}

// normRTX 归一化：RTX 5070 Ti → 5070 Ti
func normRTX(model string) string {
	m := strings.ToUpper(strings.Join(strings.Fields(model), " "))
	m = strings.TrimSpace(strings.ReplaceAll(m, "RTX", ""))
	m = regexp.MustCompile(`\s+`).ReplaceAllString(m, " ")
	m = strings.ReplaceAll(m, "TI", "Ti")
	return m
}

func fetchCloud() ([]voltGPU, error) {
	req, err := http.NewRequest("GET", voltageSnapURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "chaomi/1.0 (+https://cm01.zy1080.xyz)")
	req.Header.Set("Accept", "application/json")
	r, err := (&http.Client{Timeout: 25 * time.Second}).Do(req)
	if err != nil {
		return nil, fmt.Errorf("请求失败: %w", err)
	}
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return nil, fmt.Errorf("远端返回 %d", r.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	var snap voltSnapshot
	if err := json.Unmarshal(body, &snap); err != nil {
		return nil, fmt.Errorf("解析失败: %w", err)
	}
	// 只留 40/50 系，同型号取最低价
	best := map[string]voltGPU{}
	for _, g := range snap.GPUs {
		if !reRTX4550.MatchString(g.Model) || g.PricePerHour <= 0 {
			continue
		}
		k := normRTX(g.Model)
		if old, ok := best[k]; !ok || g.PricePerHour < old.PricePerHour {
			best[k] = g
		}
	}
	out := make([]voltGPU, 0, len(best))
	for _, g := range best {
		out = append(out, g)
	}
	return out, nil
}

// ===== 采集主流程 =====

var collectLast = struct {
	mu          sync.RWMutex
	at          int64
	retailOK    int
	cloudOK     int
	disputed    int
	missing     []string
	msg         string
	cloudErr    string
}{}

func collectAll(db *sql.DB) (int, int, error) {
	rate, _, _ := fxRate()
	now := time.Now().UnixMilli()

	tx, err := db.Begin()
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback()

	// ---- 零售卡 ----
	retailOK := 0
	var missing []string
	for _, t := range retailTargets {
		c := fetchRetailOne(t.Slug)
		if c == nil || !c.OK {
			missing = append(missing, t.Model)
			time.Sleep(700 * time.Millisecond)
			continue
		}
		cny := c.Price * rate
		msrp := msrpUSD[t.Model]
		disputed, dev, reason := judgeDispute(t.Model, c.Price)
		key := "retail:" + strings.ReplaceAll(t.Model, " ", "-")

		if _, err := tx.Exec(`INSERT INTO gpu_price
			(row_key,kind,model,model_full,spec,title,price_usd,price_cny,fx_rate,unit,
			 msrp_usd,deviation,disputed,dispute_reason,source,buy_url,source_url,
			 availability,image,ts)
			VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
			ON DUPLICATE KEY UPDATE
			price_usd=VALUES(price_usd), price_cny=VALUES(price_cny),
			fx_rate=VALUES(fx_rate), title=VALUES(title), spec=VALUES(spec),
			msrp_usd=VALUES(msrp_usd), deviation=VALUES(deviation),
			disputed=VALUES(disputed), dispute_reason=VALUES(dispute_reason),
			buy_url=VALUES(buy_url), availability=VALUES(availability),
			image=VALUES(image), ts=VALUES(ts)`,
			key, kindRetail, t.Model, "RTX "+t.Model, t.Spec, c.Name,
			c.Price, cny, rate, "USD/张",
			msrp, dev, boolToInt(disputed), reason,
			"Amazon", c.AmzURL, fmt.Sprintf(amazonCardURL, t.Slug),
			c.Avail, c.Image, now); err != nil {
			logf("[gpu] 零售 %s 写入失败: %v", t.Model, err)
			time.Sleep(700 * time.Millisecond)
			continue
		}
		_, _ = tx.Exec(`INSERT INTO gpu_price_history
			(row_key,kind,model,price_usd,price_cny,fx_rate,disputed,ts)
			VALUES(?,?,?,?,?,?,?,?)`,
			key, kindRetail, t.Model, c.Price, cny, rate, boolToInt(disputed), now)
		retailOK++
		time.Sleep(700 * time.Millisecond)
	}

	// ---- 云 GPU ----
	cloudOK := 0
	cloudErr := ""
	clouds, err := fetchCloud()
	if err != nil {
		cloudErr = err.Error()
		logf("[gpu] 云 GPU 采集失败: %v", err)
	} else {
		for _, g := range clouds {
			model := normRTX(g.Model)
			cny := g.PricePerHour * rate
			key := "cloud:" + strings.ReplaceAll(model, " ", "-")
			vram := g.VRAM
			if vram == "" {
				vram = guessVRAM(model)
			}
			if _, err := tx.Exec(`INSERT INTO gpu_price
				(row_key,kind,model,model_full,spec,title,price_usd,price_cny,fx_rate,unit,
				 msrp_usd,deviation,disputed,dispute_reason,source,buy_url,source_url,
				 extra,availability,image,ts)
				VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
				ON DUPLICATE KEY UPDATE
				price_usd=VALUES(price_usd), price_cny=VALUES(price_cny),
				fx_rate=VALUES(fx_rate), spec=VALUES(spec),
				availability=VALUES(availability), extra=VALUES(extra), ts=VALUES(ts)`,
				key, kindCloud, model, "RTX "+model, vram+", "+fmt.Sprintf("%d 卡在库", g.Available),
				"VoltageGPU 云 GPU 租赁", g.PricePerHour, cny, rate, "USD/GPU/小时",
				0, 0, 0, "", "VoltageGPU", "",
				voltageSnapURL,
				g.Label, strconv.Itoa(g.Available)+" 卡可用", "", now); err != nil {
				logf("[gpu] 云 %s 写入失败: %v", model, err)
				continue
			}
			_, _ = tx.Exec(`INSERT INTO gpu_price_history
				(row_key,kind,model,price_usd,price_cny,fx_rate,disputed,ts)
				VALUES(?,?,?,?,?,?,?,?)`,
				key, kindCloud, model, g.PricePerHour, cny, rate, 0, now)
			cloudOK++
		}
	}

	if err := tx.Commit(); err != nil {
		return 0, 0, err
	}

	// 统计有争议数量
	var disputedN int
	_ = db.QueryRow(`SELECT COUNT(*) FROM gpu_price WHERE disputed=1`).Scan(&disputedN)

	collectLast.mu.Lock()
	collectLast.at = now
	collectLast.retailOK = retailOK
	collectLast.cloudOK = cloudOK
	collectLast.disputed = disputedN
	collectLast.missing = missing
	collectLast.cloudErr = cloudErr
	collectLast.msg = fmt.Sprintf("零售 %d/%d · 云 %d · 有争议 %d",
		retailOK, len(retailTargets), cloudOK, disputedN)
	collectLast.mu.Unlock()

	logf("[gpu] 采集完成：零售 %d/%d，云 %d，有争议 %d（汇率 %.4f）",
		retailOK, len(retailTargets), cloudOK, disputedN, rate)

	if retailOK == 0 && cloudOK == 0 {
		return 0, 0, fmt.Errorf("两个来源都没取到数据")
	}
	return retailOK, cloudOK, nil
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// guessVRAM 云 GPU 的 vram 字段常为空，用已知规格补
func guessVRAM(model string) string {
	m := map[string]string{
		"4060": "8GB", "4060 Ti": "8/16GB", "4070": "12GB",
		"4070 SUPER": "12GB", "4070 Ti": "12GB", "4070 Ti SUPER": "16GB",
		"4080": "16GB", "4080 SUPER": "16GB", "4090": "24GB",
		"5050": "8GB", "5060": "8GB", "5060 Ti": "8/16GB",
		"5070": "12GB", "5070 Ti": "16GB", "5080": "16GB", "5090": "32GB",
	}
	return m[model]
}

func startGPULoop(db *sql.DB) {
	go func() {
		time.Sleep(5 * time.Second)
		refreshFX()
		if _, _, err := collectAll(db); err != nil {
			logf("[gpu] 首次采集失败: %v", err)
		}
		fxT := time.NewTicker(fxRefreshEvery)
		colT := time.NewTicker(collectEvery)
		for {
			select {
			case <-fxT.C:
				refreshFX()
			case <-colT.C:
				if _, _, err := collectAll(db); err != nil {
					logf("[gpu] 周期采集失败: %v", err)
				}
			}
		}
	}()
}

// ===== API =====

func gpuStatus() map[string]interface{} {
	collectLast.mu.RLock()
	defer collectLast.mu.RUnlock()
	rate, fxAt, fxSrc := fxRate()
	return map[string]interface{}{
		"lastAt":    collectLast.at,
		"retailOk":  collectLast.retailOK,
		"cloudOk":   collectLast.cloudOK,
		"disputed":  collectLast.disputed,
		"missing":   collectLast.missing,
		"message":   collectLast.msg,
		"cloudErr":  collectLast.cloudErr,
		"intervalH": 2,
		"fxRate":    rate,
		"fxAt":      fxAt,
		"fxSource":  fxSrc,
	}
}

// handleGPUList GET /api/gpu/list?kind=retail|cloud|all&disputed=1
func handleGPUList(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	kind := strings.TrimSpace(r.URL.Query().Get("kind"))
	onlyDisputed := r.URL.Query().Get("disputed") == "1"

	q := `SELECT row_key,kind,model,model_full,spec,title,price_usd,price_cny,fx_rate,
		unit,msrp_usd,deviation,disputed,dispute_reason,source,buy_url,source_url,
		extra,availability,image,ts FROM gpu_price`
	var where []string
	var args []any
	if kind == kindRetail || kind == kindCloud {
		where = append(where, "kind=?")
		args = append(args, kind)
	}
	if onlyDisputed {
		where = append(where, "disputed=1")
	}
	if len(where) > 0 {
		q += " WHERE " + strings.Join(where, " AND ")
	}
	rows, err := db.Query(q, args...)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()

	all := []map[string]interface{}{}
	for rows.Next() {
		var key, k, model, full, spec, title, unit, reason, source, buyURL, srcURL,
			extra, avail, image string
		var usd, cny, rate, msrp, dev float64
		var disputed int
		var ts int64
		if rows.Scan(&key, &k, &model, &full, &spec, &title, &usd, &cny, &rate,
			&unit, &msrp, &dev, &disputed, &reason, &source, &buyURL, &srcURL,
			&extra, &avail, &image, &ts) != nil {
			continue
		}
		all = append(all, map[string]interface{}{
			"key": key, "kind": k,
			"kindLabel": kindLabel(k),
			"model": model, "modelFull": full, "spec": spec, "title": title,
			"priceUsd": usd, "priceCny": cny, "fxRate": rate, "unit": unit,
			"msrpUsd": msrp, "deviation": dev,
			"disputed": disputed == 1, "disputeReason": reason,
			"source": source, "buyUrl": buyURL, "sourceUrl": srcURL,
			"extra": extra, "availability": avail, "image": image,
			"updatedAt": ts,
		})
	}

	// 排序：零售按型号数字，云也按数字
	sort.Slice(all, func(i, j int) bool {
		ki, kj := all[i]["kind"].(string), all[j]["kind"].(string)
		if ki != kj {
			return ki < kj // cloud < retail，前端会分组，这里只保证稳定
		}
		a := numOrder(all[i]["model"].(string))
		b := numOrder(all[j]["model"].(string))
		if a != b {
			return a < b
		}
		return !strings.Contains(all[i]["model"].(string), "Ti") &&
			strings.Contains(all[j]["model"].(string), "Ti")
	})

	// 分组
	retail := []map[string]interface{}{}
	cloud := []map[string]interface{}{}
	disputedList := []map[string]interface{}{}
	for _, x := range all {
		if x["kind"] == kindRetail {
			retail = append(retail, x)
		} else {
			cloud = append(cloud, x)
		}
		if x["disputed"] == true {
			disputedList = append(disputedList, x)
		}
	}

	rate, fxAt, fxSrc := fxRate()
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"count":    len(all),
			"list":     all,
			"retail":   retail,
			"cloud":    cloud,
			"disputed": disputedList,
			"fx": map[string]interface{}{
				"rate": rate, "at": fxAt, "source": fxSrc, "pair": "USD/CNY",
			},
			"status": gpuStatus(),
			"legend": map[string]interface{}{
				kindRetail: "零售卡 · Amazon 美国站价格（美元/张）",
				kindCloud:  "云 GPU · VoltageGPU 租赁价（美元/GPU/小时）",
			},
			"note": "零售卡与官方指导价偏离超过 30% 会标记为「有争议」但仍保留；云 GPU 为按小时租赁价，口径不同，不参与争议判定",
		},
	})
}

func kindLabel(k string) string {
	if k == kindCloud {
		return "云GPU"
	}
	return "零售卡"
}

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

// handleGPUHistory GET /api/gpu/history?key=retail:5090
func handleGPUHistory(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	key := strings.TrimSpace(r.URL.Query().Get("key"))
	if key == "" {
		writeJSON(w, 400, map[string]string{"error": "缺少 key"})
		return
	}
	rows, err := db.Query(`SELECT ts,price_usd,price_cny,disputed FROM gpu_price_history
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
		var dis int
		if rows.Scan(&ts, &usd, &cny, &dis) != nil {
			continue
		}
		points = append(points, map[string]interface{}{
			"ts": ts, "usd": usd, "cny": cny, "disputed": dis == 1,
		})
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

// handleGPURefresh POST /api/gpu/collect
func handleGPURefresh(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	a, b, err := collectAll(db)
	if err != nil {
		writeJSON(w, 502, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"retail": a, "cloud": b, "status": gpuStatus(),
		},
	})
}
