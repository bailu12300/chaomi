package main

// ---------------- 节点监控：延迟探测 / 心跳 ----------------
// 客户端每 3 秒打一次 /api/ping（每分钟 20 次），服务端返回自身时间戳，
// 客户端用「往返时间 - 服务端处理时间」估算单程延迟。
// /api/node 返回节点信息（区域、负载、在线时长），供 App 的监控页展示。

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"runtime"
	"strings"
	"sync"
	"time"
)

var nodeBootAt = time.Now()

// nodeStats 心跳聚合（内存态，够用且不写库）
type nodeStats struct {
	mu        sync.Mutex
	total     int64
	lastAt    int64
	lastIP    string
	latSumMs  int64
	latCount  int64
	latMinMs  int64
	latMaxMs  int64
	perMinute map[string]int // "2026-10-26 14:24" -> count
}

var nodeStat = &nodeStats{latMinMs: -1, perMinute: map[string]int{}}

func (s *nodeStats) record(ip string, rttMs int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.total++
	s.lastAt = time.Now().UnixMilli()
	s.lastIP = ip
	if rttMs >= 0 {
		s.latSumMs += rttMs
		s.latCount++
		if s.latMinMs < 0 || rttMs < s.latMinMs {
			s.latMinMs = rttMs
		}
		if rttMs > s.latMaxMs {
			s.latMaxMs = rttMs
		}
	}
	k := time.Now().Format("2006-01-02 15:04")
	s.perMinute[k]++
	// 只留最近 60 分钟，避免无限增长
	if len(s.perMinute) > 90 {
		var oldest string
		for kk := range s.perMinute {
			if oldest == "" || kk < oldest {
				oldest = kk
			}
		}
		delete(s.perMinute, oldest)
	}
}

func (s *nodeStats) snapshot() map[string]interface{} {
	s.mu.Lock()
	defer s.mu.Unlock()
	avg := int64(0)
	if s.latCount > 0 {
		avg = s.latSumMs / s.latCount
	}
	cur := s.perMinute[time.Now().Format("2006-01-02 15:04")]
	// 最近 20 分钟的心跳序列，前端可画小柱状图
	series := make([]map[string]interface{}, 0, 20)
	now := time.Now()
	for i := 19; i >= 0; i-- {
		t := now.Add(-time.Duration(i) * time.Minute)
		k := t.Format("2006-01-02 15:04")
		series = append(series, map[string]interface{}{
			"minute": k[11:],
			"count":  s.perMinute[k],
		})
	}
	return map[string]interface{}{
		"total":       s.total,
		"lastAt":      s.lastAt,
		"lastIp":      s.lastIP,
		"latAvgMs":    avg,
		"latMinMs":    s.latMinMs,
		"latMaxMs":    s.latMaxMs,
		"perMinute":   cur,
		"expectPerMin": 20,
		"series":      series,
	}
}

// handlePing 心跳端点。故意做成极轻：不查库、不加锁以外的操作。
func handlePing(w http.ResponseWriter, r *http.Request) {
	t0 := time.Now()
	ip := r.RemoteAddr
	if i := strings.LastIndex(ip, ":"); i > 0 {
		ip = ip[:i]
	}
	nodeStat.record(ip, -1)

	now := time.Now().UnixMilli()
	resp := map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"serverTime": now,
			"costMs":     time.Since(t0).Milliseconds(),
			"bootAt":     nodeBootAt.UnixMilli(),
		},
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(resp)
}

// handlePingReport 客户端把测得的 RTT 回传，服务端据此统计延迟分布
func handlePingReport(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	var b struct {
		RTT       int64  `json:"rtt"`
		Region    string `json:"region"`
		Network   string `json:"network"`
	}
	_ = json.NewDecoder(r.Body).Decode(&b)
	ip := r.RemoteAddr
	if i := strings.LastIndex(ip, ":"); i > 0 {
		ip = ip[:i]
	}
	if b.RTT > 0 && b.RTT < 60000 {
		nodeStat.record(ip, b.RTT)
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": true})
}

// handleNodeInfo 节点信息：区域、版本、负载、在线时长、心跳统计
func handleNodeInfo(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)
	up := time.Since(nodeBootAt)

	// 数据库往返也测一下，这是 App↔服务器之外最容易出问题的一环
	dbMs := int64(-1)
	if db != nil {
		t := time.Now()
		if err := db.Ping(); err == nil {
			dbMs = time.Since(t).Milliseconds()
		}
	}

	var hwCount int
	_ = db.QueryRow(`SELECT COUNT(*) FROM jd_latest`).Scan(&hwCount)
	var postCount int
	_ = db.QueryRow(`SELECT COUNT(*) FROM c_posts`).Scan(&postCount)

	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"name":       "超米主节点",
			"region":     "cn-east",
			"host":       r.Host,
			"version":    "2.1.0",
			"uptimeSec":  int64(up.Seconds()),
			"bootAt":     nodeBootAt.UnixMilli(),
			"goVersion":  runtime.Version(),
			"goroutines": runtime.NumGoroutine(),
			"memAllocMb": mem.Alloc / 1024 / 1024,
			"memSysMb":   mem.Sys / 1024 / 1024,
			"dbPingMs":   dbMs,
			"hwCount":    hwCount,
			"postCount":  postCount,
			"heartbeat":  nodeStat.snapshot(),
			"serverTime": time.Now().UnixMilli(),
		},
	})
}
