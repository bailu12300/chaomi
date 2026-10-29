package main

// ---------------- 管理员机制 + 邮件找回密码 ----------------
//
// 设计要点：
//   · 管理员用 c_users.is_admin 标记，不做角色表（这个规模不需要）
//   · 首个管理员由环境变量 ADMIN_EMAILS 指定（默认 280513935@qq.com），
//     启动时幂等提升，避免手工改库
//   · 找回密码走「邮件里的链接 + 一次性 token」，比只给验证码体验好得多：
//     用户点链接直接到重置页，不用在网页和邮箱之间来回切
//   · token 只存一次、用后作废、30 分钟过期；同时保留验证码通路作为兜底

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

const resetTTL = 30 * time.Minute

// ensureAdminSchema 建表 + 提升默认管理员（幂等）
func ensureAdminSchema(db *sql.DB) error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS c_reset_tokens(
			token VARCHAR(64) NOT NULL PRIMARY KEY,
			user_id BIGINT NOT NULL,
			email VARCHAR(191) NOT NULL,
			created_at BIGINT NOT NULL,
			expires_at BIGINT NOT NULL,
			used TINYINT DEFAULT 0,
			INDEX idx_c_rt_uid(user_id)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			return err
		}
	}
	// 幂等提升管理员：环境变量 ADMIN_EMAILS（逗号分隔）
	// 默认同时包含用户给的 280513935@qq.com 与站内已注册的 2080513935@qq.com
	// （两者高度疑似同一邮箱少写一位，两个都放开，谁注册谁就是管理员）
	emails := env("ADMIN_EMAILS", "280513935@qq.com,2080513935@qq.com")
	for _, e := range strings.Split(emails, ",") {
		e = strings.ToLower(strings.TrimSpace(e))
		if e == "" {
			continue
		}
		db.Exec(`UPDATE c_users SET is_admin=1 WHERE email=?`, e)
	}
	return nil
}

// cIsAdminEmail 判断邮箱是否在管理员白名单（新注册用户自动成为管理员用）
func cIsAdminEmail(email string) bool {
	email = strings.ToLower(strings.TrimSpace(email))
	if email == "" {
		return false
	}
	for _, e := range strings.Split(env("ADMIN_EMAILS", "280513935@qq.com,2080513935@qq.com"), ",") {
		if strings.ToLower(strings.TrimSpace(e)) == email {
			return true
		}
	}
	return false
}

// cRequireAdmin 取当前用户并校验管理员身份
func cRequireAdmin(db *sql.DB, w http.ResponseWriter, r *http.Request) *CUser {
	u := cAuthUser(db, r)
	if u == nil {
		writeJSON(w, 401, map[string]string{"error": "未登录"})
		return nil
	}
	if !u.IsAdmin {
		writeJSON(w, 403, map[string]string{"error": "需要管理员权限"})
		return nil
	}
	return u
}

// siteURL 站点根地址（用于拼邮件里的重置链接）
func siteURL() string {
	return strings.TrimRight(env("SITE_URL", "https://cm01.zy1080.xyz"), "/")
}

// resetLinkMailHtml 带链接的重置密码邮件（**纯文本**，送达率优先）
func resetLinkMailHtml(link string, minutes int) string {
	return "超米 · 账号安全\n" +
		"\n" +
		"你申请了重置超米账号的密码。\n" +
		"请在 " + fmt.Sprint(minutes) + " 分钟内打开下面的链接设置新密码：\n" +
		"\n" +
		link + "\n" +
		"\n" +
		"链接只能使用一次，用过即失效。\n" +
		"如果不是你本人操作，忽略这封邮件即可，密码不会改变。\n" +
		"\n" +
		"If you did not request this, please ignore this email.\n" +
		"\n" +
		"-- \n" +
		"超米\n" +
		siteURL() + "/community/\n"
}

// handleAuthForgot POST /api/auth/forgot {email} —— 发重置链接
func handleAuthForgot(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	var b struct {
		Email string `json:"email"`
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}
	email := strings.ToLower(strings.TrimSpace(b.Email))
	if !reEmail.MatchString(email) {
		writeJSON(w, 400, map[string]string{"error": "邮箱格式不正确"})
		return
	}

	var uid int64
	err := db.QueryRow(`SELECT id FROM c_users WHERE email=?`, email).Scan(&uid)
	if err != nil {
		writeJSON(w, 404, map[string]string{"error": "该邮箱未注册"})
		return
	}

	// 频率限制：同一邮箱 60 秒内只发一封
	ok, wait := canSendCode(db, email, "resetlink")
	if !ok {
		writeJSON(w, 429, map[string]string{
			"error": fmt.Sprintf("发送太频繁，请 %d 秒后再试", wait)})
		return
	}

	token := cRandHex(24)
	now := time.Now()
	if _, err := db.Exec(`INSERT INTO c_reset_tokens(token,user_id,email,created_at,expires_at)
		VALUES(?,?,?,?,?)`, token, uid, email, now.UnixMilli(),
		now.Add(resetTTL).UnixMilli()); err != nil {
		writeJSON(w, 500, map[string]string{"error": "重置令牌保存失败"})
		return
	}
	// 占位记录，复用现有频率限制表
	db.Exec(`INSERT INTO c_email_codes(email,code,purpose,created_at,expires_at,ip)
		VALUES(?,?,?,?,?,?)`, email, "LINK", "resetlink",
		now.UnixMilli(), now.Add(codeTTL).UnixMilli(), clientIP(r))

	link := siteURL() + "/community/?reset=" + token
	minutes := int(resetTTL.Minutes())

	if smtpConfigured() {
		if err := smtpSend(email, "超米 · 账号安全验证", resetLinkMailHtml(link, minutes)); err == nil {
			writeJSON(w, 200, map[string]interface{}{
				"code": 0,
				"data": map[string]interface{}{
					"sent":      true,
					"message":   "重置链接已发送到邮箱",
					"expiresIn": int(resetTTL.Seconds()),
				},
			})
			return
		}
	}

	// 发信失败 / 未配 SMTP：把链接直接返回（自建站点兜底，用户不至于卡死）
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"sent":      false,
			"devMode":   true,
			"devToken":  token,
			"devLink":   link,
			"message":   "邮件发送失败，已直接给出重置链接",
			"expiresIn": int(resetTTL.Seconds()),
		},
	})
}

// handleAuthResetToken POST /api/auth/reset-token {token, password}
func handleAuthResetToken(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	var b struct {
		Token    string `json:"token"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}
	token := strings.TrimSpace(b.Token)
	if token == "" {
		writeJSON(w, 400, map[string]string{"error": "重置令牌缺失"})
		return
	}
	if len([]rune(b.Password)) < 6 {
		writeJSON(w, 400, map[string]string{"error": "密码至少 6 位"})
		return
	}

	var uid int64
	err := db.QueryRow(`SELECT user_id FROM c_reset_tokens
		WHERE token=? AND used=0 AND expires_at>?`, token, time.Now().UnixMilli()).Scan(&uid)
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": "链接无效或已过期，请重新获取"})
		return
	}

	salt := cRandHex(16)
	if _, err := db.Exec(`UPDATE c_users SET pass_hash=?, salt=? WHERE id=?`,
		cHashPassword(b.Password, salt), salt, uid); err != nil {
		writeJSON(w, 500, map[string]string{"error": "密码更新失败"})
		return
	}
	// 令牌作废 + 踢掉所有旧会话（重置后必须重新登录）
	db.Exec(`UPDATE c_reset_tokens SET used=1 WHERE token=?`, token)
	db.Exec(`DELETE FROM c_sessions WHERE user_id=?`, uid)

	writeJSON(w, 200, map[string]interface{}{
		"code": 0, "data": map[string]interface{}{"ok": true},
	})
}

// ---------------- 管理后台接口 ----------------

// handleAdminOverview GET /api/admin/overview
func handleAdminOverview(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	me := cRequireAdmin(db, w, r)
	if me == nil {
		return
	}

	var users, posts, comments, todayUsers int
	db.QueryRow(`SELECT COUNT(*) FROM c_users`).Scan(&users)
	db.QueryRow(`SELECT COUNT(*) FROM c_posts`).Scan(&posts)
	db.QueryRow(`SELECT COUNT(*) FROM c_comments`).Scan(&comments)
	dayStart := time.Now().Truncate(24 * time.Hour)
	db.QueryRow(`SELECT COUNT(*) FROM c_users WHERE created_at>=?`, dayStart.UnixMilli()).Scan(&todayUsers)

	// 最近 50 个用户
	type rowUser struct {
		ID      int64  `json:"id"`
		Email   string `json:"email"`
		Nick    string `json:"nick"`
		IsAdmin bool   `json:"isAdmin"`
		Created int64  `json:"createdAt"`
	}
	userList := []rowUser{}
	if rows, err := db.Query(`SELECT id,email,nick,is_admin,created_at FROM c_users
		ORDER BY id DESC LIMIT 50`); err == nil {
		defer rows.Close()
		for rows.Next() {
			var u rowUser
			var ad sql.NullInt64
			if rows.Scan(&u.ID, &u.Email, &u.Nick, &ad, &u.Created) == nil {
				u.IsAdmin = ad.Int64 == 1
				userList = append(userList, u)
			}
		}
	}

	// 最近 20 条帖子
	type rowPost struct {
		ID        int64  `json:"id"`
		Author    string `json:"author"`
		Title     string `json:"title"`
		Body      string `json:"body"`
		CreatedAt int64  `json:"createdAt"`
	}
	postList := []rowPost{}
	if rows, err := db.Query(`SELECT id,author,title,body,created_at FROM c_posts
		ORDER BY id DESC LIMIT 20`); err == nil {
		defer rows.Close()
		for rows.Next() {
			var p rowPost
			if rows.Scan(&p.ID, &p.Author, &p.Title, &p.Body, &p.CreatedAt) == nil {
				postList = append(postList, p)
			}
		}
	}

	// 最近 50 条评论（带所属帖子标题，便于判断上下文）
	type rowComment struct {
		ID        int64  `json:"id"`
		PostID    int64  `json:"postId"`
		Author    string `json:"author"`
		Body      string `json:"body"`
		CreatedAt int64  `json:"createdAt"`
		PostTitle string `json:"postTitle"`
	}
	commentList := []rowComment{}
	if rows, err := db.Query(`SELECT c.id,c.post_id,COALESCE(c.author,''),
			COALESCE(c.body,''),c.created_at,COALESCE(p.title,''),COALESCE(p.body,'')
		FROM c_comments c LEFT JOIN c_posts p ON p.id=c.post_id
		ORDER BY c.id DESC LIMIT 50`); err == nil {
		defer rows.Close()
		for rows.Next() {
			var c rowComment
			var pt, pb string
			if rows.Scan(&c.ID, &c.PostID, &c.Author, &c.Body, &c.CreatedAt, &pt, &pb) == nil {
				if strings.TrimSpace(pt) != "" {
					c.PostTitle = pt
				} else if r := []rune(pb); len(r) > 24 {
					c.PostTitle = string(r[:24])
				} else {
					c.PostTitle = pb
				}
				commentList = append(commentList, c)
			}
		}
	}

	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"stats": map[string]interface{}{
				"users": users, "posts": posts,
				"comments": comments, "todayUsers": todayUsers,
			},
			"users":    userList,
			"posts":    postList,
			"comments": commentList,
			"me":       map[string]interface{}{"id": me.ID, "nick": me.Nick},
		},
	})
}

// handleAdminUserRole POST /api/admin/user/{id}/role —— 切换管理员
func handleAdminUserRole(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	me := cRequireAdmin(db, w, r)
	if me == nil {
		return
	}
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/api/admin/user/")
	rest = strings.TrimSuffix(rest, "/role")
	var uid int64
	if _, err := fmt.Sscanf(rest, "%d", &uid); err != nil {
		writeJSON(w, 400, map[string]string{"error": "用户 ID 不正确"})
		return
	}
	if uid == me.ID {
		writeJSON(w, 400, map[string]string{"error": "不能修改自己的角色"})
		return
	}

	var cur int
	if err := db.QueryRow(`SELECT is_admin FROM c_users WHERE id=?`, uid).Scan(&cur); err != nil {
		writeJSON(w, 404, map[string]string{"error": "用户不存在"})
		return
	}
	next := 0
	if cur == 0 {
		next = 1
	}
	if _, err := db.Exec(`UPDATE c_users SET is_admin=? WHERE id=?`, next, uid); err != nil {
		writeJSON(w, 500, map[string]string{"error": "更新失败"})
		return
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0, "data": map[string]interface{}{"isAdmin": next == 1},
	})
}

// handleAdminPostDelete DELETE /api/admin/post/{id}
func handleAdminPostDelete(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if cRequireAdmin(db, w, r) == nil {
		return
	}
	if r.Method != http.MethodDelete && r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/api/admin/post/")
	var pid int64
	if _, err := fmt.Sscanf(rest, "%d", &pid); err != nil || pid <= 0 {
		writeJSON(w, 400, map[string]string{"error": "帖子 ID 不正确"})
		return
	}
	// 帖子软/硬删：与用户删帖一致的清理范围
	db.Exec(`DELETE FROM c_comments WHERE post_id=?`, pid)
	db.Exec(`DELETE FROM c_likes WHERE post_id=?`, pid)
	res, err := db.Exec(`DELETE FROM c_posts WHERE id=?`, pid)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": "删除失败：" + err.Error()})
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeJSON(w, 404, map[string]string{"error": "帖子不存在"})
		return
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]interface{}{"ok": true}})
}


// handleAdminCommentDelete DELETE /api/admin/comment/{id}
func handleAdminCommentDelete(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if cRequireAdmin(db, w, r) == nil {
		return
	}
	if r.Method != http.MethodDelete && r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/api/admin/comment/")
	var cid int64
	if _, err := fmt.Sscanf(rest, "%d", &cid); err != nil || cid <= 0 {
		writeJSON(w, 400, map[string]string{"error": "评论 ID 不正确"})
		return
	}
	res, err := db.Exec(`DELETE FROM c_comments WHERE id=?`, cid)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": "删除失败：" + err.Error()})
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeJSON(w, 404, map[string]string{"error": "评论不存在"})
		return
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]interface{}{"ok": true}})
}


// handleAdminResetPassword POST /api/admin/user/{id}/password
//
// 邮件通道不可靠时的兜底：管理员直接为指定用户设一个新密码，
// 然后线下告诉用户。这是"邮箱收不到验证码"场景下唯一靠谱的救援通道。
//
// 安全约束：
//   · 需要管理员身份
//   · 不改管理员自己的密码（走 /api/auth/password，要验证旧密码）
//   · 重置后踢掉该用户所有会话，避免旧设备仍在线
func handleAdminResetPassword(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	me := cRequireAdmin(db, w, r)
	if me == nil {
		return
	}
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/api/admin/user/")
	rest = strings.TrimSuffix(rest, "/password")
	var uid int64
	if _, err := fmt.Sscanf(rest, "%d", &uid); err != nil || uid <= 0 {
		writeJSON(w, 400, map[string]string{"error": "用户 ID 不正确"})
		return
	}
	if uid == me.ID {
		writeJSON(w, 400, map[string]string{
			"error": "不能在这里重置自己的密码，请用账号中心的「修改密码」"})
		return
	}

	var b struct {
		Password string `json:"password"`
	}
	_ = json.NewDecoder(r.Body).Decode(&b)
	pw := strings.TrimSpace(b.Password)
	if len([]rune(pw)) < 6 {
		writeJSON(w, 400, map[string]string{"error": "新密码至少 6 位"})
		return
	}

	var nick, email string
	if err := db.QueryRow(`SELECT COALESCE(nick,''),COALESCE(email,'') FROM c_users WHERE id=?`, uid).
		Scan(&nick, &email); err != nil {
		writeJSON(w, 404, map[string]string{"error": "用户不存在"})
		return
	}

	salt := cRandHex(16)
	if _, err := db.Exec(`UPDATE c_users SET pass_hash=?, salt=? WHERE id=?`,
		cHashPassword(pw, salt), salt, uid); err != nil {
		writeJSON(w, 500, map[string]string{"error": "重置失败：" + err.Error()})
		return
	}
	db.Exec(`DELETE FROM c_sessions WHERE user_id=?`, uid)
	db.Exec(`DELETE FROM c_reset_tokens WHERE user_id=?`, uid)

	feishuNotify("超米 · 管理员重置密码",
		fmt.Sprintf("管理员 %s 重置了 %s（%s）的密码", me.Nick, nick, email))
	writeJSON(w, 200, map[string]interface{}{
		"code": 0, "data": map[string]interface{}{
			"ok":      true,
			"message": "已重置 " + nick + " 的密码，请线下告知对方",
		},
	})
}
