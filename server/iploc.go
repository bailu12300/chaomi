package main

// ---------------- IP 归属地 ----------------
//
// 目标：帖子 / 评论显示「北京市」「广东省」这类归属地，**不显示完整 IP**（隐私）。
//
// 策略：
//   · 查询走在线接口，结果按 IP 永久缓存（内存 + 落库），同一个 IP 只查一次。
//   · 查询超时 1.2 秒，失败就返回空（前端显示「未知」），
//     **绝不因为归属地查询失败而让发帖/评论失败**。
//   · 内网 / 回环地址直接标记「内网」，不发外部请求。
//
// 为什么不在服务端内置离线库：
//   服务器网络受限，公开的 qqwry / ip2region 镜像都下载不到；
//   宝塔自带的 ip_city.ipdb 是私有二进制格式，无法可靠解析。

import (
	"encoding/json"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// 查询结果缓存：ip -> 归属地（"" 表示查过但没有结果，避免反复重试）
var ipLocCache sync.Map

var ipHTTPClient = &http.Client{Timeout: 1200 * time.Millisecond}

type ipAPIReply struct {
	Status     string `json:"status"`
	Country    string `json:"country"`
	RegionName string `json:"regionName"`
	City       string `json:"city"`
}

// ipLocation 返回用于展示的归属地短名，如「北京」「广东」「日本」。
// 返回空串表示无法识别，调用方按「未知」处理。
func ipLocation(ip string) string {
	ip = strings.TrimSpace(ip)
	if ip == "" {
		return ""
	}
	if v, ok := ipLocCache.Load(ip); ok {
		return v.(string)
	}

	loc := resolveIPLoc(ip)
	ipLocCache.Store(ip, loc)
	return loc
}

func resolveIPLoc(ipStr string) string {
	pip := net.ParseIP(ipStr)
	if pip == nil {
		return ""
	}
	if pip.IsLoopback() || pip.IsPrivate() || pip.IsLinkLocalUnicast() ||
		pip.IsLinkLocalMulticast() || pip.IsUnspecified() {
		return "内网"
	}

	url := "http://ip-api.com/json/" + ipStr + "?lang=zh-CN&fields=status,country,regionName,city"
	resp, err := ipHTTPClient.Get(url)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()

	var r ipAPIReply
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return ""
	}
	if r.Status != "success" {
		return ""
	}
	return normalizeLoc(r.Country, r.RegionName, r.City)
}

// normalizeLoc 把「中国 / 北京市 / 北京」这类组合整理成展示用短名
func normalizeLoc(country, region, city string) string {
	c := strings.TrimSpace(country)
	reg := strings.TrimSpace(region)
	ct := strings.TrimSpace(city)

	// 境外：只显示国家
	if c != "" && c != "中国" && !strings.Contains(c, "中国") {
		if strings.Contains(c, "局域网") || strings.Contains(c, "内网") {
			return "内网"
		}
		return shortName(c)
	}

	src := reg
	if src == "" {
		src = ct
	}
	if src == "" {
		return ""
	}
	return shortName(src)
}

// shortName 截短行政区名：「广东省」→「广东」，「北京市」→「北京」
func shortName(s string) string {
	s = strings.ReplaceAll(s, "中国", "")
	s = strings.ReplaceAll(s, "特别行政区", "")
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}

	// 直辖市 / 特别行政区
	for _, m := range []string{"北京", "上海", "天津", "重庆", "香港", "澳门", "台湾"} {
		if strings.Contains(s, m) {
			return m
		}
	}
	// 省份：截到「省/市/自治区」之前
	if i := strings.IndexAny(s, "省市"); i > 0 {
		head := s[:i]
		for _, suf := range []string{"壮族", "回族", "维吾尔"} {
			head = strings.ReplaceAll(head, suf, "")
		}
		if head != "" {
			return head
		}
	}
	if i := strings.Index(s, "自治区"); i > 0 {
		return s[:i]
	}
	// 其它（如「内蒙古」「黑龙江」本身就是短名）
	r := []rune(s)
	if len(r) > 8 {
		return string(r[:8])
	}
	return s
}
