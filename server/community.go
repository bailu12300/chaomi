package main

// ---------------- 社区模块 ----------------
// 新增：文字/图片/链接/公众号图文 帖子、信息流、评论、点赞、WebSocket 实时、
//       公众号图文解析、硬件行情复用（/api/hw/models / /api/hw/status）。

import (
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"math/rand"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
	"github.com/gorilla/websocket"
)

// 社区静态根目录（由 main.go 在 doServe 中赋值），用于图片上传落盘
var communityDir string

// ===== 数据结构 =====

type CPost struct {
	ID           int64    `json:"id"`
	UserID       int64    `json:"userId,omitempty"`
	Type         string   `json:"type"` // text | image | link | article
	Title        string   `json:"title,omitempty"`
	Body         string   `json:"body,omitempty"`
	Cover        string   `json:"cover,omitempty"`
	URL          string   `json:"url,omitempty"`
	Images       []string `json:"images,omitempty"`
	Blocks       []CBlock `json:"blocks,omitempty"` // article 类型的原生块
	Author       string   `json:"author"`
	Tags         []string `json:"tags,omitempty"`
	// 公众号信息（发布时从解析结果带过来，供阅读页展示）
	AccountName   string `json:"accountName,omitempty"`
	AccountAvatar string `json:"accountAvatar,omitempty"`
	PublishTime   int64  `json:"publishTime,omitempty"`
	Likes        int      `json:"likes"`
	Liked        bool     `json:"liked,omitempty"`
	Views        int      `json:"views,omitempty"`
	CommentCount int      `json:"commentCount"`
	IPLoc        string   `json:"ipLoc,omitempty"` // IP 归属地（只存省市，不存完整 IP）
	CreatedAt    int64    `json:"createdAt"`
}

type CComment struct {
	ID        int64  `json:"id"`
	PostID    int64  `json:"postId"`
	UserID    int64  `json:"userId,omitempty"`
	Author    string `json:"author"`
	Body      string `json:"body"`
	CreatedAt int64  `json:"createdAt"`
	IPLoc     string `json:"ipLoc,omitempty"` // IP 归属地（只存省市，不存完整 IP）
	Mine      bool   `json:"mine,omitempty"`
}

type CArticle struct {
	Title   string   `json:"title"`
	Author  string   `json:"author"`
	Cover   string   `json:"cover"`
	Content string   `json:"content"`
	Images  []string `json:"images"`
	URL     string   `json:"url"`
	Summary string   `json:"summary"`
}

// ===== 存储 =====

func ensureCommunitySchema(db *sql.DB) error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS c_posts(
			id BIGINT AUTO_INCREMENT PRIMARY KEY,
			type VARCHAR(16) NOT NULL,
			title VARCHAR(255),
			body MEDIUMTEXT,
			cover VARCHAR(512),
			url VARCHAR(1024),
			images MEDIUMTEXT,
			blocks MEDIUMTEXT,
			author VARCHAR(64),
			tags MEDIUMTEXT,
			account_name VARCHAR(128) DEFAULT '',
			account_avatar VARCHAR(512) DEFAULT '',
			publish_time BIGINT DEFAULT 0,
			user_id BIGINT DEFAULT 0,
			likes INT DEFAULT 0,
			views INT DEFAULT 0,
			created_at BIGINT NOT NULL,
			INDEX idx_cposts_ct(created_at),
			INDEX idx_cposts_uid(user_id)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS c_comments(
			id BIGINT AUTO_INCREMENT PRIMARY KEY,
			post_id BIGINT NOT NULL,
			user_id BIGINT DEFAULT 0,
			author VARCHAR(64),
			body MEDIUMTEXT,
			created_at BIGINT NOT NULL,
			INDEX idx_c_comments_pid(post_id)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			return err
		}
	}
	// 老库补字段（幂等）
	for _, c := range [][3]string{
		{"c_posts", "blocks", "blocks MEDIUMTEXT"},
		{"c_posts", "user_id", "user_id BIGINT DEFAULT 0"},
		{"c_posts", "views", "views INT DEFAULT 0"},
		{"c_posts", "ip_loc", "ip_loc VARCHAR(32) DEFAULT ''"},
		{"c_posts", "ip", "ip VARCHAR(64) DEFAULT ''"},
		{"c_comments", "user_id", "user_id BIGINT DEFAULT 0"},
		{"c_comments", "ip_loc", "ip_loc VARCHAR(32) DEFAULT ''"},
		{"c_comments", "ip", "ip VARCHAR(64) DEFAULT ''"},
	} {
		if err := cAddColumn(db, c[0], c[1], c[2]); err != nil {
			return err
		}
	}
	return nil
}

// cPostColumns 统一的帖子列清单，保证各处扫描顺序一致
const cPostColumns = `id,user_id,type,title,body,cover,url,images,blocks,author,tags,account_name,account_avatar,publish_time,likes,views,ip_loc,created_at`

func cScanPost(scan func(...interface{}) error) (CPost, error) {
	var p CPost
	var images, tags, blocks sql.NullString
	var created int64
	var accName, accAvatar, ipLoc sql.NullString
	var pubTime sql.NullInt64
	err := scan(&p.ID, &p.UserID, &p.Type, &p.Title, &p.Body, &p.Cover, &p.URL,
		&images, &blocks, &p.Author, &tags, &accName, &accAvatar, &pubTime,
		&p.Likes, &p.Views, &ipLoc, &created)
	if err != nil {
		return p, err
	}
	p.CreatedAt = created
	p.IPLoc = ipLoc.String
	p.AccountName = accName.String
	p.AccountAvatar = accAvatar.String
	p.PublishTime = pubTime.Int64
	if images.Valid && images.String != "" {
		_ = json.Unmarshal([]byte(images.String), &p.Images)
	}
	if tags.Valid && tags.String != "" {
		_ = json.Unmarshal([]byte(tags.String), &p.Tags)
	}
	if blocks.Valid && blocks.String != "" {
		_ = json.Unmarshal([]byte(blocks.String), &p.Blocks)
	}
	return p, nil
}

func cCreatePost(db *sql.DB, p CPost) (CPost, error) {
	imgs, _ := json.Marshal(p.Images)
	tags, _ := json.Marshal(p.Tags)
	blk := "[]"
	if len(p.Blocks) > 0 {
		b, _ := json.Marshal(p.Blocks)
		blk = string(b)
	}
	res, err := db.Exec(
		`INSERT INTO c_posts(user_id,type,title,body,cover,url,images,blocks,author,tags,
			account_name,account_avatar,publish_time,ip_loc,created_at)
		 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		p.UserID, p.Type, p.Title, p.Body, p.Cover, p.URL, string(imgs), blk, p.Author,
		string(tags), p.AccountName, p.AccountAvatar, p.PublishTime, p.IPLoc, p.CreatedAt)
	if err != nil {
		return p, err
	}
	id, _ := res.LastInsertId()
	p.ID = id
	p.Likes = 0
	p.Views = 0
	p.CommentCount = 0
	return p, nil
}

func cListPosts(db *sql.DB, before int64, limit int) ([]CPost, error) {
	return cListPostsEx(db, before, limit, "", "", 0, "")
}

func cGetPost(db *sql.DB, id int64) (CPost, error) {
	row := db.QueryRow(`SELECT `+cPostColumns+` FROM c_posts WHERE id=?`, id)
	p, err := cScanPost(row.Scan)
	if err != nil {
		return p, err
	}
	p.CommentCount = cCommentCount(db, id)
	return p, nil
}

func cCommentCount(db *sql.DB, postID int64) int {
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM c_comments WHERE post_id=?`, postID).Scan(&n)
	return n
}

func cAddComment(db *sql.DB, postID int64, c CComment) (CComment, error) {
	res, err := db.Exec(
		`INSERT INTO c_comments(post_id,user_id,author,body,created_at,ip_loc) VALUES(?,?,?,?,?,?)`,
		postID, c.UserID, c.Author, c.Body, c.CreatedAt, c.IPLoc)
	if err != nil {
		return c, err
	}
	c.ID, _ = res.LastInsertId()
	c.PostID = postID
	return c, nil
}

func cListComments(db *sql.DB, postID int64) ([]CComment, error) {
	rows, err := db.Query(
		`SELECT id,post_id,user_id,author,body,created_at,ip_loc FROM c_comments WHERE post_id=? ORDER BY created_at ASC`,
		postID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []CComment
	for rows.Next() {
		var c CComment
		var ipLoc sql.NullString
		if err := rows.Scan(&c.ID, &c.PostID, &c.UserID, &c.Author, &c.Body, &c.CreatedAt, &ipLoc); err != nil {
			continue
		}
		c.IPLoc = ipLoc.String
		// 老数据没有归属地：尝试用缓存的 IP 补，补不到就留空
		if c.IPLoc == "" {
			c.IPLoc = ipLocationByCommentID(db, c.ID)
		}
		out = append(out, c)
	}
	return out, nil
}

// 老评论补归属地：从 reg_ip 之类的原始 IP 反查（仅当曾经记录过）
func ipLocationByCommentID(db *sql.DB, id int64) string {
	var ip sql.NullString
	_ = db.QueryRow(`SELECT ip FROM c_comments WHERE id=?`, id).Scan(&ip)
	if !ip.Valid || ip.String == "" {
		return ""
	}
	return ipLocation(ip.String)
}

func cLikePost(db *sql.DB, id int64) (int, error) {
	_, err := db.Exec(`UPDATE c_posts SET likes=likes+1 WHERE id=?`, id)
	if err != nil {
		return 0, err
	}
	var n int
	_ = db.QueryRow(`SELECT likes FROM c_posts WHERE id=?`, id).Scan(&n)
	return n, nil
}

// ===== 硬件行情复用（基于现有 jd_latest / jd_rounds / price_history） =====
// 行情能力已移除，社区只保留图文内容。

// hwItem 行情条目：列表 / 涨跌榜 / 详情共用同一结构
type hwItem struct {
	Key         string    `json:"key"`
	Cat         string    `json:"cat"`
	CatLabel    string    `json:"catLabel"`
	Brand       string    `json:"brand"`
	Model       string    `json:"model"`
	Spec        string    `json:"spec"`
	Keyword     string    `json:"keyword"`
	Price       any       `json:"price"`
	Platform    string    `json:"platform"`
	Shop        string    `json:"shop"`
	Title       string    `json:"title"`
	Trend       string    `json:"trend"`
	PrevPrice   any       `json:"prevPrice"`
	ChangePct   float64   `json:"changePct"`
	ChangeAbs   float64   `json:"changeAbs"` // 实际差价 = 现价 - 上轮价
	HasChange   bool      `json:"hasChange"`
	PrevRoundAt int64     `json:"prevRoundAt"` // 上轮采集时间（毫秒），0 表示无可比轮次
	Trends      []float64 `json:"trends"`
	NoMatch     bool      `json:"noMatch"`
	UpdatedAt   int64     `json:"updatedAt"`
	PriceStatus string    `json:"priceStatus"`
}

// hwChg 轮次间的价格变动（用于涨跌榜/涨跌幅展示）
type hwChg struct {
	From float64
	To   float64
	Pct  float64
	At   int64 // 上轮采集时间（秒）
}

// hwStableKey 稳定身份：row_key 跨轮次会变（早期是 gpu-rtx-50 这类系列键，
// 后期变成内容哈希），不能用来串时间轴。用「品类+品牌+型号+规格」做身份，
// 再 hex 编码成 URL 安全串，既能定位历史，也不会把中文塞进路径。
func hwStableKey(cat, brand, model, spec string) string {
	raw := normCat(cat) + "|" + strings.TrimSpace(brand) + "|" + strings.TrimSpace(model) + "|" + specKey(cat, model, spec)
	return hex.EncodeToString([]byte(raw))
}

func hwDecodeKey(key string) (cat, brand, model, spec string, ok bool) {
	b, err := hex.DecodeString(strings.TrimSpace(key))
	if err != nil {
		return "", "", "", "", false
	}
	parts := strings.Split(string(b), "|")
	if len(parts) < 4 {
		return "", "", "", "", false
	}
	return parts[0], parts[1], parts[2], parts[3], true
}

// hwMatch 判断某行是否属于该稳定身份
func hwMatch(cat, brand, model, spec string, rCat, rBrand, rModel, rSpec string) bool {
	if normCat(rCat) != cat {
		return false
	}
	if strings.TrimSpace(rBrand) != brand || strings.TrimSpace(rModel) != model {
		return false
	}
	return specKey(rCat, rModel, rSpec) == spec
}

// hwRoundChanges 取每个型号在最近 2 个采集轮次之间的涨跌幅（按稳定身份聚合，
// 同一轮多店铺取最低价，避免同轮噪声）。
func hwRoundChanges(db *sql.DB) map[string]hwChg {
	out := map[string]hwChg{}
	rows, err := db.Query(`
		SELECT ts, cat_label, brand, model, spec, price FROM jd_rounds
		WHERE price IS NOT NULL AND price > 0 AND ts >= (
			SELECT MIN(ts) FROM (
				SELECT DISTINCT ts FROM jd_rounds ORDER BY ts DESC LIMIT 2
			) t
		) ORDER BY ts ASC`)
	if err != nil {
		log.Printf("hwRoundChanges: %v", err)
		return out
	}
	defer rows.Close()
	type rp struct {
		ts    int64
		price float64
	}
	// key -> ts -> 最低价
	seq := map[string]map[int64]float64{}
	for rows.Next() {
		var ts int64
		var cl, bm, md, sp sql.NullString
		var pr sql.NullFloat64
		if err := rows.Scan(&ts, &cl, &bm, &md, &sp, &pr); err != nil || !pr.Valid {
			continue
		}
		k := hwStableKey(cl.String, bm.String, md.String, sp.String)
		if seq[k] == nil {
			seq[k] = map[int64]float64{}
		}
		if cur, ok := seq[k][ts]; !ok || pr.Float64 < cur {
			seq[k][ts] = pr.Float64
		}
	}
	for k, byTs := range seq {
		if len(byTs) < 2 {
			continue
		}
		tss := make([]int64, 0, len(byTs))
		for t := range byTs {
			tss = append(tss, t)
		}
		sort.Slice(tss, func(i, j int) bool { return tss[i] < tss[j] })
		first, last := byTs[tss[0]], byTs[tss[len(tss)-1]]
		if first <= 0 || first == last {
			continue
		}
		// At = 上一轮的采集时间，供前端展示「相比 M-d HH:mm」。
		// 注意取倒数第二个时间点，那才是「上轮」。
		out[k] = hwChg{
			From: first,
			To:   last,
			Pct:  (last - first) / first * 100,
			At:   tss[len(tss)-2],
		}
	}
	return out
}

func handleHWModels(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	cat := normCat(strings.TrimSpace(r.URL.Query().Get("cat")))
	kw := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	sortBy := strings.TrimSpace(r.URL.Query().Get("sort"))
	limit := 0
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 500 {
			limit = n
		}
	}
	chgs := hwRoundChanges(db)

	rows, err := db.Query(
		`SELECT row_key,cat_label,brand,model,spec,title,price,shop,platform,trend,prev_price,ts
		 FROM jd_latest ORDER BY ts DESC`)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()

	data := []hwItem{}
	for rows.Next() {
		var rowKey, catLabel, brand, model, spec, title, shop, platform, trend, ts sql.NullString
		var price, prevPrice sql.NullFloat64
		if err := rows.Scan(&rowKey, &catLabel, &brand, &model, &spec, &title, &price, &shop, &platform, &trend, &prevPrice, &ts); err != nil {
			continue
		}
		nc := normCat(catLabel.String)
		if cat != "" && nc != cat {
			continue
		}
		if kw != "" {
			hay := strings.ToLower(brand.String + " " + model.String + " " + spec.String + " " + title.String)
			if !strings.Contains(hay, kw) {
				continue
			}
		}
		it := hwItem{
			Key: hwStableKey(catLabel.String, brand.String, model.String, spec.String),
			Cat: nc, CatLabel: nc,
			Brand: brand.String, Model: model.String, Spec: spec.String,
			Keyword: model.String, Platform: platform.String, Shop: shop.String,
			Title: title.String, Trend: trend.String,
			PrevPrice: nullFloat(prevPrice), UpdatedAt: atoi64(ts.String) * 1000,
		}
		if price.Valid {
			it.Price = price.Float64
			it.PriceStatus = "ok"
			if c, ok := chgs[it.Key]; ok {
				it.ChangePct = math.Round(c.Pct*100) / 100
				it.ChangeAbs = math.Round((c.To-c.From)*100) / 100
				it.PrevPrice = c.From
				it.HasChange = hwPlausible(it.ChangePct)
				if it.HasChange {
					it.PrevRoundAt = c.At * 1000
				}
			} else if prevPrice.Valid && prevPrice.Float64 > 0 {
				it.ChangePct = math.Round((price.Float64-prevPrice.Float64)/prevPrice.Float64*10000) / 100
				it.ChangeAbs = math.Round((price.Float64-prevPrice.Float64)*100) / 100
				it.HasChange = hwPlausible(it.ChangePct)
			}
			if !it.HasChange {
				it.ChangePct = 0
				it.ChangeAbs = 0
			}
			// 涨跌方向以服务端算出的为准，修正采集端可能留空的 trend
			switch {
			case it.ChangeAbs > 0:
				it.Trend = "up"
			case it.ChangeAbs < 0:
				it.Trend = "down"
			case it.HasChange:
				it.Trend = "flat"
			}
			spark := []float64{}
			if prevPrice.Valid && prevPrice.Float64 > 0 {
				spark = append(spark, prevPrice.Float64)
			}
			spark = append(spark, price.Float64)
			it.Trends = spark
		} else {
			it.Price = nil
			it.NoMatch = true
			it.PriceStatus = "nomatch"
			it.Trends = []float64{}
		}
		data = append(data, it)
	}

	switch sortBy {
	case "price_asc":
		sort.SliceStable(data, func(i, j int) bool { return hwPrice(data[i]) < hwPrice(data[j]) })
	case "price_desc":
		sort.SliceStable(data, func(i, j int) bool { return hwPrice(data[i]) > hwPrice(data[j]) })
	case "change_desc":
		sort.SliceStable(data, func(i, j int) bool { return data[i].ChangePct > data[j].ChangePct })
	case "change_asc":
		sort.SliceStable(data, func(i, j int) bool { return data[i].ChangePct < data[j].ChangePct })
	}
	if limit > 0 && len(data) > limit {
		data = data[:limit]
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]interface{}{"count": len(data), "models": data}})
}

func hwPrice(it hwItem) float64 {
	if f, ok := it.Price.(float64); ok {
		return f
	}
	return math.MaxFloat64
}

// hwSpark 单型号走势（合并采集轮次 + 历史序列），最多 n 个点，按时间升序
func hwSpark(db *sql.DB, key string, n int) []float64 {
	pts := hwHistoryPoints(db, key)
	if len(pts) > n {
		pts = pts[len(pts)-n:]
	}
	out := make([]float64, len(pts))
	for i, p := range pts {
		out[i] = p.Ref
	}
	return out
}

// hwPoint 时间轴上的一个价格点
type hwPoint struct {
	Ts  int64
	Ref float64
	Lo  float64
	Hi  float64
}

// hwHistoryPoints 按稳定身份取全量历史点（升序去重）。
// jd_rounds 提供轮次价格，price_history 提供增量落库的历史，
// 两张表都按 model 粗筛后在 Go 里按「品类+品牌+型号+规格」精确过滤。
func hwHistoryPoints(db *sql.DB, key string) []hwPoint {
	cat, brand, model, spec, ok := hwDecodeKey(key)
	if !ok {
		return nil
	}
	byTs := map[int64]*hwPoint{}
	// 轮次价格
	if rows, err := db.Query(`SELECT ts, cat_label, brand, model, spec, price FROM jd_rounds WHERE model=? ORDER BY ts ASC`, model); err == nil {
		for rows.Next() {
			var ts int64
			var cl, bm, md, sp sql.NullString
			var pr sql.NullFloat64
			if err := rows.Scan(&ts, &cl, &bm, &md, &sp, &pr); err != nil || !pr.Valid || pr.Float64 <= 0 {
				continue
			}
			if !hwMatch(cat, brand, model, spec, cl.String, bm.String, md.String, sp.String) {
				continue
			}
			if cur, exists := byTs[ts]; !exists || pr.Float64 < cur.Ref {
				byTs[ts] = &hwPoint{Ts: ts, Ref: pr.Float64, Lo: pr.Float64, Hi: pr.Float64}
			}
		}
		rows.Close()
	}
	// 历史序列：price_history 的 sample 存的是商品标题，不能按型号匹配；
	// 统一用 jd_latest.row_key 关联（新导入的历史点也用同一 row_key 落库）。
	if rows, err := db.Query(`
		SELECT ph.ts, ph.cat, ph.ref_price, ph.lo_price, ph.hi_price, ph.row_key
		FROM price_history ph JOIN jd_latest j ON j.row_key = ph.row_key
		WHERE j.model=?`, model); err == nil {
		for rows.Next() {
			var ts int64
			var cl, rk sql.NullString
			var ref, lo, hi sql.NullFloat64
			if err := rows.Scan(&ts, &cl, &ref, &lo, &hi, &rk); err != nil || !ref.Valid || ref.Float64 <= 0 {
				continue
			}
			if cat != "" && normCat(cl.String) != cat {
				continue
			}
			if _, exists := byTs[ts]; exists {
				continue
			}
			p := &hwPoint{Ts: ts, Ref: ref.Float64, Lo: ref.Float64, Hi: ref.Float64}
			if lo.Valid && lo.Float64 > 0 {
				p.Lo = lo.Float64
			}
			if hi.Valid && hi.Float64 > 0 {
				p.Hi = hi.Float64
			}
			byTs[ts] = p
		}
		rows.Close()
	}
	out := make([]hwPoint, 0, len(byTs))
	for _, p := range byTs {
		out = append(out, *p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Ts < out[j].Ts })
	return out
}

func handleHWStatus(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM jd_latest`).Scan(&n)
	var ts int64
	_ = db.QueryRow(`SELECT COALESCE(MAX(ts),0) FROM jd_latest`).Scan(&ts)
	var rounds int
	_ = db.QueryRow(`SELECT COUNT(DISTINCT ts) FROM jd_rounds`).Scan(&rounds)
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"count":     n,
			"updatedAt": ts * 1000,
			"rounds":    rounds,
			"source":    "jd_latest",
		},
	})
}

// ===== 硬件行情：分类 + 单型号历史 + 涨跌榜 =====

var hwCatOrder = []string{"显卡", "处理器", "固态硬盘", "内存条", "笔记本", "游戏主机"}

// handleHWCatLabels 返回归一化后的品类（带数量），顺序与 App tab 一致
func handleHWCatLabels(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	rows, err := db.Query(`SELECT cat_label, COUNT(*) FROM jd_latest WHERE cat_label IS NOT NULL AND cat_label<>'' GROUP BY cat_label`)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()
	cnt := map[string]int{}
	for rows.Next() {
		var c string
		var n int
		if err := rows.Scan(&c, &n); err == nil {
			cnt[normCat(c)] += n
		}
	}
	type catItem struct {
		Cat   string `json:"cat"`
		Count int    `json:"count"`
	}
	out := []catItem{}
	for _, c := range hwCatOrder {
		if cnt[c] > 0 {
			out = append(out, catItem{c, cnt[c]})
		}
	}
	// 兜底：不在白名单里的品类也放出来，避免数据被藏
	for c, n := range cnt {
		if catIndex(c) == 99 && n > 0 {
			out = append(out, catItem{c, n})
		}
	}
	total := 0
	for _, v := range cnt {
		total += v
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]interface{}{
		"total": total,
		"cats":  out,
	}})
}

func catIndex(c string) int {
	for i, x := range hwCatOrder {
		if x == c {
			return i
		}
	}
	return 99
}

// handleHWHistory 单型号价格走势：合并采集轮次 + 历史序列，按时间升序去重
func handleHWHistory(db *sql.DB, w http.ResponseWriter, r *http.Request, key string) {
	key = strings.TrimSpace(key)
	if key == "" {
		http.NotFound(w, r)
		return
	}
	pts := hwHistoryPoints(db, key)
	type ptJSON struct {
		Ts  int64   `json:"ts"`
		Ref float64 `json:"ref"`
		Lo  float64 `json:"lo"`
		Hi  float64 `json:"hi"`
	}
	data := make([]ptJSON, 0, len(pts))
	var minP, maxP, sum float64
	for _, p := range pts {
		data = append(data, ptJSON{p.Ts, p.Ref, p.Lo, p.Hi})
		if minP == 0 || p.Ref < minP {
			minP = p.Ref
		}
		if p.Ref > maxP {
			maxP = p.Ref
		}
		sum += p.Ref
	}
	last := 0.0
	if n := len(data); n > 0 {
		last = data[n-1].Ref
	}
	avg := 0.0
	if len(data) > 0 {
		avg = math.Round(sum/float64(len(data))*100) / 100
	}
	chg := 0.0
	chgPct := 0.0
	if len(data) > 1 && data[0].Ref > 0 {
		chg = math.Round((last-data[0].Ref)*100) / 100
		chgPct = math.Round(chg/data[0].Ref*10000) / 100
	}

	// 上轮对比：用最后两个点（最近一次采集 vs 上一次采集）
	prevPrice := 0.0
	prevAt := int64(0)
	prevDiff := 0.0
	prevPct := 0.0
	hasPrev := false
	if n := len(data); n >= 2 {
		prevPrice = data[n-2].Ref
		prevAt = data[n-2].Ts
		if prevPrice > 0 {
			prevDiff = math.Round((last-prevPrice)*100) / 100
			prevPct = math.Round(prevDiff/prevPrice*10000) / 100
			hasPrev = hwPlausible(prevPct)
		}
	}

	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]interface{}{
		"points": data,
		"stats": map[string]interface{}{
			"min": minP, "max": maxP, "avg": avg, "last": last,
			"count": len(data), "change": chg, "changePct": chgPct,
		},
		// 与上一轮采集的对比：diff 是实际差价，pct 是百分比
		"prevRound": map[string]interface{}{
			"price":   prevPrice,
			"at":      prevAt * 1000,
			"diff":    prevDiff,
			"pct":     prevPct,
			"hasPrev": hasPrev,
		},
	}})
}

// handleHWMovers 涨跌榜：窗口内首末轮次价格变化，按幅度排序
func handleHWMovers(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	cat := normCat(strings.TrimSpace(r.URL.Query().Get("cat")))
	win := strings.TrimSpace(r.URL.Query().Get("window"))
	limit := 20
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 200 {
			limit = n
		}
	}
	var cutoff int64
	switch win {
	case "1d":
		cutoff = time.Now().Unix() - 86400
	case "7d":
		cutoff = time.Now().Unix() - 7*86400
	case "30d":
		cutoff = time.Now().Unix() - 30*86400
	}
	q := `SELECT ts, cat_label, brand, model, spec, platform, shop, price FROM jd_rounds
	      WHERE price IS NOT NULL AND price>0`
	args := []any{}
	if cutoff > 0 {
		q += ` AND ts >= ?`
		args = append(args, cutoff)
	}
	q += ` ORDER BY ts ASC`
	rows, err := db.Query(q, args...)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()
	type meta struct{ cat, brand, model, spec, plat, shop string }
	first := map[string]float64{}
	last := map[string]float64{}
	metaOf := map[string]meta{}
	for rows.Next() {
		var ts sql.NullInt64
		var cl, bm, md, sp, pl, sh sql.NullString
		var pr sql.NullFloat64
		if err := rows.Scan(&ts, &cl, &bm, &md, &sp, &pl, &sh, &pr); err != nil || !pr.Valid {
			continue
		}
		k := hwStableKey(cl.String, bm.String, md.String, sp.String)
		if _, ok := first[k]; !ok {
			first[k] = pr.Float64
			metaOf[k] = meta{normCat(cl.String), strings.TrimSpace(bm.String), strings.TrimSpace(md.String), specKey(cl.String, md.String, sp.String), pl.String, sh.String}
		}
		last[k] = pr.Float64
	}

	out := []hwItem{}
	for k, f := range first {
		l := last[k]
		if f <= 0 || l <= 0 {
			continue
		}
		mo := metaOf[k]
		if cat != "" && mo.cat != cat {
			continue
		}
		pct := math.Round((l-f)/f*10000) / 100
		if !hwPlausible(pct) || pct == 0 {
			continue
		}
		it := hwItem{
			Key: k, Cat: mo.cat, CatLabel: mo.cat,
			Brand: mo.brand, Model: mo.model, Spec: mo.spec, Keyword: mo.model,
			Platform: mo.plat, Shop: mo.shop,
			Price: l, PrevPrice: f, PriceStatus: "ok",
			ChangePct: pct, HasChange: true,
			Trends: []float64{f, l},
		}
		if pct > 0 {
			it.Trend = "up"
		} else if pct < 0 {
			it.Trend = "down"
		} else {
			it.Trend = "flat"
		}
		out = append(out, it)
	}
	// 轮次间没有可比数据时，退回 jd_latest 的 prev_price（采集端自带的上一价）
	if len(out) == 0 {
		out = hwMoversFromPrev(db, cat)
	}
	if len(out) == 0 {
		writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]interface{}{
			"count": 0, "movers": []hwItem{}, "empty": true,
		}})
		return
	}
	sort.SliceStable(out, func(i, j int) bool {
		return math.Abs(out[i].ChangePct) > math.Abs(out[j].ChangePct)
	})
	if len(out) > limit {
		out = out[:limit]
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]interface{}{"count": len(out), "movers": out}})
}

// hwMaxSwing 单轮涨跌幅合理上限（%）。超过即判定为脏数据（如历史上把「多型号打包
// 链接」的券前价当成上一价，会出现 -50% 这种假暴跌），一律不展示。
// 原则：宁可无价，不可错价。
const hwMaxSwing = 40.0

func hwPlausible(pct float64) bool { return math.Abs(pct) <= hwMaxSwing }

// hwMoversFromPrev 以 jd_latest 自带的 prev_price 作为上一价，生成涨跌条目。
// 采集端只在确认到价格变化时才写 prev_price，因此这一路数据更"干净"但更稀疏。
func hwMoversFromPrev(db *sql.DB, cat string) []hwItem {
	rows, err := db.Query(
		`SELECT cat_label, brand, model, spec, platform, shop, price, prev_price, ts
		 FROM jd_latest WHERE price>0 AND prev_price>0`)
	if err != nil {
		return nil
	}
	defer rows.Close()
	out := []hwItem{}
	for rows.Next() {
		var cl, bm, md, sp, pl, sh sql.NullString
		var pr, pv sql.NullFloat64
		var ts sql.NullInt64
		if err := rows.Scan(&cl, &bm, &md, &sp, &pl, &sh, &pr, &pv, &ts); err != nil || !pr.Valid || !pv.Valid {
			continue
		}
		nc := normCat(cl.String)
		if cat != "" && nc != cat {
			continue
		}
		pct := math.Round((pr.Float64-pv.Float64)/pv.Float64*10000) / 100
		if !hwPlausible(pct) {
			continue
		}
		if pct == 0 {
			continue
		}
		it := hwItem{
			Key: hwStableKey(cl.String, bm.String, md.String, sp.String),
			Cat: nc, CatLabel: nc,
			Brand: strings.TrimSpace(bm.String), Model: strings.TrimSpace(md.String),
			Spec: strings.TrimSpace(sp.String), Keyword: strings.TrimSpace(md.String),
			Platform: pl.String, Shop: sh.String, PriceStatus: "ok",
			Price: pr.Float64, PrevPrice: pv.Float64,
			ChangePct: pct, HasChange: true,
			UpdatedAt: ts.Int64 * 1000,
			Trends:    []float64{pv.Float64, pr.Float64},
		}
		if pct > 0 {
			it.Trend = "up"
		} else if pct < 0 {
			it.Trend = "down"
		} else {
			it.Trend = "flat"
		}
		out = append(out, it)
	}
	return out
}

func nullFloat(n sql.NullFloat64) any {	if n.Valid {
		return n.Float64
	}
	return nil
}
func atoi64(s string) int64 {
	i, _ := parseInt(s)
	return i
}
func parseInt(s string) (int64, error) {
	var n int64
	_, err := fmt.Sscanf(s, "%d", &n)
	return n, err
}

// ===== 社区 API handlers =====

func handleCommunityFeed(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	qp := r.URL.Query()
	before, _ := parseInt(qp.Get("before"))
	limit, _ := parseInt(qp.Get("limit"))
	tag := strings.TrimSpace(strings.TrimPrefix(qp.Get("tag"), "#"))
	kw := strings.TrimSpace(qp.Get("q"))
	sortBy := strings.TrimSpace(qp.Get("sort")) // hot | 空
	mine := qp.Get("mine") == "1"

	u := cAuthUser(db, r)
	var uid int64
	if u != nil {
		uid = u.ID
	}
	if mine && uid == 0 {
		writeJSON(w, 401, map[string]string{"error": "请先登录"})
		return
	}
	filterUID := int64(0)
	if mine {
		filterUID = uid
	}
	posts, err := cListPostsEx(db, before, int(limit), tag, kw, filterUID, sortBy)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": posts})
}

func handleCommunityCreatePost(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	var p CPost
	if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败: " + err.Error()})
		return
	}
	u := cAuthUser(db, r)
	if u != nil {
		p.UserID = u.ID
	}
	p.Type = strings.TrimSpace(p.Type)
	if p.Type == "" {
		p.Type = "text"
	}
	p.Author = cAuthorName(u, p.Author)
	// IP 归属地：只记省市用于展示，完整 IP 不对外暴露
	if cip := clientIP(r); cip != "" {
		p.IPLoc = ipLocation(cip)
	}
	p.Body = strings.TrimSpace(p.Body)
	p.Title = strings.TrimSpace(p.Title)
	if p.Body == "" && p.Title == "" && p.URL == "" && len(p.Images) == 0 && len(p.Blocks) == 0 {
		writeJSON(w, 400, map[string]string{"error": "内容不能为空"})
		return
	}
	// 文章类：只带 blocks 时，从块里回填正文摘要，保证信息流能预览
	if p.Type == "article" && p.Body == "" && len(p.Blocks) > 0 {
		var parts []string
		for _, b := range p.Blocks {
			if b.Text != "" {
				parts = append(parts, b.Text)
			}
			if len([]rune(strings.Join(parts, ""))) > 300 {
				break
			}
		}
		p.Body = strings.Join(parts, "\n")
	}
	if len(p.Images) == 0 && len(p.Blocks) > 0 {
		for _, b := range p.Blocks {
			if b.Type == "img" && b.Src != "" {
				p.Images = append(p.Images, b.Src)
			}
		}
	}
	if p.Cover == "" && len(p.Images) > 0 {
		p.Cover = p.Images[0]
	}
	// 话题块 → 标签（编辑器的「+ 话题」）
	for _, b := range p.Blocks {
		if b.Type == "topic" && strings.TrimSpace(b.Text) != "" {
			for _, t := range strings.FieldsFunc(b.Text, func(r rune) bool {
				return r == ',' || r == '，' || r == '#' || r == ' ' || r == '、'
			}) {
				if s := strings.TrimSpace(t); s != "" {
					p.Tags = cAppendUnique(p.Tags, s)
				}
			}
		}
	}
	for _, t := range cExtractTags(p.Body + " " + p.Title) {
		p.Tags = cAppendUnique(p.Tags, t)
	}
	if len(p.Images) > 30 {
		p.Images = p.Images[:30]
	}
	p.CreatedAt = time.Now().UnixMilli()
	saved, err := cCreatePost(db, p)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	hub.broadcastJSON(map[string]interface{}{"type": "new_post", "post": saved})
	writeJSON(w, 201, map[string]interface{}{"code": 0, "data": saved})
}

func handleCommunityPost(db *sql.DB, w http.ResponseWriter, r *http.Request, id int64) {
	u := cAuthUser(db, r)
	var uid int64
	if u != nil {
		uid = u.ID
	}
	path := r.URL.Path
	switch {
	case r.Method == http.MethodGet:
		p, err := cGetPost(db, id)
		if err != nil {
			writeJSON(w, 404, map[string]string{"error": "帖子不存在"})
			return
		}
		cIncView(db, id)
		p.Views++
		p.Liked = cPostLiked(db, id, uid)
		comments, _ := cListComments(db, id)
		for i := range comments {
			comments[i].Mine = uid > 0 && comments[i].UserID == uid
		}
		writeJSON(w, 200, map[string]interface{}{
			"code": 0, "data": p, "comments": comments,
			"mine": uid > 0 && p.UserID == uid,
		})
	case r.Method == http.MethodPost && strings.HasSuffix(path, "/comments"):
		// 游客不能评论：必须登录
		if u == nil {
			writeJSON(w, 401, map[string]string{"error": "登录后才能评论"})
			return
		}
		var c CComment
		if err := json.NewDecoder(r.Body).Decode(&c); err != nil {
			writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
			return
		}
		c.UserID = u.ID
		c.Author = cAuthorName(u, c.Author)
		c.Body = strings.TrimSpace(c.Body)
		if c.Body == "" {
			writeJSON(w, 400, map[string]string{"error": "评论不能为空"})
			return
		}
		if len([]rune(c.Body)) > 2000 {
			writeJSON(w, 400, map[string]string{"error": "评论过长"})
			return
		}
		c.CreatedAt = time.Now().UnixMilli()
		if cip := clientIP(r); cip != "" {
			c.IPLoc = ipLocation(cip)
		}
		saved, err := cAddComment(db, id, c)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": err.Error()})
			return
		}
		hub.broadcastJSON(map[string]interface{}{"type": "new_comment", "postId": id, "comment": saved})
		writeJSON(w, 201, map[string]interface{}{"code": 0, "data": saved})
	case r.Method == http.MethodPost && strings.HasSuffix(path, "/like"):
		// 游客不能点赞：必须登录
		if u == nil {
			writeJSON(w, 401, map[string]string{"error": "登录后才能点赞"})
			return
		}
		n, liked, err := cToggleLike(db, id, uid)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": err.Error()})
			return
		}
		hub.broadcastJSON(map[string]interface{}{"type": "like", "postId": id, "likes": n})
		writeJSON(w, 200, map[string]interface{}{"code": 0, "data": n, "liked": liked})
	case r.Method == http.MethodDelete:
		handleCommunityDelete(db, w, r, id)
	default:
		http.NotFound(w, r)
	}
}

func handleArticleParse(w http.ResponseWriter, r *http.Request) {
	u := r.URL.Query().Get("url")
	if u == "" && r.Method == http.MethodPost {
		var b struct {
			URL string `json:"url"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		u = b.URL
	}
	u = strings.TrimSpace(u)
	if u == "" {
		writeJSON(w, 400, map[string]string{"error": "缺少 url 参数"})
		return
	}
	art, blocks, err := cParseArticleNative(u)
	if err != nil {
		writeJSON(w, 502, map[string]string{"error": err.Error()})
		return
	}
	// 同时返回 html(content) 和 blocks：老客户端继续用 content，
	// 新客户端用 blocks 做原生渲染，无需 WebView。
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]interface{}{
		"title":   art.Title,
		"author":  art.Author,
		"cover":   art.Cover,
		"content": art.Content,
		"images":  art.Images,
		"url":     art.URL,
		"summary": art.Summary,
		"blocks":  blocks,
		"count":   len(blocks),
	}})
}

// ===== 工具 =====

func writeJSON(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func cExtractTags(s string) []string {
	var tags []string
	for _, tk := range strings.Fields(s) {
		if strings.HasPrefix(tk, "#") && len([]rune(tk)) > 1 {
			tags = append(tags, strings.TrimPrefix(tk, "#"))
		}
	}
	return tags
}

func cAppendUnique(s []string, v string) []string {
	for _, x := range s {
		if x == v {
			return s
		}
	}
	return append(s, v)
}

// ===== WebSocket 实时 =====

var upgraderC = websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}

type cClient struct {
	conn *websocket.Conn
	send chan []byte
}

type cHub struct {
	clients    map[*cClient]bool
	register   chan *cClient
	unregister chan *cClient
	broadcast  chan []byte
}

var hub = &cHub{
	clients:    make(map[*cClient]bool),
	register:   make(chan *cClient),
	unregister: make(chan *cClient),
	broadcast:  make(chan []byte, 256),
}

func (h *cHub) run() {
	for {
		select {
		case c := <-h.register:
			h.clients[c] = true
		case c := <-h.unregister:
			if _, ok := h.clients[c]; ok {
				delete(h.clients, c)
				close(c.send)
			}
		case m := <-h.broadcast:
			for c := range h.clients {
				select {
				case c.send <- m:
				default:
					close(c.send)
					delete(h.clients, c)
				}
			}
		}
	}
}

func (h *cHub) broadcastJSON(v interface{}) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	select {
	case h.broadcast <- b:
	default:
	}
}

func (c *cClient) readPump() {
	defer func() {
		hub.unregister <- c
		c.conn.Close()
	}()
	for {
		if _, _, err := c.conn.ReadMessage(); err != nil {
			break
		}
	}
}

func (c *cClient) writePump() {
	defer c.conn.Close()
	for m := range c.send {
		if err := c.conn.WriteMessage(websocket.TextMessage, m); err != nil {
			return
		}
	}
}

func serveWsCommunity(w http.ResponseWriter, r *http.Request) {
	conn, err := upgraderC.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	c := &cClient{conn: conn, send: make(chan []byte, 16)}
	hub.register <- c
	go c.writePump()
	go c.readPump()
}

// ===== 公众号图文解析 =====

func cHttpGetUA(rawURL string) (*http.Response, error) {
	client := &http.Client{Timeout: 25 * time.Second}
	req, err := http.NewRequest("GET", rawURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1")
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	req.Header.Set("Accept-Language", "zh-CN,zh;q=0.9")
	req.Header.Set("Referer", "https://mp.weixin.qq.com/")
	return client.Do(req)
}

func cMetaContent(doc *goquery.Document, name string) string {
	v := doc.Find("meta[property='" + name + "']").AttrOr("content", "")
	if v == "" {
		v = doc.Find("meta[name='" + name + "']").AttrOr("content", "")
	}
	return strings.TrimSpace(v)
}

func cAbsURL(src, base string) string {
	if src == "" {
		return ""
	}
	if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") {
		return src
	}
	b, err := url.Parse(base)
	if err != nil {
		return src
	}
	ref, err := url.Parse(src)
	if err != nil {
		return src
	}
	return b.ResolveReference(ref).String()
}

func cParseArticle(rawURL string) (*CArticle, error) {
	resp, err := cHttpGetUA(rawURL)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("远端返回 %d", resp.StatusCode)
	}
	doc, err := goquery.NewDocumentFromReader(resp.Body)
	if err != nil {
		return nil, err
	}
	a := &CArticle{URL: rawURL}
	a.Title = cMetaContent(doc, "og:title")
	if a.Title == "" {
		a.Title = strings.TrimSpace(doc.Find("#activity-name").Text())
	}
	if a.Title == "" {
		a.Title = strings.TrimSpace(doc.Find("title").Text())
	}
	a.Author = cMetaContent(doc, "og:article:author")
	if a.Author == "" {
		a.Author = strings.TrimSpace(doc.Find("#js_name").Text())
	}
	a.Cover = cAbsURL(cMetaContent(doc, "og:image"), rawURL)

	content := doc.Find("#js_content")
	if content.Length() == 0 {
		content = doc.Find("article")
	}
	if content.Length() == 0 {
		content = doc.Find(".rich_media_content")
	}
	if content.Length() == 0 {
		content = doc.Find("body")
	}
	var imgs []string
	content.Find("img").Each(func(i int, s *goquery.Selection) {
		src, _ := s.Attr("data-src")
		if src == "" {
			src, _ = s.Attr("src")
		}
		src = strings.TrimSpace(src)
		if src != "" && !strings.HasPrefix(src, "data:") {
			imgs = append(imgs, cAbsURL(src, rawURL))
		}
	})
	a.Images = imgs
	content.Find("script,style").Remove()
	html, _ := content.Html()
	a.Content = strings.TrimSpace(html)
	text := strings.TrimSpace(content.Text())
	if len([]rune(text)) > 120 {
		text = string([]rune(text)[:120]) + "…"
	}
	a.Summary = text
	if a.Title == "" && a.Content == "" {
		return nil, fmt.Errorf("未能解析出任何内容，可能不是图文链接或已被反爬拦截")
	}
	return a, nil
}

// ===== 图片上传（供原生 App 发图帖）=====

// cSaveUpload 抽出来的单文件上传逻辑，社区发帖与头像共用
func cSaveUpload(r *http.Request, field string) (string, error) {
	r.Body = http.MaxBytesReader(nil, r.Body, 20<<20)
	if err := r.ParseMultipartForm(20 << 20); err != nil {
		return "", fmt.Errorf("解析失败: %w", err)
	}
	files := r.MultipartForm.File[field]
	if len(files) == 0 {
		return "", fmt.Errorf("没有文件")
	}
	dir := communityDir + "/uploads"
	_ = os.MkdirAll(dir, 0o755)
	allowed := map[string]bool{".jpg": true, ".jpeg": true, ".png": true, ".gif": true, ".webp": true}
	for _, fh := range files {
		ext := strings.ToLower(filepath.Ext(fh.Filename))
		if !allowed[ext] {
			continue
		}
		src, err := fh.Open()
		if err != nil {
			continue
		}
		name := fmt.Sprintf("%d_%s%s", time.Now().UnixNano(), randStr(6), ext)
		dst, err := os.Create(dir + "/" + name)
		if err != nil {
			src.Close()
			continue
		}
		_, _ = io.Copy(dst, src)
		src.Close()
		dst.Close()
		return "/community/uploads/" + name, nil
	}
	return "", fmt.Errorf("无效图片（仅支持 jpg/png/gif/webp）")
}

func handleCommunityUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 20<<20)
	if err := r.ParseMultipartForm(20 << 20); err != nil {
		writeJSON(w, 400, map[string]string{"error": "解析失败: " + err.Error()})
		return
	}
	files := r.MultipartForm.File["file"]
	if len(files) == 0 {
		writeJSON(w, 400, map[string]string{"error": "没有文件"})
		return
	}
	dir := communityDir + "/uploads"
	_ = os.MkdirAll(dir, 0o755)
	allowed := map[string]bool{".jpg": true, ".jpeg": true, ".png": true, ".gif": true, ".webp": true}
	var urls []string
	for _, fh := range files {
		ext := strings.ToLower(filepath.Ext(fh.Filename))
		if !allowed[ext] {
			continue
		}
		src, err := fh.Open()
		if err != nil {
			continue
		}
		name := fmt.Sprintf("%d_%s%s", time.Now().UnixNano(), randStr(6), ext)
		dst, err := os.Create(dir + "/" + name)
		if err != nil {
			src.Close()
			continue
		}
		_, _ = io.Copy(dst, src)
		src.Close()
		dst.Close()
		urls = append(urls, "/community/uploads/"+name)
	}
	if len(urls) == 0 {
		writeJSON(w, 400, map[string]string{"error": "无效图片（仅支持 jpg/png/gif/webp）"})
		return
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]interface{}{"urls": urls}})
}

func randStr(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = letters[rand.Intn(len(letters))]
	}
	return string(b)
}

// ===== 安卓 App 下载页 =====

const appDownloadPage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>超米 · 安卓 App</title>
<style>
  body{margin:0;font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#0d1117;color:#e6edf3;display:flex;min-height:100vh;align-items:center;justify-content:center}
  .card{background:#161b22;border:1px solid #30363d;border-radius:16px;padding:32px 28px;max-width:420px;width:90%;text-align:center;box-shadow:0 12px 40px rgba(0,0,0,.4)}
  h1{margin:0 0 4px;font-size:22px}
  .sub{color:#8b949e;font-size:13px;margin-bottom:20px}
  .btn{display:inline-block;background:#238636;color:#fff;text-decoration:none;padding:13px 22px;border-radius:10px;font-weight:600;font-size:15px}
  .btn:active{background:#2ea043}
  .meta{margin-top:16px;color:#8b949e;font-size:12px;line-height:1.7}
  .tip{margin-top:14px;background:#1c2128;border:1px solid #30363d;border-radius:10px;padding:12px;font-size:12px;color:#c9d1d9;text-align:left}
  details{margin-top:12px;background:#1c2128;border:1px solid #30363d;border-radius:10px;padding:12px;font-size:12px;color:#c9d1d9;text-align:left}
  summary{cursor:pointer;color:#58a6ff;font-weight:600;outline:none}
  details ol{margin:8px 0 0;padding-left:18px;line-height:1.9}
  details b{color:#e3b341}
  code{background:#0d1117;padding:1px 5px;border-radius:4px;color:#a5d6ff;font-size:11px}
</style></head>
<body><div class="card">
  <h1>超米</h1>
  <div class="sub">资讯聚合 · 图文社区 · 实时互动</div>
  <a class="btn" href="/downloads/__APK__">⬇ 下载安卓 App（APK）</a>
  <div class="meta">版本 __VER__ · 原生应用 · 支持 Android 5.0 及以上</div>
  <div class="meta">备用下载：<a href="https://github.com/bailu12300/chaomi/releases/latest"
     style="color:#58a6ff">GitHub Releases</a></div>
  <div class="tip">安装提示：安卓默认禁止「未知来源」安装。下载后点击 APK，按提示允许「允许来自此来源的应用」即可安装。</div>
  <div class="tip" style="margin-top:10px">
    <b style="color:#e3b341">本版功能</b><br>
    · <b>资讯</b>：数码 / AI / 能源 / 国际 / 科技，服务器定时抓取并翻译，断网也能看中文<br>
    · <b>天气卡片</b>：按定位自动取当前城市，5 天预报<br>
    · <b>图文社区</b>：富文本编辑（行内加粗/高亮/代码块/内嵌链接/引用旧文）<br>
    · 公众号一键转原生文章，沉浸式阅读<br>
    · 账号体系：邮箱注册，发帖评论点赞记在名下，显示 IP 归属地<br>
    · <b>净屏</b>：无障碍自动点掉开屏广告，内置规则离线可用<br>
    · <b>节点</b>：实时延迟监控，每 3 秒心跳（20 次/分钟）<br>
    · 后台常驻：开机自启 + 定时自愈
  </div>
  <details>
    <summary>装不上？点这里看排查步骤</summary>
    <ol>
      <li><b>安装提示</b>：超米使用独立包名 <code>com.chaomi.app</code>，与其它同名应用互不影响，<b>可以直接安装</b>。</li>
      <li><b>提示「解析软件包时出现问题」</b>：多半是下载不完整或被浏览器改名。请用系统浏览器下载，别用微信/QQ 内置浏览器直接打开安装。</li>
      <li><b>华为 / 荣耀</b>：设置 → 系统和更新 → 纯净模式 → 关闭；或在「应用管控中心」里放行。</li>
      <li><b>小米 / Redmi</b>：设置 → 应用设置 → 应用管理 → 右上角 → 特殊权限 → 安装未知应用 → 允许浏览器。</li>
      <li><b>OPPO / vivo / 一加 / realme</b>：安装时勾选「允许来自此来源」，或临时关闭「安全检查」。</li>
      <li><b>Android 11+ 提示「应用未安装」</b>：设置里搜索「安装未知应用」，给浏览器授权后重试。</li>
      <li><b>仍然失败</b>：把手机型号 + 具体报错文字发给管理员，可按错误码定位。</li>
    </ol>
  </details>
</div></body></html>`

// latestAPK 取 downloads 目录里最新的 APK（按修改时间），避免每次发版改代码。
func latestAPK() (name, version string) {
	dir := "site/downloads"
	if communityDir != "" {
		dir = filepath.Join(filepath.Dir(communityDir), "downloads")
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "", ""
	}
	var best os.FileInfo
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(strings.ToLower(e.Name()), ".apk") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		if best == nil || info.ModTime().After(best.ModTime()) {
			best = info
			name = e.Name()
		}
	}
	if name != "" {
		version = name
		version = strings.TrimSuffix(version, ".apk")
		if i := strings.LastIndex(version, "v"); i >= 0 {
			version = version[i+1:]
		}
	}
	return name, version
}

func handleAppDownloadPage(w http.ResponseWriter, r *http.Request) {
	name, ver := latestAPK()
	if name == "" {
		name = "chaomi-v1.0.0.apk"
		ver = "1.2.0"
	}
	html := strings.Replace(appDownloadPage, "__APK__", name, 1)
	html = strings.Replace(html, "__VER__", ver, 1)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write([]byte(html))
}
