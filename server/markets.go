package main

// ---------------- 汇率 / 油价 / 台风 ----------------
//
// 数据源选择（都验证过可用、免费、无需 key）：
//   · 汇率：fawazahmed0 currency-api（jsDelivr 镜像）——含离岸人民币 CNH，
//     这是官方 ECB 那类源没有的，而用户明确要 CNH
//   · 原油：Yahoo Finance chart API（布伦特 BZ=F / WTI CL=F / 亨利港 NG=F）
//   · 国内油价：icauto 全国各省零售价表（92#/95#/0#）
//   · 台风：中央气象台 typhoon.nmc.cn 的 JSONP 列表接口
//
// 统一策略：全部服务端抓取 + 内存缓存，客户端只读 /api/*。
// 抓取失败时返回上一次成功的缓存（stale 标记），不让页面白屏。

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// ===== 通用带缓存抓取 =====

type cachedBlob struct {
	mu   sync.Mutex
	at   time.Time
	data interface{}
	ttl  time.Duration
}

func (c *cachedBlob) get(build func() (interface{}, error)) (interface{}, bool, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.data != nil && time.Since(c.at) < c.ttl {
		return c.data, false, nil
	}
	v, err := build()
	if err != nil {
		if c.data != nil {
			// 抓取失败但有旧数据 → 用旧的（stale）
			return c.data, true, nil
		}
		return nil, false, err
	}
	c.data = v
	c.at = time.Now()
	return v, false, nil
}

var (
	fxCache      = &cachedBlob{ttl: 10 * time.Minute}
	oilCache     = &cachedBlob{ttl: 15 * time.Minute}
	typhoonCache = &cachedBlob{ttl: 10 * time.Minute}
)

// ===== 历史走势存储 =====
//
// 需求：汇率/油价要能看走势。
// 外部免费 API 只给"当前值"，没有历史序列，所以这里自己攒：
//   · 每次成功抓到最新值就写一条（按自然小时去重，避免 4 分钟保活刷出无数点）
//   · 表很小（每小时 1 条），保留 30 天足够画 7d/30d 走势
var marketsDB *sql.DB

func ensureMarketsSchema(db *sql.DB) error {
	marketsDB = db
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS m_fx_history(
			hour_key BIGINT NOT NULL,
			code VARCHAR(8) NOT NULL,
			rate DOUBLE NOT NULL,
			created_at BIGINT NOT NULL,
			PRIMARY KEY(hour_key, code),
			INDEX idx_mfh_code(code, hour_key)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS m_oil_history(
			hour_key BIGINT NOT NULL,
			item_key VARCHAR(24) NOT NULL,
			price DOUBLE NOT NULL,
			created_at BIGINT NOT NULL,
			PRIMARY KEY(hour_key, item_key),
			INDEX idx_moh_key(item_key, hour_key)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS m_oil_prov_history(
			hour_key BIGINT NOT NULL,
			region VARCHAR(24) NOT NULL,
			p92 DOUBLE NOT NULL,
			p95 DOUBLE NOT NULL,
			p0 DOUBLE NOT NULL,
			created_at BIGINT NOT NULL,
			PRIMARY KEY(hour_key, region)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			return err
		}
	}
	return nil
}

// hourKey 当前小时的时间戳（取整点），用作去重键
func hourKey() int64 {
	return time.Now().Truncate(time.Hour).UnixMilli()
}

// saveFxHistory 落库。首次调用时若 marketsDB 还没绑定（预热早于建表），直接跳过，
// 下一轮（TTL 到期或保活）就会正常写入。
func saveFxHistory(list []fxItem) {
	if marketsDB == nil {
		logf("[markets] saveFxHistory 跳过：marketsDB 未绑定")
		return
	}
	if len(list) == 0 {
		return
	}
	hk := hourKey()
	now := time.Now().UnixMilli()
	for _, x := range list {
		marketsDB.Exec(`INSERT INTO m_fx_history(hour_key,code,rate,created_at)
			VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE rate=VALUES(rate), created_at=VALUES(created_at)`,
			hk, x.Code, x.Rate, now)
	}
}

func saveOilHistory(crude []crudeItem, prov []provinceOil) {
	if marketsDB == nil {
		return
	}
	hk := hourKey()
	now := time.Now().UnixMilli()
	for _, c := range crude {
		marketsDB.Exec(`INSERT INTO m_oil_history(hour_key,item_key,price,created_at)
			VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE price=VALUES(price), created_at=VALUES(created_at)`,
			hk, c.Key, c.Price, now)
	}
	for _, p := range prov {
		marketsDB.Exec(`INSERT INTO m_oil_prov_history(hour_key,region,p92,p95,p0,created_at)
			VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE p92=VALUES(p92),p95=VALUES(p95),p0=VALUES(p0),created_at=VALUES(created_at)`,
			hk, p.Region, p.P92, p.P95, p.P0, now)
	}
}

func httpGetBody(url string, timeout time.Duration) ([]byte, error) {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) chaomi/1.0")
	cl := &http.Client{Timeout: timeout}
	resp, err := cl.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 8<<20))
}

// ===== 汇率 =====

type fxItem struct {
	Code      string  `json:"code"`
	Name      string  `json:"name"`
	Rate      float64 `json:"rate"`      // 1 人民币 = rate 该外币
	Per100    float64 `json:"per100"`    // 100 人民币 = per100 该外币（便于阅读小币值）
	ChangePct float64 `json:"changePct"` // 相对上一交易日 %
	Flag      string  `json:"flag,omitempty"`
}

// 主要货币（含离岸人民币 CNH），顺序即展示顺序。
// ★ 基准是**在岸人民币 CNY**：所有价格都是「1 元人民币兑换多少该货币」。
var fxMeta = []struct{ Code, Name, Flag string }{
	{"CNY", "在岸人民币", "🇨🇳"},
	{"USD", "美元", "🇺🇸"},
	{"EUR", "欧元", "🇪🇺"},
	{"JPY", "日元", "🇯🇵"},
	{"GBP", "英镑", "🇬🇧"},
	{"HKD", "港元", "🇭🇰"},
	{"CNH", "离岸人民币", "🌐"},
	{"KRW", "韩元", "🇰🇷"},
	{"SGD", "新加坡元", "🇸🇬"},
	{"AUD", "澳元", "🇦🇺"},
	{"CAD", "加元", "🇨🇦"},
	{"CHF", "瑞士法郎", "🇨🇭"},
	{"TWD", "新台币", "🇨🇳"},
	{"MOP", "澳门元", "🇨🇳"},
	{"THB", "泰铢", "🇹🇭"},
	{"MYR", "林吉特", "🇲🇾"},
	{"RUB", "卢布", "🇷🇺"},
	{"INR", "印度卢比", "🇮🇳"},
	{"BRL", "雷亚尔", "🇧🇷"},
	{"AED", "迪拉姆", "🇦🇪"},
	{"SAR", "里亚尔", "🇸🇦"},
	{"NZD", "新西兰元", "🇳🇿"},
}

func fetchUsdRates(datePath string) (map[string]float64, error) {
	u := "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@" + datePath +
		"/v1/currencies/usd.json"
	b, err := httpGetBody(u, 20*time.Second)
	if err != nil {
		return nil, err
	}
	var root map[string]json.RawMessage
	if err := json.Unmarshal(b, &root); err != nil {
		return nil, err
	}
	raw, ok := root["usd"]
	if !ok {
		return nil, fmt.Errorf("响应缺少 usd 字段")
	}
	var m map[string]float64
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, err
	}
	return m, nil
}

func buildFx() (interface{}, error) {
	// 最新价与昨日价**并行**取（原来串行两个 jsDelivr 请求）
	type fres struct {
		m   map[string]float64
		err error
	}
	curCh := make(chan fres, 1)
	prevCh := make(chan fres, 1)
	go func() {
		m, e := fetchUsdRates("latest")
		curCh <- fres{m, e}
	}()
	go func() {
		m, e := fetchUsdRates(time.Now().UTC().AddDate(0, 0, -1).Format("2006-01-02"))
		prevCh <- fres{m, e}
	}()

	cf := <-curCh
	if cf.err != nil {
		return nil, cf.err
	}
	cur := cf.m
	prev := map[string]float64{}
	if pf := <-prevCh; pf.err == nil {
		prev = pf.m
	}

	cnyPerUsd, ok := cur["cny"]
	if !ok || cnyPerUsd <= 0 {
		return nil, fmt.Errorf("汇率数据异常")
	}

	list := make([]fxItem, 0, len(fxMeta))
	for _, m := range fxMeta {
		code := strings.ToLower(m.Code)

		// ★ 基准：在岸人民币 CNY。
		//   cur[code] = 1 USD 兑多少该币；cur["cny"] = 1 USD 兑多少 CNY。
		//   所以 1 CNY 兑该币 = cur[code] / cur["cny"]。
		var rate float64
		if code == "cny" {
			rate = 1
		} else {
			r, ok := cur[code]
			if !ok || r <= 0 {
				continue
			}
			rate = r / cnyPerUsd
		}

		chg := 0.0
		if code != "cny" {
			if pr, ok2 := prev[code]; ok2 && pr > 0 {
				if pcny, ok3 := prev["cny"]; ok3 && pcny > 0 {
					prevRate := pr / pcny
					if prevRate > 0 {
						chg = (rate - prevRate) / prevRate * 100
					}
				}
			}
		}

		// 小币值货币（日元/韩元等）用更高精度，否则全是 0.00
		digits := 4
		if rate >= 100 {
			digits = 2
		} else if rate >= 1 {
			digits = 4
		} else {
			digits = 6
		}

		list = append(list, fxItem{
			Code: m.Code, Name: m.Name, Flag: m.Flag,
			Rate:      roundTo(rate, digits),
			Per100:    roundTo(rate*100, 2),
			ChangePct: roundTo(chg, 3),
		})
	}

	// 离岸 vs 在岸价差（市场关注点）
	spread := 0.0
	if cnh, ok1 := cur["cnh"]; ok1 && cnh > 0 {
		spread = roundTo(cnyPerUsd/cnh-1, 4) * 100 // 相对在岸的偏离 %
	}

	return map[string]interface{}{
		"base":      "CNY",
		"unit":      "1 人民币 = ? 外币",
		"list":      list,
		"cnhSpread": roundTo(spread, 3), // 离岸相对在岸偏离（%）
		"updatedAt": time.Now().UnixMilli(),
	}, nil
}

// buildFxAndSave 抓取并落历史
func buildFxAndSave() (interface{}, error) {
	v, err := buildFx()
	if err != nil {
		return nil, err
	}
	m, ok := v.(map[string]interface{})
	if !ok {
		logf("[markets] fx 结果类型异常，跳过落库")
		return v, nil
	}
	lst, ok2 := m["list"].([]fxItem)
	if !ok2 {
		logf("[markets] fx list 类型异常，跳过落库")
		return v, nil
	}
	saveFxHistory(lst)
	return v, nil
}

func roundTo(v float64, n int) float64 {
	p := 1.0
	for i := 0; i < n; i++ {
		p *= 10
	}
	return float64(int64(v*p+sign(v)*0.5)) / p
}

// handleFx GET /api/fx
func handleFx(w http.ResponseWriter, r *http.Request) {
	v, stale, err := fxCache.get(buildFxAndSave)
	if err != nil {
		writeJSON(w, 502, map[string]string{"error": "汇率获取失败：" + err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{
		"code":  0,
		"data":  v,
		"stale": stale,
	})
}

// StartMarketsPrewarm 启动预热 + 缓存保活 + 历史采样。
//
// 三个职责分开（之前混在一起，导致历史表一直没数据）：
//   1) 预热：启动后先把三个接口的缓存填满，用户首个请求就是 20ms 级
//   2) 保活：TTL 过半提前刷新，避免"刚好过期→用户等抓取"
//   3) 采样：独立定时器每 10 分钟把当前值落一条历史（与缓存是否命中无关）
func StartMarketsPrewarm() {
	go func() {
		time.Sleep(2 * time.Second)
		warm := func(name string, c *cachedBlob, build func() (interface{}, error)) {
			start := time.Now()
			if _, _, err := c.get(build); err != nil {
				logf("[markets] %s 预热失败: %v", name, err)
				return
			}
			logf("[markets] %s 预热完成 %dms", name, time.Since(start).Milliseconds())
		}
		warm("汇率", fxCache, buildFxAndSave)
		warm("油价", oilCache, buildOilAndSave)
		warm("台风", typhoonCache, buildTyphoon)

		// 2) 缓存保活
		go func() {
			tk := time.NewTicker(2 * time.Minute)
			defer tk.Stop()
			for range tk.C {
				if time.Since(cacheAt(fxCache)) > fxCache.ttl/2 {
					fxCache.get(buildFxAndSave)
				}
				if time.Since(cacheAt(oilCache)) > oilCache.ttl/2 {
					oilCache.get(buildOilAndSave)
				}
				if time.Since(cacheAt(typhoonCache)) > typhoonCache.ttl/2 {
					typhoonCache.get(buildTyphoon)
				}
			}
		}()

		// 3) 历史采样（独立于缓存命中与否）
		go func() {
			time.Sleep(1 * time.Second)
			sample := func() {
				if marketsDB == nil {
					return
				}
				if v, _, err := fxCache.get(buildFxAndSave); err == nil {
					if m, ok := v.(map[string]interface{}); ok {
						if lst, ok2 := m["list"].([]fxItem); ok2 {
							saveFxHistory(lst)
						}
					}
				}
				if v, _, err := oilCache.get(buildOilAndSave); err == nil {
					if m, ok := v.(map[string]interface{}); ok {
						if c, ok2 := m["crude"].([]crudeItem); ok2 {
							p, _ := m["domestic"].([]provinceOil)
							saveOilHistory(c, p)
						}
					}
				}
			}
			sample()
			tk := time.NewTicker(10 * time.Minute)
			defer tk.Stop()
			for range tk.C {
				sample()
			}
		}()
	}()
}

// cacheAt 读缓存时间（只读，不触发抓取）
func cacheAt(c *cachedBlob) time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.at
}

// ===== 油价 =====

type crudeItem struct {
	Key       string  `json:"key"`
	Name      string  `json:"name"`
	Price     float64 `json:"price"`
	Unit      string  `json:"unit"`
	ChangePct float64 `json:"changePct"`
}

type provinceOil struct {
	Region string  `json:"region"`
	P92    float64 `json:"p92"`
	P95    float64 `json:"p95"`
	P0     float64 `json:"p0"`
}

var crudeMeta = []struct{ Sym, Key, Name, Unit string }{
	{"BZ=F", "brent", "布伦特原油", "USD/桶"},
	{"CL=F", "wti", "WTI 原油", "USD/桶"},
	{"NG=F", "natgas", "亨利港天然气", "USD/MMBtu"},
	{"RB=F", "rbob", "RBOB 汽油", "USD/加仑"},
}

func fetchYahooQuote(sym string) (price, chg float64, err error) {
	u := "https://query1.finance.yahoo.com/v8/finance/chart/" + sym + "?range=5d&interval=1d"
	b, err := httpGetBody(u, 15*time.Second)
	if err != nil {
		return 0, 0, err
	}
	var res struct {
		Chart struct {
			Result []struct {
				Meta struct {
					RegularMarketPrice         float64 `json:"regularMarketPrice"`
					RegularMarketChangePercent float64 `json:"regularMarketChangePercent"`
				} `json:"meta"`
			} `json:"result"`
		} `json:"chart"`
	}
	if err := json.Unmarshal(b, &res); err != nil {
		return 0, 0, err
	}
	if len(res.Chart.Result) == 0 {
		return 0, 0, fmt.Errorf("无报价")
	}
	m := res.Chart.Result[0].Meta
	return m.RegularMarketPrice, m.RegularMarketChangePercent, nil
}

// 抓 icauto 全国各省零售油价
func fetchProvinceOil() ([]provinceOil, error) {
	b, err := httpGetBody("https://www.icauto.com.cn/oil/", 20*time.Second)
	if err != nil {
		return nil, err
	}
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(string(b)))
	if err != nil {
		return nil, err
	}
	num := func(s string) float64 {
		s = strings.TrimSpace(strings.TrimSuffix(s, "元"))
		v, err := strconv.ParseFloat(s, 64)
		if err != nil || v <= 0 {
			return 0
		}
		return v
	}
	out := []provinceOil{}
	doc.Find("tr").Each(func(i int, s *goquery.Selection) {
		tds := s.Find("td")
		if tds.Length() < 7 {
			return
		}
		region := strings.TrimSpace(tds.Eq(0).Text())
		if region == "" || region == "地区" || len([]rune(region)) > 8 {
			return
		}
		// 表头顺序：地区 | 90# | 92# | 93# | 95# | 97# | 0#柴油
		rec := provinceOil{
			Region: region,
			P92:    num(tds.Eq(2).Text()),
			P95:    num(tds.Eq(4).Text()),
			P0:     num(tds.Eq(6).Text()),
		}
		if rec.P92 <= 0 && rec.P95 <= 0 {
			return
		}
		out = append(out, rec)
	})
	if len(out) == 0 {
		return nil, fmt.Errorf("未解析到省份油价")
	}
	return out, nil
}

func buildOil() (interface{}, error) {
	// ★ 四个原油品种**并行**抓（原来串行：4 个 Yahoo 请求一个个等，冷启动要 3-4 秒）
	type qres struct {
		item crudeItem
		ok   bool
	}
	ch := make(chan qres, len(crudeMeta))
	for _, m := range crudeMeta {
		go func(sym, key, name, unit string) {
			p, c, err := fetchYahooQuote(sym)
			if err != nil {
				ch <- qres{ok: false}
				return
			}
			ch <- qres{ok: true, item: crudeItem{
				Key: key, Name: name, Unit: unit,
				Price: roundTo(p, 2), ChangePct: roundTo(c, 3),
			}}
		}(m.Sym, m.Key, m.Name, m.Unit)
	}

	// 国内油价同时并行抓
	type pres struct {
		list []provinceOil
		err  error
	}
	pch := make(chan pres, 1)
	go func() {
		l, e := fetchProvinceOil()
		pch <- pres{l, e}
	}()

	crude := make([]crudeItem, 0, len(crudeMeta))
	for i := 0; i < len(crudeMeta); i++ {
		if r := <-ch; r.ok {
			crude = append(crude, r.item)
		}
	}
	if len(crude) == 0 {
		return nil, fmt.Errorf("原油报价全部失败")
	}

	pr := <-pch
	prov := pr.list
	perr := pr.err
	if perr != nil {
		prov = []provinceOil{}
	}

	return map[string]interface{}{
		"crude":       crude,
		"domestic":    prov,
		"domesticErr": func() string { if perr != nil { return perr.Error() }; return "" }(),
		"updatedAt":   time.Now().UnixMilli(),
		"source":      "Yahoo Finance / icauto",
	}, nil
}

// buildOilAndSave 抓取并落历史
func buildOilAndSave() (interface{}, error) {
	v, err := buildOil()
	if err != nil {
		return nil, err
	}
	if m, ok := v.(map[string]interface{}); ok {
		if c, ok2 := m["crude"].([]crudeItem); ok2 {
			p, _ := m["domestic"].([]provinceOil)
			saveOilHistory(c, p)
		}
	}
	return v, nil
}

// handleOil GET /api/oil
func handleOil(w http.ResponseWriter, r *http.Request) {
	v, stale, err := oilCache.get(buildOilAndSave)
	if err != nil {
		writeJSON(w, 502, map[string]string{"error": "油价获取失败：" + err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": v,
		"stale": stale,
	})
}

// ===== 台风 =====

type typhoonItem struct {
	ID     string `json:"id"`
	NameEN string `json:"nameEn"`
	NameCN string `json:"nameCn"`
	Num    string `json:"num"`
	Status string `json:"status"` // start=活跃 stop=已停编
	Active bool   `json:"active"`
}

var reJSONP = regexp.MustCompile(`(?s)^[^(]*\(\((.*)\)\)\s*;?\s*$`)

func buildTyphoon() (interface{}, error) {
	b, err := httpGetBody("http://typhoon.nmc.cn/weatherservice/typhoon/jsons/list_default", 20*time.Second)
	if err != nil {
		return nil, err
	}
	raw := strings.TrimSpace(string(b))
	// JSONP 包裹：typhoon_jsons_list_default(({...}))
	m := reJSONP.FindStringSubmatch(raw)
	if m == nil {
		// 兜底：直接截取最外层 {}
		i, j := strings.Index(raw, "{"), strings.LastIndex(raw, "}")
		if i >= 0 && j > i {
			raw = raw[i : j+1]
		}
	} else {
		raw = m[1]
	}

	var root struct {
		TyphoonList []json.RawMessage `json:"typhoonList"`
	}
	if err := json.Unmarshal([]byte(raw), &root); err != nil {
		return nil, fmt.Errorf("台风数据解析失败")
	}

	list := []typhoonItem{}
	active := []typhoonItem{}
	for _, r := range root.TyphoonList {
		var arr []interface{}
		if json.Unmarshal(r, &arr) != nil || len(arr) < 8 {
			continue
		}
		str := func(i int) string {
			if i >= len(arr) || arr[i] == nil {
				return ""
			}
			return strings.TrimSpace(fmt.Sprint(arr[i]))
		}
		it := typhoonItem{
			ID: str(0), NameEN: str(1), NameCN: str(2),
			Num: str(4), Status: str(7),
		}
		it.Active = it.Status == "start"
		list = append(list, it)
		if it.Active {
			active = append(active, it)
		}
	}

	return map[string]interface{}{
		"active":    active,
		"all":       list,
		"embedUrl":  "https://typhoon.nmc.cn/web.html",
		"source":    "中央气象台",
		"updatedAt": time.Now().UnixMilli(),
	}, nil
}

// handleTyphoon GET /api/typhoon
func handleTyphoon(w http.ResponseWriter, r *http.Request) {
	v, stale, err := typhoonCache.get(buildTyphoon)
	if err != nil {
		writeJSON(w, 502, map[string]string{"error": "台风数据获取失败：" + err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": v,
		"stale": stale,
	})
}

// ===== 历史走势查询 =====

type seriesPoint struct {
	At int64   `json:"at"`
	V  float64 `json:"v"`
}

// handleFxHistory GET /api/fx/history?code=USD&days=7
//
// 返回按小时聚合的走势。刚上线的表数据少，会自动缩短到已有的点，
// 前端按实际长度画线，不会出现断线或空白。
func handleFxHistory(w http.ResponseWriter, r *http.Request) {
	if marketsDB == nil {
		writeJSON(w, 200, map[string]interface{}{"code": 0, "data": []seriesPoint{}})
		return
	}
	code := strings.ToUpper(strings.TrimSpace(r.URL.Query().Get("code")))
	if code == "" {
		code = "USD"
	}
	days := 7
	if v := r.URL.Query().Get("days"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 90 {
			days = n
		}
	}
	since := time.Now().AddDate(0, 0, -days).UnixMilli()

	rows, err := marketsDB.Query(`SELECT hour_key,rate FROM m_fx_history
		WHERE code=? AND hour_key>=? ORDER BY hour_key ASC`, code, since)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()
	out := []seriesPoint{}
	for rows.Next() {
		var p seriesPoint
		if rows.Scan(&p.At, &p.V) == nil {
			out = append(out, p)
		}
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"code": code, "days": days, "points": out,
		},
	})
}

// handleOilHistory GET /api/oil/history?key=brent&days=7
// provision：key 形如 brent/wti/natgas/rbob（国际），或 "prov:北京"（省份 92#）
func handleOilHistory(w http.ResponseWriter, r *http.Request) {
	if marketsDB == nil {
		writeJSON(w, 200, map[string]interface{}{"code": 0, "data": []seriesPoint{}})
		return
	}
	key := strings.TrimSpace(r.URL.Query().Get("key"))
	if key == "" {
		key = "brent"
	}
	days := 7
	if v := r.URL.Query().Get("days"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 90 {
			days = n
		}
	}
	since := time.Now().AddDate(0, 0, -days).UnixMilli()

	out := []seriesPoint{}
	if strings.HasPrefix(key, "prov:") {
		region := strings.TrimPrefix(key, "prov:")
		rows, err := marketsDB.Query(`SELECT hour_key,p92 FROM m_oil_prov_history
			WHERE region=? AND hour_key>=? ORDER BY hour_key ASC`, region, since)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": err.Error()})
			return
		}
		defer rows.Close()
		for rows.Next() {
			var p seriesPoint
			if rows.Scan(&p.At, &p.V) == nil {
				out = append(out, p)
			}
		}
	} else {
		rows, err := marketsDB.Query(`SELECT hour_key,price FROM m_oil_history
			WHERE item_key=? AND hour_key>=? ORDER BY hour_key ASC`, key, since)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": err.Error()})
			return
		}
		defer rows.Close()
		for rows.Next() {
			var p seriesPoint
			if rows.Scan(&p.At, &p.V) == nil {
				out = append(out, p)
			}
		}
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"key": key, "days": days, "points": out,
		},
	})
}
