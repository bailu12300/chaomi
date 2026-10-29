package main

// ---------------- 账号体系延展 ----------------
//
// 补齐一个「账号」该有的基础能力（之前只有注册/登录/改资料）：
//   · 修改密码（登录态，需验证旧密码）
//   · 我的内容：帖子 / 评论 / 点赞
//   · 登录设备管理（列出会话、踢掉其它设备）
//   · 注销账号（二次确认 + 密码校验，级联清理）

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// ---------------- 修改密码 ----------------

// handleChangePassword POST /api/auth/password {oldPassword, newPassword}
func handleChangePassword(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	me := cAuthUser(db, r)
	if me == nil {
		writeJSON(w, 401, map[string]string{"error": "未登录"})
		return
	}
	var b struct {
		Old string `json:"oldPassword"`
		New string `json:"newPassword"`
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}
	if len([]rune(b.New)) < 6 {
		writeJSON(w, 400, map[string]string{"error": "新密码至少 6 位"})
		return
	}
	if b.Old == b.New {
		writeJSON(w, 400, map[string]string{"error": "新密码不能与旧密码相同"})
		return
	}

	var hash, salt sql.NullString
	if err := db.QueryRow(`SELECT pass_hash,salt FROM c_users WHERE id=?`, me.ID).
		Scan(&hash, &salt); err != nil {
		writeJSON(w, 404, map[string]string{"error": "用户不存在"})
		return
	}
	if cHashPassword(b.Old, salt.String) != hash.String {
		writeJSON(w, 400, map[string]string{"error": "原密码不正确"})
		return
	}

	newSalt := cRandHex(16)
	if _, err := db.Exec(`UPDATE c_users SET pass_hash=?, salt=? WHERE id=?`,
		cHashPassword(b.New, newSalt), newSalt, me.ID); err != nil {
		writeJSON(w, 500, map[string]string{"error": "密码更新失败"})
		return
	}
	// 改密码后踢掉其它设备的会话，当前设备保留
	cur := r.Header.Get("Authorization")
	cur = strings.TrimSpace(strings.TrimPrefix(strings.ToLower(cur), "bearer "))
	curTok := ""
	if a := r.Header.Get("Authorization"); strings.HasPrefix(strings.ToLower(a), "bearer ") {
		curTok = strings.TrimSpace(a[7:])
	}
	if curTok != "" {
		db.Exec(`DELETE FROM c_sessions WHERE user_id=? AND token<>?`, me.ID, curTok)
	} else {
		db.Exec(`DELETE FROM c_sessions WHERE user_id=?`, me.ID)
	}
	_ = cur

	writeJSON(w, 200, map[string]interface{}{
		"code": 0, "data": map[string]interface{}{
			"ok":      true,
			"message": "密码已修改，其它设备已退出登录",
		},
	})
}

// ---------------- 我的内容 ----------------

type myPost struct {
	ID           int64  `json:"id"`
	Type         string `json:"type"`
	Title        string `json:"title"`
	Body         string `json:"body"`
	Cover        string `json:"cover"`
	CreatedAt    int64  `json:"createdAt"`
	Likes        int    `json:"likes"`
	CommentCount int    `json:"commentCount"`
	Views        int    `json:"views"`
}

// handleMyContent GET /api/me/contents?kind=posts|comments|likes&limit=50
func handleMyContent(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	me := cAuthUser(db, r)
	if me == nil {
		writeJSON(w, 401, map[string]string{"error": "未登录"})
		return
	}
	kind := strings.TrimSpace(r.URL.Query().Get("kind"))
	if kind == "" {
		kind = "posts"
	}
	limit := 50
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := parseIntSafe(v); err == nil && n > 0 && n <= 200 {
			limit = n
		}
	}

	switch kind {
	case "posts":
		out := []myPost{}
		// ★ c_posts 表**没有** comment_count 列（评论数是动态统计的），
		//   之前直接 SELECT 它会 SQL 报错，而错误被静默吞掉 → 前端永远看到"没有内容"。
		//   这里改成子查询统计，并且**不再忽略错误**。
		rows, err := db.Query(`SELECT p.id,p.type,COALESCE(p.title,''),COALESCE(p.body,''),
				COALESCE(p.cover,''),p.created_at,COALESCE(p.likes,0),
				(SELECT COUNT(*) FROM c_comments c WHERE c.post_id=p.id),
				COALESCE(p.views,0)
			FROM c_posts p WHERE p.user_id=? ORDER BY p.id DESC LIMIT ?`, me.ID, limit)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "查询失败：" + err.Error()})
			return
		}
		defer rows.Close()
		for rows.Next() {
			var p myPost
			if e := rows.Scan(&p.ID, &p.Type, &p.Title, &p.Body, &p.Cover,
				&p.CreatedAt, &p.Likes, &p.CommentCount, &p.Views); e == nil {
				out = append(out, p)
			}
		}
		writeJSON(w, 200, map[string]interface{}{
			"code": 0, "data": map[string]interface{}{"kind": kind, "list": out, "total": len(out)}})

	case "comments":
		type myComment struct {
			ID        int64  `json:"id"`
			PostID    int64  `json:"postId"`
			Body      string `json:"body"`
			CreatedAt int64  `json:"createdAt"`
			PostTitle string `json:"postTitle"`
		}
		out := []myComment{}
		rows, err := db.Query(`SELECT c.id,c.post_id,COALESCE(c.body,''),c.created_at,
				COALESCE(p.title,''),COALESCE(p.body,'')
			FROM c_comments c LEFT JOIN c_posts p ON p.id=c.post_id
			WHERE c.user_id=? ORDER BY c.id DESC LIMIT ?`, me.ID, limit)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "查询失败：" + err.Error()})
			return
		}
		{
			defer rows.Close()
			for rows.Next() {
				var c myComment
				var pt, pb string
				if rows.Scan(&c.ID, &c.PostID, &c.Body, &c.CreatedAt, &pt, &pb) == nil {
					if strings.TrimSpace(pt) != "" {
						c.PostTitle = pt
					} else if len([]rune(pb)) > 30 {
						c.PostTitle = string([]rune(pb)[:30])
					} else {
						c.PostTitle = pb
					}
					out = append(out, c)
				}
			}
		}
		writeJSON(w, 200, map[string]interface{}{
			"code": 0, "data": map[string]interface{}{"kind": kind, "list": out, "total": len(out)}})

	case "likes":
		out := []myPost{}
		rows, err := db.Query(`SELECT p.id,p.type,COALESCE(p.title,''),COALESCE(p.body,''),
				COALESCE(p.cover,''),p.created_at,COALESCE(p.likes,0),
				(SELECT COUNT(*) FROM c_comments c WHERE c.post_id=p.id),
				COALESCE(p.views,0)
			FROM c_likes l JOIN c_posts p ON p.id=l.post_id
			WHERE l.user_id=? ORDER BY l.created_at DESC LIMIT ?`, me.ID, limit)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "查询失败：" + err.Error()})
			return
		}
		defer rows.Close()
		for rows.Next() {
			var p myPost
			if e := rows.Scan(&p.ID, &p.Type, &p.Title, &p.Body, &p.Cover,
				&p.CreatedAt, &p.Likes, &p.CommentCount, &p.Views); e == nil {
				out = append(out, p)
			}
		}
		writeJSON(w, 200, map[string]interface{}{
			"code": 0, "data": map[string]interface{}{"kind": kind, "list": out, "total": len(out)}})

	default:
		writeJSON(w, 400, map[string]string{"error": "kind 只支持 posts / comments / likes"})
	}
}

// ---------------- 登录设备管理 ----------------

// handleSessions GET /api/auth/sessions，DELETE /api/auth/sessions（踢掉其它设备）
func handleSessions(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	me := cAuthUser(db, r)
	if me == nil {
		writeJSON(w, 401, map[string]string{"error": "未登录"})
		return
	}
	curTok := ""
	if a := r.Header.Get("Authorization"); strings.HasPrefix(strings.ToLower(a), "bearer ") {
		curTok = strings.TrimSpace(a[7:])
	}

	if r.Method == http.MethodDelete || r.Method == http.MethodPost {
		res, err := db.Exec(`DELETE FROM c_sessions WHERE user_id=? AND token<>?`, me.ID, curTok)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": "清理失败"})
			return
		}
		n, _ := res.RowsAffected()
		writeJSON(w, 200, map[string]interface{}{
			"code": 0, "data": map[string]interface{}{
				"ok": true, "kicked": n,
				"message": fmt.Sprintf("已退出 %d 台其它设备", n),
			}})
		return
	}

	type session struct {
		Token      string `json:"tokenMasked"`
		CreatedAt  int64  `json:"createdAt"`
		ExpiresAt  int64  `json:"expiresAt"`
		Current    bool   `json:"current"`
	}
	out := []session{}
	rows, err := db.Query(`SELECT token,created_at,expires_at FROM c_sessions
		WHERE user_id=? AND expires_at>? ORDER BY created_at DESC LIMIT 50`,
		me.ID, time.Now().UnixMilli())
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var s session
			var tok string
			if rows.Scan(&tok, &s.CreatedAt, &s.ExpiresAt) == nil {
				// 不返回完整 token（防泄漏），只给头尾便于识别
				if len(tok) > 12 {
					s.Token = tok[:6] + "…" + tok[len(tok)-4:]
				} else {
					s.Token = "…"
				}
				s.Current = (tok == curTok)
				out = append(out, s)
			}
		}
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0, "data": map[string]interface{}{"list": out, "total": len(out)}})
}

// ---------------- 注销账号 ----------------

// handleDeleteAccount POST /api/auth/delete {password, confirm}
func handleDeleteAccount(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	me := cAuthUser(db, r)
	if me == nil {
		writeJSON(w, 401, map[string]string{"error": "未登录"})
		return
	}
	var b struct {
		Password string `json:"password"`
		Confirm  string `json:"confirm"`
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}
	if strings.TrimSpace(b.Confirm) != "注销" {
		writeJSON(w, 400, map[string]string{"error": "请输入「注销」二字确认"})
		return
	}
	// 管理员账号不允许自助注销，避免把自己锁在门外
	if me.IsAdmin {
		writeJSON(w, 403, map[string]string{"error": "管理员账号不能自助注销"})
		return
	}

	var hash, salt sql.NullString
	if err := db.QueryRow(`SELECT pass_hash,salt FROM c_users WHERE id=?`, me.ID).
		Scan(&hash, &salt); err != nil {
		writeJSON(w, 404, map[string]string{"error": "用户不存在"})
		return
	}
	if cHashPassword(b.Password, salt.String) != hash.String {
		writeJSON(w, 400, map[string]string{"error": "密码不正确"})
		return
	}

	// 级联清理：先记录帖子 id 用于删评论
	postIDs := []int64{}
	if rows, err := db.Query(`SELECT id FROM c_posts WHERE user_id=?`, me.ID); err == nil {
		for rows.Next() {
			var id int64
			if rows.Scan(&id) == nil {
				postIDs = append(postIDs, id)
			}
		}
		rows.Close()
	}
	for _, pid := range postIDs {
		db.Exec(`DELETE FROM c_comments WHERE post_id=?`, pid)
		db.Exec(`DELETE FROM c_likes WHERE post_id=?`, pid)
	}
	db.Exec(`DELETE FROM c_posts WHERE user_id=?`, me.ID)      // 自己的帖子
	db.Exec(`DELETE FROM c_comments WHERE user_id=?`, me.ID)   // 自己在别人帖下的评论
	db.Exec(`DELETE FROM c_likes WHERE user_id=?`, me.ID)      // 自己的点赞
	db.Exec(`DELETE FROM c_sessions WHERE user_id=?`, me.ID)   // 所有会话
	db.Exec(`DELETE FROM c_reset_tokens WHERE user_id=?`, me.ID)
	if _, err := db.Exec(`DELETE FROM c_users WHERE id=?`, me.ID); err != nil {
		writeJSON(w, 500, map[string]string{"error": "注销失败：" + err.Error()})
		return
	}

	feishuNotify("超米 · 账号注销",
		fmt.Sprintf("用户 %s（%s）已注销\n删除帖子 %d 条", me.Nick, me.Email, len(postIDs)))
	writeJSON(w, 200, map[string]interface{}{
		"code": 0, "data": map[string]interface{}{
			"ok": true, "message": "账号已注销",
		}})
}

