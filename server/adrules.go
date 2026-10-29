package main

// ---------------- 去广告规则库（服务端下发） ----------------
//
// 为什么放服务端：
//   1) 规则要能随时更新，不用重发 APK
//   2) App 只负责执行，规则维护集中在一处
//   3) 可以按需下发「摇一摇公告」这类容易误触的拦截策略
//
// 客户端拉取 /api/adrules → 合并进本地规则库（用户自己的启停状态保留）

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// adAction 与客户端 SkipRule.Action 字段一一对应
type adAction struct {
	Matches  []string  `json:"matches,omitempty"`
	ViewIds  []string  `json:"viewIds,omitempty"`
	Action   string    `json:"action,omitempty"`
	Position []float64 `json:"position,omitempty"`
	DelayMs  int       `json:"delayMs,omitempty"`
}

// adRule 与客户端 SkipRule 字段一一对应
type adRule struct {
	ID           string     `json:"id"`
	Name         string     `json:"name"`
	Pkg          string     `json:"pkg"`
	ActivityIds  []string   `json:"activityIds,omitempty"`
	ExcludeActs  []string   `json:"excludeActivityIds,omitempty"`
	Rules        []adAction `json:"rules"`
	Enabled      bool       `json:"enabled"`
	Source       string     `json:"source"`
	AppLabel     string     `json:"appLabel,omitempty"`
	Note         string     `json:"note,omitempty"`
}

// 规则库版本：内容变更时递增，客户端据此判断是否需要重拉
const adRulesVersion = 5

var adRulesUpdatedAt = time.Now().UnixMilli()

// adRulesBuiltin 服务端下发的规则库
//
// ★ 只保留「跳过」类，一条都不含 × / 关闭 / 取消 / 我知道了。
//
//   之前这里塞了大量通用关闭词，客户端拿到后会在正常界面乱点
//   （用户反馈"只要有 × 就会异常点击"）。服务器下发比内置更危险：
//   它会覆盖到所有已安装机器，所以更不能放宽泛的词。
//
//   现在的原则：**服务端只存「跳过」**。这类词是广告专属信号，
//   在任何 App 里含义一致，下发出去不会造成误触。
func adRulesBuiltin() []adRule {
	common := func(id, name, pkg string, matches []string, note string) adRule {
		return adRule{
			ID: id, Name: name, Pkg: pkg, Enabled: true, Source: "server",
			Note: note,
			Rules: []adAction{{
				Matches: matches, Action: "click",
			}},
		}
	}

	out := []adRule{
		// 唯一一条通配规则：全局「跳过」。
		//
		// 为什么只要一条：所有「跳过 XXX」的变体都包含「跳过」二字，
		// 匹配时用 contains 就能覆盖，不需要为「跳过片头」「跳过广告」各建一条。
		// 多条通配规则反而会重复点击同一个按钮。
		common("srv.skip.universal", "通用 · 跳过广告", "*",
			[]string{
				"跳过", "跳過",
				"skip", "skip ad", "skip ads", "skipad",
			},
			"广告专属词，误触风险极低；不含 × / 关闭 等通用按钮"),
	}

	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

// handleAdRules GET /api/adrules?version=N&app=1
// 客户端本地已有同版本时返回 304 语义（changed=false，不下发正文，省流量）
func handleAdRules(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	ver := 0
	if v := r.URL.Query().Get("version"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			ver = n
		}
	}
	want := strings.TrimSpace(r.URL.Query().Get("pkg"))

	all := adRulesBuiltin()
	if want != "" {
		var filtered []adRule
		for _, x := range all {
			if x.Pkg == "*" || x.Pkg == want ||
				(strings.HasSuffix(x.Pkg, ".*") && strings.HasPrefix(want, strings.TrimSuffix(x.Pkg, "*"))) {
				filtered = append(filtered, x)
			}
		}
		all = filtered
	}

	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"version":   adRulesVersion,
			"changed":   ver != adRulesVersion,
			"updatedAt": adRulesUpdatedAt,
			"count":     len(all),
			"rules":     all,
			// 摇一摇抑制策略：客户端据此开关传感器拦截
			"shake": map[string]interface{}{
				"enabled": true,
				// 开屏期间屏蔽加速度计多久（毫秒）
				"windowMs":  6000,
				// 判定阈值：加速度变化超过该值算「摇」
				"threshold": 2.6,
				"note":      "开屏/公告页出现时抑制加速度传感器，避免摇一摇跳转",
			},
			"serverTime": time.Now().UnixMilli(),
		},
	})
}

// handleAdRulesReport POST /api/adrules/report
// 客户端上报命中情况，便于后续优化规则（无鉴权，只做聚合统计）
var adRuleHits struct {
	mu   sync.Mutex
	data map[string]int
	last map[string]int64
}

func adRuleHit(ruleID string) {
	adRuleHits.mu.Lock()
	defer adRuleHits.mu.Unlock()
	if adRuleHits.data == nil {
		adRuleHits.data = map[string]int{}
		adRuleHits.last = map[string]int64{}
	}
	adRuleHits.data[ruleID]++
	adRuleHits.last[ruleID] = time.Now().UnixMilli()
}

func handleAdRuleReport(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	var body struct {
		RuleID string `json:"ruleId"`
		Pkg    string `json:"pkg"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	if body.RuleID != "" {
		adRuleHit(body.RuleID)
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": true})
}

// handleAdRuleStats GET /api/adrules/stats 命中统计（供自己排查规则质量）
func handleAdRuleStats(w http.ResponseWriter, r *http.Request) {
	adRuleHits.mu.Lock()
	defer adRuleHits.mu.Unlock()
	out := make([]map[string]interface{}, 0, len(adRuleHits.data))
	for k, v := range adRuleHits.data {
		out = append(out, map[string]interface{}{
			"ruleId": k, "hits": v, "lastAt": adRuleHits.last[k],
		})
	}
	sort.Slice(out, func(i, j int) bool {
		return out[i]["hits"].(int) > out[j]["hits"].(int)
	})
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": out})
}
