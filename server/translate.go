package main

// ---------------- 翻译调度 ----------------
//
// 实际翻译实现见 translate_local.go（本地词典，零网络依赖）。
// 本文件只负责并发调度与批量补翻。
//
// 历史说明：曾用过 Google 免费端点 + MyMemory，
// 但前者被限流（返回 Sorry 页面）、后者免费额度一天耗尽，
// 在线方案不可靠，已全部改为本地词典。

import (
	"database/sql"
	"log"
	"sync"
)

const translateWorkers = 4

// translatePending 并发翻译新增条目
func translatePending(db *sql.DB, ids []int64) {
	if len(ids) == 0 {
		return
	}
	jobs := make(chan int64, len(ids))
	for _, id := range ids {
		jobs <- id
	}
	close(jobs)

	var wg sync.WaitGroup
	for i := 0; i < translateWorkers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for id := range jobs {
				translateOneLocal(db, id)
			}
		}()
	}
	wg.Wait()
}

// backfillTranslate 补齐历史未翻译条目（服务重启后跑一次）
func backfillTranslate(db *sql.DB) {
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM rss_items
		WHERE (title_zh IS NULL OR title_zh='') AND lang<>'zh'`).Scan(&n)
	if n == 0 {
		return
	}
	log.Printf("[translate] 补翻 %d 条历史资讯（本地词典）", n)
	translateAllPendingLocal(db)
}

// isMostlyChinese 判断是否以中文为主（CJK 占比 > 30%）
func isMostlyChinese(s string) bool {
	if s == "" {
		return false
	}
	total, cjk := 0, 0
	for _, r := range s {
		if r == ' ' || r == '\n' || r == '\t' {
			continue
		}
		total++
		if r >= 0x4E00 && r <= 0x9FFF {
			cjk++
		}
	}
	if total == 0 {
		return false
	}
	return float64(cjk)/float64(total) > 0.3
}
