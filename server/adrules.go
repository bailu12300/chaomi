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
const adRulesVersion = 4

var adRulesUpdatedAt = time.Now().UnixMilli()

// adRulesBuiltin 服务端内置的通用规则
// 设计原则：只放「高置信度」的，宁可漏跳也不要误点
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
		// ---- 通用开屏 ----
		common("srv.open.skip", "开屏 · 跳过按钮", "*",
			[]string{"跳过", "跳过广告", "跳过此广告", "跳过开屏", "跳过 5", "skip", "skip ad"},
			"绝大多数开屏广告的跳过键"),
		common("srv.open.close", "开屏 · 关闭符号", "*",
			[]string{"×", "✕", "✖", "╳", "✗", "⨯", "❌", "❎", "关闭"},
			"纯符号关闭按钮"),
		common("srv.popup.close", "弹窗 · 关闭", "*",
			[]string{"我知道了", "知道了", "以后再说", "暂不更新", "稍后再说",
				"残忍拒绝", "放弃更新", "不同意", "下次再说", "取消"},
			"公告/更新/评分/隐私弹窗"),

		// ---- ★ 摇一摇公告拦截 ----
		// 摇一摇广告靠传感器触发跳转，不能靠点「关闭」，必须点它的「取消/关闭」
		// 并在客户端侧同时屏蔽传感器。这里下发文本规则，客户端另有传感器抑制。
		common("srv.shake.dismiss", "摇一摇 · 关闭入口", "*",
			[]string{"摇一摇", "摇动手机", "扭一扭", "摇一摇跳转", "点击跳过", "跳过并关闭"},
			"识别摇一摇广告的关闭/跳过入口"),

		// ---- 信息流广告 ----
		common("srv.feed.ad", "信息流 · 广告标记", "*",
			[]string{"广告", "赞助", "推广", "AD"},
			"信息流里的广告标识（仅在能点到 × 时生效）"),
	}

	// 视频类片头
	out = append(out, adRule{
		ID: "srv.video.pre", Name: "视频 · 跳过片头", Pkg: "com.qiyi.video",
		Enabled: true, Source: "server", AppLabel: "爱奇艺",
		Rules: []adAction{
			{Matches: []string{"跳过片头", "跳过广告", "关闭广告"}, Action: "click"},
		},
	})
	out = append(out, adRule{
		ID: "srv.video.pre.tx", Name: "视频 · 跳过片头", Pkg: "com.tencent.qqlive",
		Enabled: true, Source: "server", AppLabel: "腾讯视频",
		Rules: []adAction{
			{Matches: []string{"跳过片头", "跳过广告"}, Action: "click"},
			{ViewIds: []string{"iv_close_ad", "btn_skip"}, Action: "click"},
		},
	})
	out = append(out, adRule{
		ID: "srv.video.pre.bili", Name: "视频 · 跳过片头", Pkg: "tv.danmaku.bili",
		Enabled: true, Source: "server", AppLabel: "哔哩哔哩",
		Rules: []adAction{
			{Matches: []string{"跳过"}, Action: "click"},
		},
	})

	// 电商开屏（这些最常有摇一摇）
	for _, e := range []struct{ pkg, label string }{
		{"com.taobao.taobao", "淘宝"},
		{"com.jingdong.app.mall", "京东"},
		{"com.xunmeng.pinduoduo", "拼多多"},
		{"com.taobao.idlefish", "闲鱼"},
		{"com.achievo.vipshop", "唯品会"},
	} {
		out = append(out, adRule{
			ID: "srv.open." + e.pkg, Name: "开屏 · " + e.label, Pkg: e.pkg,
			Enabled: true, Source: "server", AppLabel: e.label,
			Note: "含摇一摇公告，客户端会同时抑制传感器",
			Rules: []adAction{
				{Matches: []string{"跳过", "跳过广告", "关闭", "×", "✕"}, Action: "click"},
				// 摇一摇类：右下角常见「取消」
				{Matches: []string{"取消", "关闭广告", "不感兴趣"}, Action: "click"},
			},
		})
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
