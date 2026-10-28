package main

// ---------------- RSS 资讯聚合 ----------------
//
// 设计要点（对应需求）：
//   1. RSS 是一级菜单，二级分类固定 5 个：数码 / AI / 能源 / 国际 / 科技
//   2. 每个二级分类**最多保留 200 条**，超了按发布时间删最旧的（FIFO）
//   3. 客户端刷新**每次拉 5~15 条**（游标分页，不重复不遗漏）
//   4. 高效：并发拉取 + guid 去重 + 批量入库 + 单事务裁剪
//   5. 服务器主动访问原文/图片，客户端只拿结构化数据（不改直连第三方）
//   6. **离线翻译**：入库时异步翻译标题/摘要并落库，
//      客户端拿到的就是中文，断网也能看——不依赖客户端联网调翻译接口
//
// 为什么翻译放服务端：
//   同一篇文章只翻一次（全站共享），比每个用户各翻一次省几个数量级；
//   而且翻译结果永久缓存，老文章不用重复翻。

import (
	"crypto/sha1"
	"database/sql"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"io"
	"log"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ===== 分类定义（一级菜单） =====

type rssCategory struct {
	Key   string `json:"key"`
	Name  string `json:"name"`
	Feeds []rssFeed
}

type rssFeed struct {
	Name string
	URL  string
	Lang string // zh / en
}

// 每类保留条数上限
const rssMaxPerCategory = 200

// 客户端单次刷新上限
const rssMaxPageSize = 15

// 手动刷新节流（5 分钟一次，防止被刷）
var lastRSSManual time.Time
var rssManualMu sync.Mutex

// tryLockRSSManual 抢手动刷新锁，成功返回 true
func tryLockRSSManual() bool {
	rssManualMu.Lock()
	defer rssManualMu.Unlock()
	if time.Since(lastRSSManual) < 5*time.Minute {
		return false
	}
	lastRSSManual = time.Now()
	return true
}

var rssCategories = []rssCategory{
	{
		Key: "digital", Name: "数码",
		Feeds: []rssFeed{
			{"IT之家", "https://www.ithome.com/rss/", "zh"},
			{"少数派", "https://sspai.com/feed", "zh"},
			{"爱范儿", "https://www.ifanr.com/feed", "zh"},
			{"数字尾巴", "https://www.dgtle.com/rss/dgtle.xml", "zh"},
			{"Engadget", "https://www.engadget.com/rss.xml", "en"},
		},
	},
	{
		Key: "ai", Name: "AI",
		Feeds: []rssFeed{
			{"量子位", "https://www.qbitai.com/feed", "zh"},
			{"DeepMind", "https://deepmind.google/blog/rss.xml", "en"},
			{"Hugging Face", "https://huggingface.co/blog/feed.xml", "en"},
			{"Google AI", "https://blog.google/technology/ai/rss/", "en"},
		},
	},
	{
		Key: "energy", Name: "能源",
		Feeds: []rssFeed{
			{"OilPrice", "https://oilprice.com/rss/main", "en"},
			{"pv-magazine", "https://www.pv-magazine.com/feed/", "en"},
			{"Energy Storage", "https://www.energy-storage.news/feed/", "en"},
			{"InsideEVs", "https://insideevs.com/rss/articles/all/", "en"},
			{"CleanTechnica", "https://cleantechnica.com/feed/", "en"},
		},
	},
	{
		Key: "world", Name: "国际",
		Feeds: []rssFeed{
			{"BBC 中文", "https://feeds.bbci.co.uk/zhongwen/simp/rss.xml", "zh"},
			{"BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml", "en"},
			{"NYT World", "https://rss.nytimes.com/services/xml/rss/nyt/World.xml", "en"},
			{"Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "en"},
			{"NPR World", "https://feeds.npr.org/1004/rss.xml", "en"},
		},
	},
	{
		Key: "tech", Name: "科技",
		Feeds: []rssFeed{
			{"IT之家", "https://www.ithome.com/rss/", "zh"},
			{"开源中国", "https://www.oschina.net/news/rss", "zh"},
			{"雷锋网", "https://www.leiphone.com/feed", "zh"},
			{"Solidot", "https://www.solidot.org/index.rss", "zh"},
			{"TechCrunch", "https://techcrunch.com/feed/", "en"},
			{"Hacker News", "https://news.ycombinator.com/rss", "en"},
		},
	},
}

// ===== 数据结构 =====

type rssItem struct {
	ID        int64  `json:"id"`
	Category  string `json:"category"`
	Title     string `json:"title"`
	TitleZh   string `json:"titleZh,omitempty"`
	Link      string `json:"link"`
	Summary   string `json:"summary,omitempty"`
	SummaryZh string `json:"summaryZh,omitempty"`
	Source    string `json:"source"`
	Image     string `json:"image,omitempty"`
	Lang      string `json:"lang"`
	PubAt     int64  `json:"pubAt"`
	FetchedAt int64  `json:"fetchedAt"`
}

// ===== 表结构 =====

func ensureRSSSchema(db *sql.DB) error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS rss_items(
			id BIGINT AUTO_INCREMENT PRIMARY KEY,
			category VARCHAR(32) NOT NULL,
			guid CHAR(40) NOT NULL,
			title VARCHAR(512) DEFAULT '',
			title_zh VARCHAR(512) DEFAULT '',
			link VARCHAR(1024) DEFAULT '',
			summary MEDIUMTEXT,
			summary_zh MEDIUMTEXT,
			source VARCHAR(64) DEFAULT '',
			image VARCHAR(1024) DEFAULT '',
			lang VARCHAR(8) DEFAULT 'zh',
			pub_at BIGINT DEFAULT 0,
			fetched_at BIGINT DEFAULT 0,
			UNIQUE KEY uk_guid(guid),
			INDEX idx_cat_pub(category, pub_at)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			return err
		}
	}
	return nil
}

// ===== RSS / Atom 解析 =====

type rssXML struct {
	Channel struct {
		Items []struct {
			Title       string `xml:"title"`
			Link        string `xml:"link"`
			Description string `xml:"description"`
			PubDate     string `xml:"pubDate"`
			Date        string `xml:"date"`
			GUID        string `xml:"guid"`
			Enclosure   struct {
				URL  string `xml:"url,attr"`
				Type string `xml:"type,attr"`
			} `xml:"enclosure"`
			Content struct {
				URL string `xml:"url,attr"`
			} `xml:"content"`
		} `xml:"item"`
	} `xml:"channel"`
	// Atom
	Entries []struct {
		Title     string `xml:"title"`
		Link      []struct {
			Href string `xml:"href,attr"`
			Rel  string `xml:"rel,attr"`
		} `xml:"link"`
		Summary   string `xml:"summary"`
		Content   string `xml:"content"`
		Published string `xml:"published"`
		Updated   string `xml:"updated"`
		ID        string `xml:"id"`
	} `xml:"entry"`
}

// rawItem 解析出的原始条目（未入库）
type rawItem struct {
	Title   string
	Link    string
	Summary string
	PubAt   int64
	Image   string
	Lang    string
	Source  string
}

var rssHTTP = &http.Client{Timeout: 15 * time.Second}

// fetchFeed 拉取并解析单个 RSS/Atom 源
func fetchFeed(f rssFeed) []rawItem {
	req, err := http.NewRequest("GET", f.URL, nil)
	if err != nil {
		return nil
	}
	// 部分源要求 UA，否则 403
	req.Header.Set("User-Agent",
		"Mozilla/5.0 (compatible; ChaomiBot/1.0; +https://cm01.zy1080.xyz)")
	req.Header.Set("Accept", "application/rss+xml, application/xml, text/xml, */*")

	resp, err := rssHTTP.Do(req)
	if err != nil {
		log.Printf("[rss] %s 拉取失败: %v", f.Name, err)
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		log.Printf("[rss] %s HTTP %d", f.Name, resp.StatusCode)
		return nil
	}
	// 限制 4MB，防止异常源打爆内存
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil
	}

	var doc rssXML
	// ★ 很多中文源输出的 XML 不合规（裸 & 未转义、标签未闭合），
	//   Go 的严格解析器会直接报错。先做一次轻度清洗再解析。
	cleaned := sanitizeXML(body)
	if err := xml.Unmarshal(cleaned, &doc); err != nil {
		// 仍失败则退回宽松方式：用正则逐条抽取
		out := parseFallback(body, f)
		if len(out) > 0 {
			log.Printf("[rss] %s 严格解析失败(%v)，宽松模式取到 %d 条", f.Name, err, len(out))
		} else {
			log.Printf("[rss] %s 解析失败: %v", f.Name, err)
		}
		return out
	}

	var out []rawItem
	// RSS 2.0
	for _, it := range doc.Channel.Items {
		link := strings.TrimSpace(it.Link)
		if link == "" {
			continue
		}
		img := strings.TrimSpace(it.Enclosure.URL)
		if img == "" {
			img = strings.TrimSpace(it.Content.URL)
		}
		if img == "" {
			img = firstImageIn(it.Description)
		}
		out = append(out, rawItem{
			Title:   cleanText(it.Title),
			Link:    link,
			Summary: excerpt(it.Description, 220),
			PubAt:   parseRSSDate(rssFirstNonEmpty(it.PubDate, it.Date)),
			Image:   img,
			Lang:    f.Lang,
			Source:  f.Name,
		})
	}
	// Atom
	for _, e := range doc.Entries {
		var link string
		for _, l := range e.Link {
			if l.Rel == "" || l.Rel == "alternate" {
				link = strings.TrimSpace(l.Href)
				break
			}
		}
		if link == "" {
			continue
		}
		body := e.Summary
		if body == "" {
			body = e.Content
		}
		out = append(out, rawItem{
			Title:   cleanText(e.Title),
			Link:    link,
			Summary: excerpt(body, 220),
			PubAt:   parseRSSDate(rssFirstNonEmpty(e.Published, e.Updated)),
			Image:   firstImageIn(body),
			Lang:    f.Lang,
			Source:  f.Name,
		})
	}
	return out
}

func rssFirstNonEmpty(vs ...string) string {
	for _, v := range vs {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

// ===== XML 容错 =====

var (
	// Go 的 regexp 是 RE2，不支持负向断言 (?!...)，
	// 所以不能用「& 后面不是实体」这种写法，改成显式列全部合法实体。
	// 策略：先把已有合法实体临时替换成占位符，再把剩下的裸 & 全部转义，最后还原。
	entityRe   = regexp.MustCompile(`&(?:amp|lt|gt|quot|apos|#\d+|#x[0-9A-Fa-f]+);`)
	bareAmpRe  = regexp.MustCompile(`&`)
	ctrlRe     = regexp.MustCompile(`[\x00-\x08\x0B\x0C\x0E-\x1F]`)
	selfCloseRe = regexp.MustCompile(`<(br|hr|img|input|meta|link)([^>]*[^/>])>`)
)

// sanitizeXML 轻度清洗非法 XML，让严格解析器能过。
// 只做安全替换，不改动真实内容结构。
func sanitizeXML(b []byte) []byte {
	s := string(b)
	s = ctrlRe.ReplaceAllString(s, "")

	// 1) 合法实体先抽出来占位，避免被二次转义
	var stash []string
	s = entityRe.ReplaceAllStringFunc(s, func(m string) string {
		stash = append(stash, m)
		return "\x01ENT" + itoa(len(stash)-1) + "\x01"
	})
	// 2) 剩下的 & 都是裸 &，全部转义
	s = bareAmpRe.ReplaceAllString(s, "&amp;")
	// 3) 还原合法实体
	for i, m := range stash {
		s = strings.Replace(s, "\x01ENT"+itoa(i)+"\x01", m, 1)
	}
	// 4) 自闭合标签补 /
	s = selfCloseRe.ReplaceAllString(s, "<$1$2/>")
	return []byte(s)
}

func itoa(n int) string {
	return strconv.Itoa(n)
}

// parseFallback 正则兜底解析：从畸形 XML 里硬抽 <item>
func parseFallback(b []byte, f rssFeed) []rawItem {
	s := string(b)
	var out []rawItem

	itemRe := regexp.MustCompile(`(?is)<item[^>]*>(.*?)</item>`)
	titleRe := regexp.MustCompile(`(?is)<title[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</title>`)
	linkRe := regexp.MustCompile(`(?is)<link[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</link>`)
	descRe := regexp.MustCompile(`(?is)<description[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</description>`)
	dateRe := regexp.MustCompile(`(?is)<pubDate[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</pubDate>`)

	for _, m := range itemRe.FindAllStringSubmatch(s, 60) {
		block := m[1]
		pick := func(re *regexp.Regexp) string {
			if mm := re.FindStringSubmatch(block); len(mm) > 1 {
				return strings.TrimSpace(mm[1])
			}
			return ""
		}
		link := pick(linkRe)
		title := pick(titleRe)
		if link == "" || title == "" {
			continue
		}
		desc := pick(descRe)
		out = append(out, rawItem{
			Title:   cleanText(title),
			Link:    link,
			Summary: excerpt(desc, 220),
			PubAt:   parseRSSDate(pick(dateRe)),
			Image:   firstImageIn(desc),
			Lang:    f.Lang,
			Source:  f.Name,
		})
	}
	return out
}

var (
	tagRe    = regexp.MustCompile(`<[^>]+>`)
	spaceRe  = regexp.MustCompile(`\s+`)
	imgSrcRe = regexp.MustCompile(`<img[^>]+src=["']([^"']+)["']`)
)

// cleanText 去掉 HTML 标签与多余空白
func cleanText(s string) string {
	s = htmlUnescape(s)
	s = tagRe.ReplaceAllString(s, "")
	s = spaceRe.ReplaceAllString(s, " ")
	return strings.TrimSpace(s)
}

func htmlUnescape(s string) string {
	r := strings.NewReplacer(
		"&amp;", "&", "&lt;", "<", "&gt;", ">", "&quot;", `"`,
		"&#39;", "'", "&apos;", "'", "&nbsp;", " ", "&#160;", " ",
	)
	return r.Replace(s)
}

// excerpt 取正文摘要（纯文本，限长）
func excerpt(s string, max int) string {
	t := cleanText(s)
	r := []rune(t)
	if len(r) <= max {
		return t
	}
	return string(r[:max]) + "…"
}

// firstImageIn 从 HTML 里取第一张图
func firstImageIn(s string) string {
	m := imgSrcRe.FindStringSubmatch(s)
	if len(m) > 1 {
		return strings.TrimSpace(m[1])
	}
	return ""
}

var rssDateLayouts = []string{
	time.RFC1123Z, time.RFC1123, time.RFC822Z, time.RFC822,
	"2006-01-02T15:04:05Z07:00", "2006-01-02T15:04:05Z",
	"2006-01-02 15:04:05", "2006-01-02", time.RFC3339,
}

func parseRSSDate(s string) int64 {
	s = strings.TrimSpace(s)
	if s == "" {
		return time.Now().UnixMilli()
	}
	for _, l := range rssDateLayouts {
		if t, err := time.Parse(l, s); err == nil {
			return t.UnixMilli()
		}
	}
	return time.Now().UnixMilli()
}

// ===== 入库（去重 + 裁剪到 200 条） =====

func guidOf(link string) string {
	h := sha1.Sum([]byte(link))
	return hex.EncodeToString(h[:])
}

// saveRSSItems 批量入库，返回新增条数。
// 去重靠 uk_guid（INSERT IGNORE），裁剪靠单条 DELETE 子查询。
func saveRSSItems(db *sql.DB, cat string, items []rawItem) (int, []int64) {
	if len(items) == 0 {
		return 0, nil
	}
	now := time.Now().UnixMilli()
	added := 0
	var newIDs []int64

	tx, err := db.Begin()
	if err != nil {
		return 0, nil
	}
	stmt, err := tx.Prepare(`INSERT IGNORE INTO rss_items
		(category,guid,title,link,summary,source,image,lang,pub_at,fetched_at)
		VALUES(?,?,?,?,?,?,?,?,?,?)`)
	if err != nil {
		tx.Rollback()
		return 0, nil
	}
	for _, it := range items {
		if it.Title == "" || it.Link == "" {
			continue
		}
		res, err := stmt.Exec(cat, guidOf(it.Link), truncRunes(it.Title, 500), it.Link,
			truncRunes(it.Summary, 2000), it.Source, it.Image, it.Lang,
			it.PubAt, now)
		if err != nil {
			continue
		}
		if n, _ := res.RowsAffected(); n > 0 {
			added++
			if id, _ := res.LastInsertId(); id > 0 {
				newIDs = append(newIDs, id)
			}
		}
	}
	stmt.Close()

	// 裁剪：只保留该类最新 200 条（按发布时间，其次按 id）
	_, err = tx.Exec(`DELETE FROM rss_items
		WHERE category=? AND id NOT IN (
			SELECT id FROM (
				SELECT id FROM rss_items WHERE category=?
				ORDER BY pub_at DESC, id DESC LIMIT ?
			) keep
		)`, cat, cat, rssMaxPerCategory)
	if err != nil {
		log.Printf("[rss] 裁剪 %s 失败: %v", cat, err)
	}
	if err := tx.Commit(); err != nil {
		return 0, nil
	}
	return added, newIDs
}

func truncRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}

// ===== 拉取调度 =====

// refreshRSS 并发拉取全部分类，全部完成后统一入库
func refreshRSS(db *sql.DB) (int, int) {
	totalNew, totalSeen := 0, 0

	type result struct {
		cat   string
		items []rawItem
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	var results []result

	for _, c := range rssCategories {
		for _, f := range c.Feeds {
			wg.Add(1)
			go func(cat string, feed rssFeed) {
				defer wg.Done()
				items := fetchFeed(feed)
				if len(items) == 0 {
					return
				}
				mu.Lock()
				results = append(results, result{cat: cat, items: items})
				mu.Unlock()
			}(c.Key, f)
		}
	}
	wg.Wait()

	// 按分类聚合后入库
	byCat := map[string][]rawItem{}
	for _, r := range results {
		byCat[r.cat] = append(byCat[r.cat], r.items...)
		totalSeen += len(r.items)
	}
	var newIDs []int64
	for cat, items := range byCat {
		// 同类内按发布时间倒序，保证裁剪时保留最新
		sort.SliceStable(items, func(i, j int) bool { return items[i].PubAt > items[j].PubAt })
		n, ids := saveRSSItems(db, cat, items)
		totalNew += n
		newIDs = append(newIDs, ids...)
	}

	// 新条目异步翻译（不阻塞拉取）
	if len(newIDs) > 0 {
		go translatePending(db, newIDs)
	}
	return totalNew, totalSeen
}

// startRSSLoop 定时拉取：启动即拉一次，之后每 15 分钟一轮
func startRSSLoop(db *sql.DB) {
	go func() {
		time.Sleep(5 * time.Second) // 让服务先起来
		for {
			n, seen := refreshRSS(db)
			log.Printf("[rss] 拉取完成：新增 %d 条（抓取 %d）", n, seen)
			time.Sleep(15 * time.Minute)
		}
	}()
}

// ===== HTTP 接口 =====

// handleRSSCats 一级菜单：分类 + 各分类当前条数
func handleRSSCats(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	type catOut struct {
		Key   string `json:"key"`
		Name  string `json:"name"`
		Count int    `json:"count"`
	}
	out := make([]catOut, 0, len(rssCategories))
	for _, c := range rssCategories {
		var n int
		_ = db.QueryRow(`SELECT COUNT(*) FROM rss_items WHERE category=?`, c.Key).Scan(&n)
		out = append(out, catOut{Key: c.Key, Name: c.Name, Count: n})
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{"cats": out, "maxPerCat": rssMaxPerCategory},
	})
}

// handleRSSItems 游标分页：before=上一页最后一条的 pubAt（首次不传）
//
// 客户端刷新拿 5~15 条，服务端强约束上限 15，防止一次拉爆。
func handleRSSItems(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	cat := strings.TrimSpace(r.URL.Query().Get("cat"))
	limit := 10
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := parseIntSafe(v); err == nil && n > 0 {
			limit = n
		}
	}
	if limit > rssMaxPageSize {
		limit = rssMaxPageSize
	}
	var before int64
	if v := r.URL.Query().Get("before"); v != "" {
		if n, err := parseInt64Safe(v); err == nil {
			before = n
		}
	}

	var rows *sql.Rows
	var err error
	if cat == "" || cat == "all" {
		if before > 0 {
			rows, err = db.Query(`SELECT id,category,title,title_zh,link,summary,summary_zh,
				source,image,lang,pub_at,fetched_at FROM rss_items
				WHERE pub_at < ? ORDER BY pub_at DESC, id DESC LIMIT ?`, before, limit)
		} else {
			rows, err = db.Query(`SELECT id,category,title,title_zh,link,summary,summary_zh,
				source,image,lang,pub_at,fetched_at FROM rss_items
				ORDER BY pub_at DESC, id DESC LIMIT ?`, limit)
		}
	} else {
		if before > 0 {
			rows, err = db.Query(`SELECT id,category,title,title_zh,link,summary,summary_zh,
				source,image,lang,pub_at,fetched_at FROM rss_items
				WHERE category=? AND pub_at < ? ORDER BY pub_at DESC, id DESC LIMIT ?`,
				cat, before, limit)
		} else {
			rows, err = db.Query(`SELECT id,category,title,title_zh,link,summary,summary_zh,
				source,image,lang,pub_at,fetched_at FROM rss_items
				WHERE category=? ORDER BY pub_at DESC, id DESC LIMIT ?`, cat, limit)
		}
	}
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()

	list := make([]rssItem, 0, limit)
	for rows.Next() {
		var it rssItem
		var titleZh, summaryZh, summary sql.NullString
		if err := rows.Scan(&it.ID, &it.Category, &it.Title, &titleZh, &it.Link,
			&summary, &summaryZh, &it.Source, &it.Image, &it.Lang,
			&it.PubAt, &it.FetchedAt); err != nil {
			continue
		}
		it.TitleZh = titleZh.String
		it.Summary = summary.String
		it.SummaryZh = summaryZh.String
		list = append(list, it)
	}

	nextBefore := int64(0)
	if len(list) > 0 {
		nextBefore = list[len(list)-1].PubAt
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"list":       list,
			"nextBefore": nextBefore,
			"hasMore":    len(list) == limit,
		},
	})
}

func parseIntSafe(s string) (int, error) {
	var n int
	_, err := fmt.Sscanf(strings.TrimSpace(s), "%d", &n)
	return n, err
}

func parseInt64Safe(s string) (int64, error) {
	var n int64
	_, err := fmt.Sscanf(strings.TrimSpace(s), "%d", &n)
	return n, err
}
