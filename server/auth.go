package main

// ---------------- 账号体系 + 文章转原生 + 社区增强 ----------------
// 账号：邮箱+密码注册登录，PBKDF2-HMAC-SHA256 加盐存储，Bearer token 会话。
// 文章：公众号 HTML → 结构化 blocks（段落/标题/图片/引用/代码/列表），客户端原生渲染。
// 社区：点赞去重、我的帖子、删除自己的内容、标签/搜索/热度排序、浏览计数。

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/mail"
	"strconv"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// ===== 数据结构 =====

type CUser struct {
	ID        int64  `json:"id"`
	UID       string `json:"uid"`   // 唯一用户号，如 CM123456
	Email     string `json:"email"`
	Nick      string `json:"nick"`
	Avatar    string `json:"avatar,omitempty"`
	Bio       string `json:"bio,omitempty"`
	RegIP     string `json:"regIp,omitempty"`
	LastIP    string `json:"lastIp,omitempty"`
	CreatedAt int64  `json:"createdAt"`
	LastLogin int64  `json:"lastLogin,omitempty"`
	PostCount int    `json:"postCount"`
}

// CBlock 文章原生块：客户端据此渲染，不依赖 WebView
type CBlock struct {
	Type  string `json:"type"`            // p | h2 | h3 | img | quote | code | li | ol | video | hr | topic | link
	Text  string `json:"text,omitempty"`  // 文本内容（video 时存类型标记）
	Src   string `json:"src,omitempty"`   // img / video 地址
	Alt   string `json:"alt,omitempty"`   // img 图注 / video 封面
	Style string `json:"style,omitempty"` // center = 居中，空 = 默认左对齐
}

// ===== 建表（幂等，可反复执行） =====

func ensureAuthSchema(db *sql.DB) error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS c_users(
			id BIGINT AUTO_INCREMENT PRIMARY KEY,
			email VARCHAR(191) NOT NULL,
			nick VARCHAR(64) NOT NULL,
			pass_hash VARCHAR(128) NOT NULL,
			salt VARCHAR(64) NOT NULL,
			avatar VARCHAR(512) DEFAULT '',
			bio VARCHAR(255) DEFAULT '',
			created_at BIGINT NOT NULL,
			last_login BIGINT DEFAULT 0,
			UNIQUE KEY uk_c_users_email(email)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS c_sessions(
			token VARCHAR(64) NOT NULL PRIMARY KEY,
			user_id BIGINT NOT NULL,
			created_at BIGINT NOT NULL,
			expires_at BIGINT NOT NULL,
			INDEX idx_c_sessions_uid(user_id)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS c_likes(
			post_id BIGINT NOT NULL,
			user_id BIGINT NOT NULL,
			created_at BIGINT NOT NULL,
			UNIQUE KEY uk_c_likes(post_id,user_id)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			return err
		}
	}
	return nil
}

// cAddColumn 列不存在才加（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）
func cAddColumn(db *sql.DB, table, col, def string) error {
	var n int
	err := db.QueryRow(`SELECT COUNT(*) FROM information_schema.columns
		WHERE table_schema=DATABASE() AND table_name=? AND column_name=?`, table, col).Scan(&n)
	if err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	_, err = db.Exec(fmt.Sprintf("ALTER TABLE %s ADD COLUMN %s", table, def))
	return err
}

// ===== 口令哈希（stdlib 实现 PBKDF2-HMAC-SHA256，不引额外依赖） =====

func cPBKDF2(password, salt []byte, iter, keyLen int) []byte {
	h := hmac.New(sha256.New, password)
	hashLen := h.Size()
	blocks := (keyLen + hashLen - 1) / hashLen
	out := make([]byte, 0, blocks*hashLen)
	for b := 1; b <= blocks; b++ {
		h.Reset()
		h.Write(salt)
		h.Write([]byte{byte(b >> 24), byte(b >> 16), byte(b >> 8), byte(b)})
		u := h.Sum(nil)
		t := make([]byte, len(u))
		copy(t, u)
		for i := 1; i < iter; i++ {
			h.Reset()
			h.Write(u)
			u = h.Sum(nil)
			for j := range t {
				t[j] ^= u[j]
			}
		}
		out = append(out, t...)
	}
	return out[:keyLen]
}

func cHashPassword(pw, salt string) string {
	sum := cPBKDF2([]byte(pw), []byte(salt), 12000, 32)
	return hex.EncodeToString(sum)
}

func cRandHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return strings.Repeat("0", n*2)
	}
	return hex.EncodeToString(b)
}

// ===== 会话 =====

const cSessionTTL = 90 * 24 * time.Hour

func cCreateSession(db *sql.DB, userID int64) (string, error) {
	tok := cRandHex(32)
	now := time.Now()
	_, err := db.Exec(`INSERT INTO c_sessions(token,user_id,created_at,expires_at) VALUES(?,?,?,?)`,
		tok, userID, now.UnixMilli(), now.Add(cSessionTTL).UnixMilli())
	return tok, err
}

func cUserByToken(db *sql.DB, tok string) *CUser {
	tok = strings.TrimSpace(tok)
	if tok == "" {
		return nil
	}
	var u CUser
	var avatar, bio sql.NullString
	var uid, regIp, lastIp sql.NullString
	err := db.QueryRow(`
		SELECT u.id,u.email,u.nick,u.avatar,u.bio,u.created_at,u.last_login,u.uid,u.reg_ip,u.last_ip
		FROM c_sessions s JOIN c_users u ON u.id=s.user_id
		WHERE s.token=? AND s.expires_at>?`, tok, time.Now().UnixMilli()).
		Scan(&u.ID, &u.Email, &u.Nick, &avatar, &bio, &u.CreatedAt, &u.LastLogin,
			&uid, &regIp, &lastIp)
	if err != nil {
		return nil
	}
	u.Avatar = avatar.String
	u.Bio = bio.String
	u.UID = uid.String
	u.RegIP = regIp.String
	u.LastIP = lastIp.String
	return &u
}

// cAuthUser 从请求里取当前用户：Bearer token → query token → 空
func cAuthUser(db *sql.DB, r *http.Request) *CUser {
	tok := ""
	if a := r.Header.Get("Authorization"); strings.HasPrefix(strings.ToLower(a), "bearer ") {
		tok = strings.TrimSpace(a[7:])
	}
	if tok == "" {
		tok = r.URL.Query().Get("token")
	}
	if tok == "" {
		if c, err := r.Cookie("chaomi_token"); err == nil {
			tok = c.Value
		}
	}
	return cUserByToken(db, tok)
}

// cAuthorName 已登录用昵称，未登录按请求体（兼容老客户端）
func cAuthorName(u *CUser, fallback string) string {
	if u != nil && strings.TrimSpace(u.Nick) != "" {
		return u.Nick
	}
	if strings.TrimSpace(fallback) == "" {
		return "匿名"
	}
	return fallback
}

// ===== 账号 API =====

func handleAuthRegister(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	var b struct {
		Email    string `json:"email"`
		Password string `json:"password"`
		Nick     string `json:"nick"`
		Code     string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}
	b.Email = strings.ToLower(strings.TrimSpace(b.Email))
	b.Nick = strings.TrimSpace(b.Nick)
	if _, err := mail.ParseAddress(b.Email); err != nil || !strings.Contains(b.Email, ".") {
		writeJSON(w, 400, map[string]string{"error": "邮箱格式不正确"})
		return
	}
	if len([]rune(b.Password)) < 6 {
		writeJSON(w, 400, map[string]string{"error": "密码至少 6 位"})
		return
	}
	// 验证码：配置了 SMTP 就必须校验；未配置时放行（内测模式，响应里会标注）
	if smtpConfigured() {
		if !verifyCode(db, b.Email, "register", b.Code) {
			writeJSON(w, 400, map[string]string{"error": "验证码不正确或已过期"})
			return
		}
	}
	if b.Nick == "" {
		b.Nick = strings.Split(b.Email, "@")[0]
	}
	if len([]rune(b.Nick)) > 20 {
		b.Nick = string([]rune(b.Nick)[:20])
	}
	var exist int
	_ = db.QueryRow(`SELECT COUNT(*) FROM c_users WHERE email=?`, b.Email).Scan(&exist)
	if exist > 0 {
		writeJSON(w, 409, map[string]string{"error": "该邮箱已注册，请直接登录"})
		return
	}
	salt := cRandHex(16)
	now := time.Now().UnixMilli()
	uid := newUID(db)
	ip := clientIP(r)
	res, err := db.Exec(`INSERT INTO c_users(email,nick,pass_hash,salt,created_at,last_login,uid,reg_ip,last_ip)
		VALUES(?,?,?,?,?,?,?,?,?)`, b.Email, b.Nick, cHashPassword(b.Password, salt),
		salt, now, now, uid, ip, ip)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": "注册失败：" + err.Error()})
		return
	}
	id, _ := res.LastInsertId()
	tok, err := cCreateSession(db, id)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": "会话创建失败"})
		return
	}
	u := CUser{ID: id, UID: uid, Email: b.Email, Nick: b.Nick,
		RegIP: ip, LastIP: ip, CreatedAt: now, LastLogin: now}
	feishuNotify("超米 · 新用户注册",
		fmt.Sprintf("邮箱 %s\n昵称 %s\nUID %s\nIP %s", b.Email, b.Nick, uid, ip))
	writeJSON(w, 201, map[string]interface{}{"code": 0, "data": map[string]interface{}{"token": tok, "user": u}})
}

func handleAuthLogin(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	var b struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}
	b.Email = strings.ToLower(strings.TrimSpace(b.Email))
	var u CUser
	var hash, salt, avatar, bio, uid, regIp, lastIp sql.NullString
	err := db.QueryRow(`SELECT id,email,nick,pass_hash,salt,avatar,bio,created_at,uid,reg_ip,last_ip
		FROM c_users WHERE email=?`, b.Email).
		Scan(&u.ID, &u.Email, &u.Nick, &hash, &salt, &avatar, &bio, &u.CreatedAt,
			&uid, &regIp, &lastIp)
	if err != nil {
		writeJSON(w, 401, map[string]string{"error": "邮箱或密码错误"})
		return
	}
	want := cHashPassword(b.Password, salt.String)
	if subtle.ConstantTimeCompare([]byte(want), []byte(hash.String)) != 1 {
		writeJSON(w, 401, map[string]string{"error": "邮箱或密码错误"})
		return
	}
	tok, err := cCreateSession(db, u.ID)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": "会话创建失败"})
		return
	}
	now := time.Now().UnixMilli()
	ip := clientIP(r)
	_, _ = db.Exec(`UPDATE c_users SET last_login=?, last_ip=? WHERE id=?`, now, ip, u.ID)
	u.LastLogin = now
	u.Avatar = avatar.String
	u.Bio = bio.String
	u.UID = uid.String
	u.RegIP = regIp.String
	u.LastIP = ip
	_ = db.QueryRow(`SELECT COUNT(*) FROM c_posts WHERE user_id=?`, u.ID).Scan(&u.PostCount)
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]interface{}{"token": tok, "user": u}})
}

func handleAuthLogout(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	tok := ""
	if a := r.Header.Get("Authorization"); strings.HasPrefix(strings.ToLower(a), "bearer ") {
		tok = strings.TrimSpace(a[7:])
	}
	if tok != "" {
		_, _ = db.Exec(`DELETE FROM c_sessions WHERE token=?`, tok)
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": true})
}

func handleAuthMe(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	u := cAuthUser(db, r)
	if u == nil {
		writeJSON(w, 401, map[string]string{"error": "未登录"})
		return
	}
	_ = db.QueryRow(`SELECT COUNT(*) FROM c_posts WHERE user_id=?`, u.ID).Scan(&u.PostCount)
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": u})
}

func handleAuthProfile(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	u := cAuthUser(db, r)
	if u == nil {
		writeJSON(w, 401, map[string]string{"error": "未登录"})
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, 200, map[string]interface{}{"code": 0, "data": u})
		return
	}
	var b struct {
		Nick   string `json:"nick"`
		Bio    string `json:"bio"`
		Avatar string `json:"avatar"`
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}
	if n := strings.TrimSpace(b.Nick); n != "" {
		if len([]rune(n)) > 20 {
			n = string([]rune(n)[:20])
		}
		u.Nick = n
	}
	if len([]rune(b.Bio)) > 120 {
		b.Bio = string([]rune(b.Bio)[:120])
	}
	if _, err := db.Exec(`UPDATE c_users SET nick=?, bio=?, avatar=? WHERE id=?`,
		u.Nick, strings.TrimSpace(b.Bio), strings.TrimSpace(b.Avatar), u.ID); err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	u.Bio = strings.TrimSpace(b.Bio)
	u.Avatar = strings.TrimSpace(b.Avatar)
	// 昵称变了，同步已发帖子/评论的作者名
	_, _ = db.Exec(`UPDATE c_posts SET author=? WHERE user_id=?`, u.Nick, u.ID)
	_, _ = db.Exec(`UPDATE c_comments SET author=? WHERE user_id=?`, u.Nick, u.ID)
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": u})
}

// ===== 公众号文章 → 原生块 =====

// cHTMLToBlocks 把正文 HTML 拆成结构化块。客户端无需 WebView 即可原生渲染。
//
// 关键：只在「文本缓冲」里累积行内文字，遇到块级元素才 flush 成一段。
// 绝不能对容器取 n.Text()，否则容器 + 子节点会把同一段话取两遍（重复段）。
func cHTMLToBlocks(content *goquery.Selection) []CBlock {
	blocks := []CBlock{}
	content.Find("script,style,noscript,iframe").Remove()

	var buf strings.Builder
	flush := func() {
		t := strings.TrimSpace(strings.ReplaceAll(buf.String(), "\u00a0", " "))
		buf.Reset()
		if t != "" {
			blocks = append(blocks, CBlock{Type: "p", Text: t})
		}
	}

	var walk func(s *goquery.Selection)
	walk = func(s *goquery.Selection) {
		s.Contents().Each(func(_ int, n *goquery.Selection) {
			tag := goquery.NodeName(n)
			switch tag {
			case "#text":
				buf.WriteString(n.Text())
			case "br":
				buf.WriteString("\n")
			case "hr":
				flush()
			case "img":
				flush()
				src, _ := n.Attr("data-src")
				if strings.TrimSpace(src) == "" {
					src, _ = n.Attr("src")
				}
				src = strings.TrimSpace(src)
				if src != "" && !strings.HasPrefix(src, "data:") {
					alt, _ := n.Attr("alt")
					blocks = append(blocks, CBlock{Type: "img", Src: src, Alt: strings.TrimSpace(alt)})
				}
			case "h1", "h2":
				flush()
				if t := strings.TrimSpace(n.Text()); t != "" {
					blocks = append(blocks, CBlock{Type: "h2", Text: t})
				}
			case "h3", "h4", "h5", "h6":
				flush()
				if t := strings.TrimSpace(n.Text()); t != "" {
					blocks = append(blocks, CBlock{Type: "h3", Text: t})
				}
			case "blockquote":
				flush()
				if t := strings.TrimSpace(n.Text()); t != "" {
					blocks = append(blocks, CBlock{Type: "quote", Text: t})
				}
			case "pre":
				flush()
				if t := strings.TrimSpace(n.Text()); t != "" {
					blocks = append(blocks, CBlock{Type: "code", Text: t})
				}
			case "li":
				flush()
				if t := strings.TrimSpace(n.Text()); t != "" {
					blocks = append(blocks, CBlock{Type: "li", Text: t})
				}
			case "p", "div", "section", "article", "table", "tbody", "thead", "tr",
				"td", "th", "ul", "ol", "figure", "figcaption", "dl", "dd", "dt":
				// 容器：只递归，不取自身聚合文本（否则重复）
				walk(n)
				flush()
			default:
				// 行内元素（span/strong/em/a/font/…）：有子节点就继续下钻
				if n.Children().Length() > 0 {
					walk(n)
				} else {
					buf.WriteString(n.Text())
				}
			}
		})
	}
	walk(content)
	flush()

	// 去掉相邻重复段（公众号模板偶有重复嵌套）
	out := make([]CBlock, 0, len(blocks))
	var prev string
	for _, b := range blocks {
		if b.Type == "p" && b.Text == prev && b.Text != "" {
			continue
		}
		if b.Type == "p" {
			prev = b.Text
		} else {
			prev = ""
		}
		out = append(out, b)
	}
	if len(out) > 400 {
		out = out[:400]
	}
	return out
}

func handleArticleImport(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	var b struct {
		URL   string `json:"url"`
		Query string `json:"q"`
	}
	if r.Method == http.MethodPost {
		_ = json.NewDecoder(r.Body).Decode(&b)
	}
	u := strings.TrimSpace(b.URL)
	if u == "" {
		u = strings.TrimSpace(r.URL.Query().Get("url"))
	}
	if u == "" {
		writeJSON(w, 400, map[string]string{"error": "缺少 url"})
		return
	}
	// 只取元信息（含公众号名称/头像/发布时间），正文交给客户端浏览器渲染
	m, err := cParseArticleMeta(u)
	if err != nil {
		// 即使解析报错，能拿到的字段也一并返回，方便前端排查
		if m != nil && m.Title != "" {
			writeJSON(w, 200, map[string]interface{}{"code": 0, "data": metaToMap(m)})
			return
		}
		writeJSON(w, 502, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": metaToMap(m)})
}

// cParseArticleNative 解析并同时产出 blocks（旧路径，保留用于自测接口）
func cParseArticleNative(rawURL string) (*CArticle, []CBlock, error) {
	resp, err := cHttpGetUA(rawURL)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, nil, fmt.Errorf("远端返回 %d", resp.StatusCode)
	}
	doc, err := goquery.NewDocumentFromReader(resp.Body)
	if err != nil {
		return nil, nil, err
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
	blocks := cHTMLToBlocksV2(content)
	// 图片地址补全为绝对路径
	for i := range blocks {
		if blocks[i].Type == "img" {
			blocks[i].Src = cAbsURL(blocks[i].Src, rawURL)
		}
	}
	var imgs []string
	for _, b := range blocks {
		if b.Type == "img" {
			imgs = append(imgs, b.Src)
		}
	}
	if len(imgs) > 30 {
		imgs = imgs[:30]
	}
	a.Images = imgs

	var textParts []string
	for _, b := range blocks {
		if b.Type == "p" || b.Type == "h2" || b.Type == "h3" {
			textParts = append(textParts, b.Text)
		}
		if len([]rune(strings.Join(textParts, ""))) > 120 {
			break
		}
	}
	summary := strings.Join(textParts, " ")
	if len([]rune(summary)) > 120 {
		summary = string([]rune(summary)[:120]) + "…"
	}
	a.Summary = summary
	if a.Title == "" && len(blocks) == 0 {
		return nil, nil, fmt.Errorf("未能解析出内容，可能不是图文链接或已被反爬拦截")
	}
	html, _ := content.Html()
	a.Content = html
	return a, blocks, nil
}

// ===== 社区增强 =====

// cPostLiked 判断某用户是否点赞过
func cPostLiked(db *sql.DB, postID, userID int64) bool {
	if userID == 0 {
		return false
	}
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM c_likes WHERE post_id=? AND user_id=?`, postID, userID).Scan(&n)
	return n > 0
}

// cToggleLike 点赞/取消，幂等
func cToggleLike(db *sql.DB, postID, userID int64) (int, bool, error) {
	if userID == 0 {
		// 游客点赞：只加不减（保留老行为，避免破坏未登录体验）
		if _, err := db.Exec(`UPDATE c_posts SET likes=likes+1 WHERE id=?`, postID); err != nil {
			return 0, false, err
		}
		var n int
		_ = db.QueryRow(`SELECT likes FROM c_posts WHERE id=?`, postID).Scan(&n)
		return n, true, nil
	}
	liked := cPostLiked(db, postID, userID)
	if liked {
		_, err := db.Exec(`DELETE FROM c_likes WHERE post_id=? AND user_id=?`, postID, userID)
		if err != nil {
			return 0, false, err
		}
		_, _ = db.Exec(`UPDATE c_posts SET likes=GREATEST(likes-1,0) WHERE id=?`, postID)
	} else {
		_, err := db.Exec(`INSERT IGNORE INTO c_likes(post_id,user_id,created_at) VALUES(?,?,?)`,
			postID, userID, time.Now().UnixMilli())
		if err != nil {
			return 0, false, err
		}
		_, _ = db.Exec(`UPDATE c_posts SET likes=likes+1 WHERE id=?`, postID)
	}
	var n int
	_ = db.QueryRow(`SELECT likes FROM c_posts WHERE id=?`, postID).Scan(&n)
	return n, !liked, nil
}

// cListPostsEx 支持标签 / 搜索 / 我的 / 热度排序
func cListPostsEx(db *sql.DB, before int64, limit int, tag, q string, userID int64, sortBy string) ([]CPost, error) {
	if limit <= 0 || limit > 100 {
		limit = 30
	}
	where := []string{}
	args := []any{}

	if sortBy == "hot" {
		// 近 24 小时按「赞*3 + 浏览」热度排
		where = append(where, "created_at > ?")
		args = append(args, time.Now().Add(-24*time.Hour).UnixMilli())
	} else {
		where = append(where, "(?=0 OR created_at<?)")
		args = append(args, before, before)
	}
	if tag != "" {
		where = append(where, "tags LIKE ?")
		args = append(args, "%"+tag+"%")
	}
	if q != "" {
		where = append(where, "(title LIKE ? OR body LIKE ?)")
		args = append(args, "%"+q+"%", "%"+q+"%")
	}
	if userID > 0 {
		where = append(where, "user_id=?")
		args = append(args, userID)
	}
	orderBy := "created_at DESC"
	if sortBy == "hot" {
		orderBy = "(likes*3 + views) DESC, created_at DESC"
	}

	// ★ 用统一列清单，别再手写 SELECT 列表 —— 加字段时漏掉就会静默丢数据
	q2 := `SELECT ` + cPostColumns + `
		FROM c_posts WHERE ` + strings.Join(where, " AND ") + ` ORDER BY ` + orderBy + ` LIMIT ?`
	args = append(args, limit)

	rows, err := db.Query(q2, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []CPost{}
	for rows.Next() {
		p, err := cScanPost(rows.Scan)
		if err != nil {
			continue
		}
		out = append(out, p)
	}
	for i := range out {
		out[i].CommentCount = cCommentCount(db, out[i].ID)
		out[i].Liked = cPostLiked(db, out[i].ID, userID)
	}
	return out, nil
}

// cIncView 浏览计数（同一 IP 不做去重，量级小够用）
func cIncView(db *sql.DB, postID int64) {
	_, _ = db.Exec(`UPDATE c_posts SET views=views+1 WHERE id=?`, postID)
}

func handleCommunityDelete(db *sql.DB, w http.ResponseWriter, r *http.Request, id int64) {
	u := cAuthUser(db, r)
	if u == nil {
		writeJSON(w, 401, map[string]string{"error": "请先登录"})
		return
	}
	var owner int64
	if err := db.QueryRow(`SELECT user_id FROM c_posts WHERE id=?`, id).Scan(&owner); err != nil {
		writeJSON(w, 404, map[string]string{"error": "帖子不存在"})
		return
	}
	if owner != u.ID {
		writeJSON(w, 403, map[string]string{"error": "只能删除自己的帖子"})
		return
	}
	_, _ = db.Exec(`DELETE FROM c_comments WHERE post_id=?`, id)
	_, _ = db.Exec(`DELETE FROM c_likes WHERE post_id=?`, id)
	_, _ = db.Exec(`DELETE FROM c_posts WHERE id=?`, id)
	hub.broadcastJSON(map[string]interface{}{"type": "delete_post", "postId": id})
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": true})
}

func handleCommunityDeleteComment(db *sql.DB, w http.ResponseWriter, r *http.Request, commentID int64) {
	u := cAuthUser(db, r)
	if u == nil {
		writeJSON(w, 401, map[string]string{"error": "请先登录"})
		return
	}
	var owner, postID int64
	if err := db.QueryRow(`SELECT user_id,post_id FROM c_comments WHERE id=?`, commentID).Scan(&owner, &postID); err != nil {
		writeJSON(w, 404, map[string]string{"error": "评论不存在"})
		return
	}
	if owner != u.ID {
		writeJSON(w, 403, map[string]string{"error": "只能删除自己的评论"})
		return
	}
	_, _ = db.Exec(`DELETE FROM c_comments WHERE id=?`, commentID)
	hub.broadcastJSON(map[string]interface{}{"type": "delete_comment", "postId": postID, "commentId": commentID})
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": true})
}

// handleUserProfile 公开用户主页：资料 + 他的帖子
func handleUserProfile(db *sql.DB, w http.ResponseWriter, r *http.Request, id int64) {
	var u CUser
	var avatar, bio sql.NullString
	var uid sql.NullString
	err := db.QueryRow(`SELECT id,email,nick,avatar,bio,created_at,uid FROM c_users WHERE id=?`, id).
		Scan(&u.ID, &u.Email, &u.Nick, &avatar, &bio, &u.CreatedAt, &uid)
	if err != nil {
		writeJSON(w, 404, map[string]string{"error": "用户不存在"})
		return
	}
	u.Avatar = avatar.String
	u.Bio = bio.String
	u.UID = uid.String
	u.Email = "" // 不对外暴露邮箱
	u.RegIP = "" // 不对外暴露 IP
	u.LastIP = ""
	posts, _ := cListPostsEx(db, 0, 30, "", "", id, "")
	_ = db.QueryRow(`SELECT COUNT(*) FROM c_posts WHERE user_id=?`, id).Scan(&u.PostCount)
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]interface{}{
		"user": u, "posts": posts,
	}})
}

// handleCommunityTags 热门标签
func handleCommunityTags(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	rows, err := db.Query(`SELECT tags FROM c_posts WHERE tags IS NOT NULL AND tags<>'[]'
		ORDER BY created_at DESC LIMIT 500`)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()
	cnt := map[string]int{}
	for rows.Next() {
		var s sql.NullString
		if err := rows.Scan(&s); err != nil || !s.Valid {
			continue
		}
		var tags []string
		if json.Unmarshal([]byte(s.String), &tags) != nil {
			continue
		}
		for _, t := range tags {
			if t != "" {
				cnt[t]++
			}
		}
	}
	type tagItem struct {
		Tag   string `json:"tag"`
		Count int    `json:"count"`
	}
	out := []tagItem{}
	for k, v := range cnt {
		out = append(out, tagItem{k, v})
	}
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].Count > out[i].Count {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	if len(out) > 20 {
		out = out[:20]
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": out})
}

func cParseID(s string) int64 {
	n, _ := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
	return n
}
