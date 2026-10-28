package main

// ---------------- 第三方服务巡检 ----------------
//
// 需求：监控 DeepSeek / OpenAI / Cloudflare 等外部服务是否正常，
//       异常时通过飞书 + 邮箱通知。
//
// 设计要点：
//   · **连续失败才告警**：单次抖动不报警，否则半夜会被吵醒
//   · **恢复通知**：从故障回到正常也发一次，避免「不知道好了没」
//   · **告警去重**：同一服务故障期间只发一次，不重复轰炸
//   · 巡检结果落库，前端可看历史与当前状态
//   · 失败不影响主服务（每个探针独立 goroutine + 超时）

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"
)

// ===== 被监控目标 =====

type watchTarget struct {
	Key      string // 唯一标识
	Name     string // 展示名
	URL      string
	Method   string // GET / POST
	Body     string // POST 体
	Expect   []int  // 可接受的 HTTP 状态码
	Keyword  string // 返回体必须包含的关键词（空则不检查）
	Timeout  int    // 秒
	Critical bool   // 关键服务（故障时优先告警）
}

// 监控清单。新增服务只需往这里加一行。
//
// ★ 关于 401 / 403：
//   这两个码说明**服务器本身有响应**，只是缺少鉴权或被 Cloudflare 挑战页拦截。
//   对「服务是否在线」这个目标，它们应该算正常。
//   真正的故障是：连接超时、DNS 失败、5xx、或返回体内容不符预期。
var watchTargets = []watchTarget{
	// ---- AI 服务 ----
	{
		Key: "deepseek", Name: "DeepSeek",
		URL: "https://api.deepseek.com/v1/models", Method: "GET",
		// 401/403 都说明服务在线（只是没带 key / 被 CDN 挑战页拦）
		Expect: []int{200, 401, 403},
		Timeout: 12, Critical: true,
	},
	{
		Key: "deepseek-web", Name: "DeepSeek 网页版",
		URL: "https://chat.deepseek.com", Method: "GET",
		Expect: []int{200, 301, 302, 403}, Timeout: 12, Critical: true,
	},
	{
		Key: "openai", Name: "OpenAI API",
		URL: "https://api.openai.com/v1/models", Method: "GET",
		Expect: []int{200, 401, 403}, Timeout: 12, Critical: true,
	},
	{
		Key: "anthropic", Name: "Anthropic API",
		URL: "https://api.anthropic.com/v1/models", Method: "GET",
		Expect: []int{200, 401, 403}, Timeout: 12,
	},
	{
		Key: "gemini", Name: "Google Gemini",
		URL: "https://generativelanguage.googleapis.com/v1beta/models", Method: "GET",
		Expect: []int{200, 400, 403}, Timeout: 12,
	},
	{
		Key: "huggingface", Name: "Hugging Face",
		URL: "https://huggingface.co/api/models?limit=1", Method: "GET",
		Expect: []int{200}, Timeout: 12,
	},

	// ---- 基础设施 ----
	{
		Key: "cloudflare", Name: "Cloudflare",
		URL: "https://www.cloudflare.com/cdn-cgi/trace", Method: "GET",
		Expect: []int{200}, Keyword: "h=", Timeout: 10, Critical: true,
	},
	{
		Key: "cloudflare-dns", Name: "Cloudflare DNS 1.1.1.1",
		URL: "https://1.1.1.1/dns-query?name=example.com&type=A",
		Method: "GET", Expect: []int{200, 400}, Timeout: 10,
	},
	{
		Key: "github-api", Name: "GitHub API",
		URL: "https://api.github.com/rate_limit", Method: "GET",
		Expect: []int{200}, Keyword: "rate", Timeout: 10,
	},
	{
		Key: "github-web", Name: "GitHub 网站",
		URL: "https://github.com", Method: "GET",
		Expect: []int{200}, Timeout: 12,
	},

	// ---- 常用开放接口（本项目依赖） ----
	{
		Key: "open-meteo", Name: "Open-Meteo 天气",
		URL: "https://api.open-meteo.com/v1/forecast?latitude=39.9&longitude=116.4&current=temperature_2m",
		Method: "GET", Expect: []int{200}, Keyword: "current", Timeout: 12, Critical: true,
	},
	{
		Key: "ip-api", Name: "ip-api 定位",
		URL: "http://ip-api.com/json/8.8.8.8?fields=status", Method: "GET",
		Expect: []int{200}, Keyword: "success", Timeout: 10,
	},
	{
		Key: "er-api", Name: "汇率接口 er-api",
		URL: "https://open.er-api.com/v6/latest/USD", Method: "GET",
		Expect: []int{200}, Keyword: "rates", Timeout: 12,
	},

	// ---- 国内大站（可用性基准） ----
	{
		Key: "baidu", Name: "百度",
		URL: "https://www.baidu.com", Method: "GET",
		Expect: []int{200}, Timeout: 10,
	},
	{
		Key: "taobao", Name: "淘宝",
		URL: "https://www.taobao.com", Method: "GET",
		Expect: []int{200, 302}, Timeout: 10,
	},
	{
		Key: "qq", Name: "腾讯",
		URL: "https://www.qq.com", Method: "GET",
		Expect: []int{200}, Timeout: 10,
	},
	{
		Key: "weibo", Name: "微博",
		URL: "https://weibo.com", Method: "GET",
		Expect: []int{200, 302, 403}, Timeout: 10,
	},
	{
		Key: "bilibili", Name: "哔哩哔哩",
		URL: "https://www.bilibili.com", Method: "GET",
		Expect: []int{200}, Timeout: 10,
	},
	{
		Key: "zhihu", Name: "知乎",
		URL: "https://www.zhihu.com", Method: "GET",
		Expect: []int{200, 403}, Timeout: 10,
	},

	// ---- 国际大站 ----
	{
		Key: "google", Name: "Google",
		URL: "https://www.google.com", Method: "GET",
		Expect: []int{200}, Timeout: 10,
	},
	{
		Key: "youtube", Name: "YouTube",
		URL: "https://www.youtube.com", Method: "GET",
		Expect: []int{200}, Timeout: 10,
	},
	{
		Key: "x", Name: "X (Twitter)",
		URL: "https://x.com", Method: "GET",
		Expect: []int{200, 302, 403}, Timeout: 10,
	},
	{
		Key: "wikipedia", Name: "维基百科",
		URL: "https://zh.wikipedia.org/wiki/Wikipedia", Method: "GET",
		Expect: []int{200}, Timeout: 10,
	},
	{
		Key: "aws", Name: "AWS",
		URL: "https://aws.amazon.com", Method: "GET",
		Expect: []int{200, 301, 302}, Timeout: 10,
	},
	{
		Key: "azure", Name: "Azure",
		URL: "https://azure.microsoft.com", Method: "GET",
		Expect: []int{200, 301, 302}, Timeout: 10,
	},
	{
		Key: "vercel", Name: "Vercel",
		URL: "https://vercel.com", Method: "GET",
		Expect: []int{200}, Timeout: 10,
	},
	{
		Key: "npm", Name: "npm 仓库",
		URL: "https://registry.npmjs.org/-/ping", Method: "GET",
		Expect: []int{200}, Timeout: 10,
	},
	{
		Key: "dockerhub", Name: "Docker Hub",
		URL: "https://hub.docker.com/v2/repositories/library/ubuntu/", Method: "GET",
		Expect: []int{200}, Timeout: 10,
	},

	// ---- 内容源抽样（RSS 是否可达） ----
	{
		Key: "ithome", Name: "IT之家 RSS",
		URL: "https://www.ithome.com/rss/", Method: "GET",
		Expect: []int{200}, Keyword: "<rss", Timeout: 15,
	},
	{
		Key: "bbc", Name: "BBC World RSS",
		URL: "https://feeds.bbci.co.uk/news/world/rss.xml", Method: "GET",
		Expect: []int{200}, Keyword: "<rss", Timeout: 15,
	},
}

// ===== 结果结构 =====

type watchResult struct {
	Key       string `json:"key"`
	Name      string `json:"name"`
	OK        bool   `json:"ok"`
	Status    int    `json:"status"`
	LatencyMs int64  `json:"latencyMs"`
	Error     string `json:"error,omitempty"`
	CheckedAt int64  `json:"checkedAt"`
	// 连续失败次数（用于决定是否告警）
	FailStreak int `json:"failStreak"`
}

// 告警阈值：连续失败这么多次才通知
const watchFailThreshold = 2

// 巡检间隔：每 15 秒一轮 = 每分钟探测 4 次
//
// 频率取舍：15 秒能及时发现秒级故障，且 15 个目标一轮只需几十毫秒，
// 对本机负载可忽略。再快会显得没必要，也会给外部服务添无意义流量。
const watchInterval = 15 * time.Second

// 保留多少条时序数据（用于画图表）：15 秒 × 2880 = 12 小时
const watchHistoryKeep = 2880

var (
	watchMu     sync.Mutex
	watchState  = map[string]*watchResult{} // key -> 最新结果
	watchAlertd = map[string]bool{}         // 是否已就故障发过告警
)

// ===== 表结构 =====

func ensureWatchSchema(db *sql.DB) error {
	_, err := db.Exec(`CREATE TABLE IF NOT EXISTS watch_status(
		target_key VARCHAR(64) PRIMARY KEY,
		name VARCHAR(128) DEFAULT '',
		ok TINYINT DEFAULT 0,
		status INT DEFAULT 0,
		latency_ms BIGINT DEFAULT 0,
		err_msg VARCHAR(512) DEFAULT '',
		fail_streak INT DEFAULT 0,
		checked_at BIGINT DEFAULT 0
	) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`)
	if err != nil {
		return err
	}
	// 时序表：存每次探测结果，供 PHP 画图表用
	_, err = db.Exec(`CREATE TABLE IF NOT EXISTS watch_history(
		id BIGINT AUTO_INCREMENT PRIMARY KEY,
		target_key VARCHAR(64) NOT NULL,
		ok TINYINT DEFAULT 0,
		status INT DEFAULT 0,
		latency_ms BIGINT DEFAULT 0,
		checked_at BIGINT NOT NULL,
		INDEX idx_wh_key_at(target_key, checked_at)
	) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`)
	if err != nil {
		return err
	}
	_, err = db.Exec(`CREATE TABLE IF NOT EXISTS watch_events(
		id BIGINT AUTO_INCREMENT PRIMARY KEY,
		target_key VARCHAR(64) NOT NULL,
		name VARCHAR(128) DEFAULT '',
		kind VARCHAR(16) NOT NULL,
		detail VARCHAR(512) DEFAULT '',
		created_at BIGINT NOT NULL,
		INDEX idx_we_at(created_at)
	) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`)
	return err
}

// ===== 探针 =====

func probeOnce(t watchTarget) watchResult {
	timeout := t.Timeout
	if timeout <= 0 {
		timeout = 10
	}
	client := &http.Client{Timeout: time.Duration(timeout) * time.Second}

	method := t.Method
	if method == "" {
		method = "GET"
	}
	var body io.Reader
	if method == "POST" && t.Body != "" {
		body = strings.NewReader(t.Body)
	}

	req, err := http.NewRequest(method, t.URL, body)
	if err != nil {
		return watchResult{Key: t.Key, Name: t.Name, Error: "请求构造失败: " + err.Error()}
	}
	req.Header.Set("User-Agent", "chaomi-watch/1.0 (+https://cm01.zy1080.xyz)")
	req.Header.Set("Accept", "*/*")
	if method == "POST" {
		req.Header.Set("Content-Type", "application/json")
	}

	start := time.Now()
	resp, err := client.Do(req)
	lat := time.Since(start).Milliseconds()
	if err != nil {
		msg := err.Error()
		if len(msg) > 200 {
			msg = msg[:200]
		}
		return watchResult{Key: t.Key, Name: t.Name, LatencyMs: lat,
			Error: msg, CheckedAt: time.Now().UnixMilli()}
	}
	defer resp.Body.Close()

	// 只读前 64KB 做关键词判断，避免大响应拖慢
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	text := string(b)

	okStatus := len(t.Expect) == 0
	for _, code := range t.Expect {
		if resp.StatusCode == code {
			okStatus = true
			break
		}
	}
	okKeyword := true
	if t.Keyword != "" {
		okKeyword = strings.Contains(text, t.Keyword)
	}

	r := watchResult{
		Key: t.Key, Name: t.Name,
		OK: okStatus && okKeyword,
		Status: resp.StatusCode, LatencyMs: lat,
		CheckedAt: time.Now().UnixMilli(),
	}
	if !okStatus {
		r.Error = fmt.Sprintf("HTTP %d（期望 %v）", resp.StatusCode, t.Expect)
	} else if !okKeyword {
		r.Error = "响应内容缺少关键标记：" + t.Keyword
	}
	return r
}

// ===== 巡检主循环 =====

func startWatchLoop(db *sql.DB) {
	go func() {
		time.Sleep(12 * time.Second) // 让主服务先稳定
		for {
			runWatchRound(db)
			time.Sleep(watchInterval)
		}
	}()
}

// runWatchRound 跑一轮巡检（并发）
func runWatchRound(db *sql.DB) []watchResult {
	var wg sync.WaitGroup
	results := make([]watchResult, len(watchTargets))

	for i, t := range watchTargets {
		wg.Add(1)
		go func(idx int, tg watchTarget) {
			defer wg.Done()
			results[idx] = probeOnce(tg)
		}(i, t)
	}
	wg.Wait()

	// 更新状态 + 判断是否需要告警
	var alerts []string
	var recoveries []string

	watchMu.Lock()
	for i := range results {
		r := &results[i]
		prev := watchState[r.Key]
		if prev != nil {
			if !r.OK {
				r.FailStreak = prev.FailStreak + 1
			}
		} else if !r.OK {
			r.FailStreak = 1
		}

		if !r.OK {
			// 连续失败达到阈值且还没告过 → 告警
			if r.FailStreak >= watchFailThreshold && !watchAlertd[r.Key] {
				watchAlertd[r.Key] = true
				alerts = append(alerts, formatAlert(*r))
			}
		} else {
			// 从故障恢复 → 发恢复通知
			if watchAlertd[r.Key] {
				watchAlertd[r.Key] = false
				recoveries = append(recoveries, fmt.Sprintf("%s 已恢复正常（%d ms）", r.Name, r.LatencyMs))
			}
		}
		watchState[r.Key] = r
	}
	watchMu.Unlock()

	// 落库：当前状态 + 时序（图表用）
	for _, r := range results {
		saveWatchResult(db, r)
		saveWatchHistory(db, r)
	}
	// 每分钟裁一次旧数据（保留 12 小时），避免表无限增长
	if time.Now().Unix()%60 < int64(watchInterval/time.Second)+1 {
		pruneWatchHistory(db)
	}

	// 通知
	if len(alerts) > 0 {
		msg := "【服务异常】\n" + strings.Join(alerts, "\n\n")
		log.Printf("[watch] %d 个服务异常", len(alerts))
		notifyAll(db, "超米 · 服务异常告警", msg)
		for _, r := range results {
			if !r.OK && r.FailStreak == watchFailThreshold {
				saveWatchEvent(db, r.Key, r.Name, "down", r.Error)
			}
		}
	}
	if len(recoveries) > 0 {
		msg := "【服务恢复】\n" + strings.Join(recoveries, "\n")
		log.Printf("[watch] %d 个服务恢复", len(recoveries))
		notifyAll(db, "超米 · 服务恢复通知", msg)
		for _, r := range results {
			if r.OK {
				saveWatchEvent(db, r.Key, r.Name, "up", "")
			}
		}
	}

	return results
}

func formatAlert(r watchResult) string {
	lat := ""
	if r.LatencyMs > 0 {
		lat = fmt.Sprintf("（%d ms）", r.LatencyMs)
	}
	return fmt.Sprintf("· %s%s\n  原因：%s\n  时间：%s",
		r.Name, lat, r.Error, time.Now().Format("2006-01-02 15:04:05"))
}

func saveWatchResult(db *sql.DB, r watchResult) {
	okVal := 0
	if r.OK {
		okVal = 1
	}
	_, _ = db.Exec(`INSERT INTO watch_status
		(target_key,name,ok,status,latency_ms,err_msg,fail_streak,checked_at)
		VALUES(?,?,?,?,?,?,?,?)
		ON DUPLICATE KEY UPDATE name=VALUES(name),ok=VALUES(ok),status=VALUES(status),
		latency_ms=VALUES(latency_ms),err_msg=VALUES(err_msg),
		fail_streak=VALUES(fail_streak),checked_at=VALUES(checked_at)`,
		r.Key, r.Name, okVal, r.Status, r.LatencyMs, truncRunes(r.Error, 500),
		r.FailStreak, r.CheckedAt)
}

func saveWatchHistory(db *sql.DB, r watchResult) {
	okVal := 0
	if r.OK {
		okVal = 1
	}
	_, _ = db.Exec(`INSERT INTO watch_history(target_key,ok,status,latency_ms,checked_at)
		VALUES(?,?,?,?,?)`, r.Key, okVal, r.Status, r.LatencyMs, r.CheckedAt)
}

// pruneWatchHistory 只保留每个目标最近 watchHistoryKeep 条
func pruneWatchHistory(db *sql.DB) {
	for _, t := range watchTargets {
		_, _ = db.Exec(`DELETE FROM watch_history WHERE target_key=? AND id NOT IN
			(SELECT id FROM (SELECT id FROM watch_history WHERE target_key=?
			 ORDER BY id DESC LIMIT ?) k)`, t.Key, t.Key, watchHistoryKeep)
	}
}

func saveWatchEvent(db *sql.DB, key, name, kind, detail string) {
	_, _ = db.Exec(`INSERT INTO watch_events(target_key,name,kind,detail,created_at)
		VALUES(?,?,?,?,?)`, key, name, kind, truncRunes(detail, 500), time.Now().UnixMilli())
	// 只留最近 500 条事件
	_, _ = db.Exec(`DELETE FROM watch_events WHERE id NOT IN
		(SELECT id FROM (SELECT id FROM watch_events ORDER BY id DESC LIMIT 500) t)`)
}

// notifyAll 同时发飞书和邮件
func notifyAll(db *sql.DB, subject, body string) {
	// 飞书
	go func() {
		if !feishuConfigured() {
			return
		}
		// 飞书用富文本卡片，比纯文本直观
		payload := map[string]interface{}{
			"msg_type": "text",
			"content":  map[string]string{"text": subject + "\n\n" + body},
		}
		b, _ := json.Marshal(payload)
		hook := env("FEISHU_WEBHOOK", "")
		req, err := http.NewRequest("POST", hook, strings.NewReader(string(b)))
		if err != nil {
			return
		}
		req.Header.Set("Content-Type", "application/json")
		cl := &http.Client{Timeout: 10 * time.Second}
		resp, err := cl.Do(req)
		if err == nil && resp != nil {
			resp.Body.Close()
		}
	}()

	// 邮件
	go func() {
		if !smtpConfigured() {
			return
		}
		to := env("ALERT_EMAIL", "")
		if to == "" {
			to = env("SMTP_USER", "")
		}
		if to == "" {
			return
		}
		html := `<div style="font-family:sans-serif;font-size:14px;line-height:1.7">` +
			`<h3 style="margin:0 0 12px">` + htmlEscape(subject) + `</h3>` +
			`<pre style="white-space:pre-wrap;font-family:inherit;margin:0">` +
			htmlEscape(body) + `</pre></div>`
		_ = smtpSend(to, subject, html)
	}()
}

// ===== HTTP 接口 =====

// handleWatchStatus GET /api/watch/status —— 当前状态
func handleWatchStatus(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	// 优先返回内存里的最新结果（比查库快且更新）
	watchMu.Lock()
	list := make([]watchResult, 0, len(watchState))
	for _, v := range watchState {
		list = append(list, *v)
	}
	watchMu.Unlock()

	// 内存为空（刚重启）就用库里的
	if len(list) == 0 {
		rows, err := db.Query(`SELECT target_key,name,ok,status,latency_ms,err_msg,fail_streak,checked_at
			FROM watch_status ORDER BY target_key`)
		if err == nil {
			defer rows.Close()
			for rows.Next() {
				var x watchResult
				var okv int
				if err := rows.Scan(&x.Key, &x.Name, &okv, &x.Status, &x.LatencyMs,
					&x.Error, &x.FailStreak, &x.CheckedAt); err == nil {
					x.OK = okv == 1
					list = append(list, x)
				}
			}
		}
	}

	up, down := 0, 0
	for _, x := range list {
		if x.OK {
			up++
		} else {
			down++
		}
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"list":     list,
			"up":       up,
			"down":     down,
			"interval": int(watchInterval / time.Second),
		},
	})
}

// handleWatchCheck POST /api/watch/check —— 立即巡检一次
func handleWatchCheck(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	fresh := runWatchRound(db)
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": fresh})
}

// handleWatchHistory GET /api/watch/history?key=xxx&points=120
//
// 返回最近 N 个探测点，供图表画折线（延迟曲线 + 在线率）
func handleWatchHistory(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	key := strings.TrimSpace(r.URL.Query().Get("key"))
	points := 120
	if v := r.URL.Query().Get("points"); v != "" {
		if n, err := parseIntSafe(v); err == nil && n > 0 && n <= 2000 {
			points = n
		}
	}

	type pt struct {
		At    int64 `json:"at"`
		OK    bool  `json:"ok"`
		Ms    int64 `json:"ms"`
		Code  int   `json:"code"`
	}

	base := `SELECT checked_at,ok,latency_ms,status FROM watch_history`
	var rows *sql.Rows
	var err error
	if key != "" {
		rows, err = db.Query(base+` WHERE target_key=? ORDER BY id DESC LIMIT ?`, key, points)
	} else {
		// 不传 key 时返回总在线率的聚合（按时间桶）
		rows, err = db.Query(`SELECT checked_at,
			SUM(ok) AS okcnt, COUNT(*) AS total, AVG(latency_ms) AS avgms
			FROM watch_history WHERE checked_at > ?
			GROUP BY checked_at DIV 60000 ORDER BY checked_at DESC LIMIT ?`,
			time.Now().Add(-12*time.Hour).UnixMilli(), points)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": err.Error()})
			return
		}
		defer rows.Close()
		type agg struct {
			At    int64   `json:"at"`
			OK    int     `json:"okCount"`
			Total int     `json:"total"`
			Avg   float64 `json:"avgMs"`
		}
		var out []agg
		for rows.Next() {
			var a agg
			if er := rows.Scan(&a.At, &a.OK, &a.Total, &a.Avg); er == nil {
				out = append(out, a)
			}
		}
		writeJSON(w, 200, map[string]interface{}{"code": 0, "data": out})
		return
	}
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()

	var out []pt
	for rows.Next() {
		var x pt
		var okv int
		if er := rows.Scan(&x.At, &okv, &x.Ms, &x.Code); er == nil {
			x.OK = okv == 1
			out = append(out, x)
		}
	}
	// 反转成时间正序，画图从左到右
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": out})
}

// handleWatchEvents GET /api/watch/events —— 最近告警历史
func handleWatchEvents(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	rows, err := db.Query(`SELECT target_key,name,kind,detail,created_at
		FROM watch_events ORDER BY id DESC LIMIT 100`)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()
	type ev struct {
		Key    string `json:"key"`
		Name   string `json:"name"`
		Kind   string `json:"kind"`
		Detail string `json:"detail"`
		At     int64  `json:"at"`
	}
	var out []ev
	for rows.Next() {
		var e ev
		if err := rows.Scan(&e.Key, &e.Name, &e.Kind, &e.Detail, &e.At); err == nil {
			out = append(out, e)
		}
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": out})
}
