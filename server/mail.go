package main

// ---------------- 验证码 / 发信 / 飞书通知 / 用户资料增强 ----------------
//
// 发信：SMTP（QQ/163/企业邮 均可），配置全走环境变量，源码零凭据。
//   SMTP_HOST / SMTP_PORT / SMTP_USER / SMTP_PASS / SMTP_FROM / SMTP_TLS
// 未配置时：注册不强制验证码（内测模式），并在响应里标注，避免把用户卡死。
//
// 飞书：FEISHU_WEBHOOK（可选 FEISHU_SECRET 做签名校验）

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/smtp"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// ===== 配置 =====

func logf(format string, args ...interface{}) {
	log.Printf(format, args...)
}

func env(k, def string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return def
}

func smtpConfigured() bool {
	return env("SMTP_HOST", "") != "" && env("SMTP_USER", "") != ""
}

func feishuConfigured() bool {
	return env("FEISHU_WEBHOOK", "") != ""
}

// ===== 验证码 =====

const codeTTL = 10 * time.Minute

func ensureMailSchema(db *sql.DB) error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS c_email_codes(
			id BIGINT AUTO_INCREMENT PRIMARY KEY,
			email VARCHAR(191) NOT NULL,
			code VARCHAR(12) NOT NULL,
			purpose VARCHAR(16) NOT NULL,
			created_at BIGINT NOT NULL,
			expires_at BIGINT NOT NULL,
			used TINYINT DEFAULT 0,
			ip VARCHAR(64) DEFAULT '',
			INDEX idx_c_ec(email,purpose)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			return err
		}
	}
	// 用户表补字段
	for _, c := range [][3]string{
		{"c_users", "uid", "uid VARCHAR(16) DEFAULT ''"},
		{"c_users", "reg_ip", "reg_ip VARCHAR(64) DEFAULT ''"},
		{"c_users", "last_ip", "last_ip VARCHAR(64) DEFAULT ''"},
	} {
		if err := cAddColumn(db, c[0], c[1], c[2]); err != nil {
			return err
		}
	}
	// 老用户没有 UID 的补一个（加字段之前的账号）
	rows, err := db.Query(`SELECT id FROM c_users WHERE uid IS NULL OR uid=''`)
	if err == nil {
		var ids []int64
		for rows.Next() {
			var id int64
			if rows.Scan(&id) == nil {
				ids = append(ids, id)
			}
		}
		rows.Close()
		for _, id := range ids {
			uid := newUID(db)
			if _, err := db.Exec(`UPDATE c_users SET uid=? WHERE id=?`, uid, id); err == nil {
				logf("[mail] 为老用户 #%d 补 UID: %s", id, uid)
			}
		}
	}
	return nil
}

// 6 位数字验证码
func newCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(900000))
	if err != nil {
		return "123456"
	}
	return strconv.FormatInt(n.Int64()+100000, 10)
}

// 唯一用户 ID：CM + 6 位数字（便于口头分享）
func newUID(db *sql.DB) string {
	for i := 0; i < 30; i++ {
		n, _ := rand.Int(rand.Reader, big.NewInt(900000))
		uid := "CM" + strconv.FormatInt(n.Int64()+100000, 10)
		var c int
		_ = db.QueryRow(`SELECT COUNT(*) FROM c_users WHERE uid=?`, uid).Scan(&c)
		if c == 0 {
			return uid
		}
	}
	return "CM" + strconv.FormatInt(time.Now().UnixNano()%1000000, 10)
}

func clientIP(r *http.Request) string {
	if v := r.Header.Get("X-Forwarded-For"); v != "" {
		if i := strings.Index(v, ","); i > 0 {
			return strings.TrimSpace(v[:i])
		}
		return strings.TrimSpace(v)
	}
	if v := r.Header.Get("X-Real-IP"); v != "" {
		return strings.TrimSpace(v)
	}
	ip := r.RemoteAddr
	if i := strings.LastIndex(ip, ":"); i > 0 {
		ip = ip[:i]
	}
	return ip
}

// 同一邮箱 60 秒内只能发一次，防刷
func canSendCode(db *sql.DB, email, purpose string) (bool, int) {
	var last int64
	_ = db.QueryRow(`SELECT COALESCE(MAX(created_at),0) FROM c_email_codes
		WHERE email=? AND purpose=?`, email, purpose).Scan(&last)
	if last == 0 {
		return true, 0
	}
	elapsed := time.Now().UnixMilli() - last
	if elapsed < 60_000 {
		return false, int((60_000 - elapsed) / 1000)
	}
	return true, 0
}

// ===== 发信 =====

func smtpSend(to, subject, body string) error {
	host := env("SMTP_HOST", "")
	port := env("SMTP_PORT", "465")
	user := env("SMTP_USER", "")
	pass := env("SMTP_PASS", "")
	from := env("SMTP_FROM", user)
	if from == "" {
		from = user
	}
	useTLS := env("SMTP_TLS", "true") != "false"
	addr := net.JoinHostPort(host, port)

	// 头部（主题做 RFC2047 编码，避免中文乱码）
	subj := "=?UTF-8?B?" + base64.StdEncoding.EncodeToString([]byte(subject)) + "?="
	msg := "From: 超米 <" + from + ">\r\n" +
		"To: " + to + "\r\n" +
		"Subject: " + subj + "\r\n" +
		"MIME-Version: 1.0\r\n" +
		"Content-Type: text/html; charset=UTF-8\r\n" +
		"Date: " + time.Now().Format(time.RFC1123Z) + "\r\n" +
		"\r\n" + body + "\r\n"

	if useTLS {
		// 465 隐式 TLS
		conf := &tls.Config{ServerName: host, InsecureSkipVerify: false}
		conn, err := tls.Dial("tcp", addr, conf)
		if err != nil {
			return fmt.Errorf("连接 %s 失败: %w", addr, err)
		}
		c, err := smtp.NewClient(conn, host)
		if err != nil {
			conn.Close()
			return err
		}
		defer c.Close()
		if ok, _ := c.Extension("AUTH"); ok {
			if err := c.Auth(smtp.PlainAuth("", user, pass, host)); err != nil {
				return fmt.Errorf("认证失败: %w", err)
			}
		}
		if err := c.Mail(from); err != nil {
			return err
		}
		if err := c.Rcpt(to); err != nil {
			return err
		}
		w, err := c.Data()
		if err != nil {
			return err
		}
		if _, err := w.Write([]byte(msg)); err != nil {
			return err
		}
		w.Close()
		return c.Quit()
	}

	// 587 STARTTLS
	c, err := smtp.Dial(addr)
	if err != nil {
		return fmt.Errorf("连接 %s 失败: %w", addr, err)
	}
	defer c.Close()
	if ok, _ := c.Extension("STARTTLS"); ok {
		if err := c.StartTLS(&tls.Config{ServerName: host}); err != nil {
			return err
		}
	}
	if ok, _ := c.Extension("AUTH"); ok {
		if err := c.Auth(smtp.PlainAuth("", user, pass, host)); err != nil {
			return fmt.Errorf("认证失败: %w", err)
		}
	}
	if err := c.Mail(from); err != nil {
		return err
	}
	if err := c.Rcpt(to); err != nil {
		return err
	}
	w, err := c.Data()
	if err != nil {
		return err
	}
	w.Write([]byte(msg))
	w.Close()
	return c.Quit()
}

// 验证码邮件模板
func codeMailHtml(code, purpose string) string {
	title := "注册验证"
	if purpose == "reset" {
		title = "重置密码"
	}
	return `<!DOCTYPE html><html><body style="margin:0;padding:24px;background:#0d1117;
font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif">
<div style="max-width:460px;margin:0 auto;background:#161b22;border:1px solid #30363d;
border-radius:14px;padding:26px">
<div style="font-size:20px;font-weight:800;color:#e6edf3;margin-bottom:6px">超米</div>
<div style="color:#8b949e;font-size:13px;margin-bottom:20px">` + title + `验证码</div>
<div style="font-size:34px;font-weight:800;letter-spacing:8px;color:#58a6ff;
background:#0d1117;border:1px solid #30363d;border-radius:10px;padding:16px;
text-align:center;font-family:Menlo,Consolas,monospace">` + code + `</div>
<div style="color:#8b949e;font-size:12px;margin-top:18px;line-height:1.8">
10 分钟内有效。如果不是你本人操作，忽略这封邮件即可。<br>
请勿把验证码告诉任何人，超米不会向你索要验证码。</div>
</div></body></html>`
}

// ===== 飞书通知 =====

func feishuNotify(title, text string) {
	hook := env("FEISHU_WEBHOOK", "")
	if hook == "" {
		return
	}
	payload := map[string]interface{}{
		"msg_type": "post",
		"content": map[string]interface{}{
			"post": map[string]interface{}{
				"zh_cn": map[string]interface{}{
					"title": title,
					"content": [][]interface{}{
						{map[string]interface{}{"tag": "text", "text": text}},
					},
				},
			},
		},
	}
	// 可选签名校验
	if secret := env("FEISHU_SECRET", ""); secret != "" {
		ts := strconv.FormatInt(time.Now().Unix(), 10)
		h := hmac.New(sha256.New, []byte(ts+"\n"+secret))
		payload["timestamp"] = ts
		payload["sign"] = base64.StdEncoding.EncodeToString(h.Sum(nil))
	}
	b, _ := json.Marshal(payload)
	go func() {
		req, err := http.NewRequest("POST", hook, strings.NewReader(string(b)))
		if err != nil {
			return
		}
		req.Header.Set("Content-Type", "application/json")
		cl := &http.Client{Timeout: 8 * time.Second}
		resp, err := cl.Do(req)
		if err == nil && resp != nil {
			resp.Body.Close()
		}
	}()
}

// ===== API =====

var reEmail = regexp.MustCompile(`^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$`)

// handleSendCode POST /api/auth/sendcode {email, purpose}
func handleSendCode(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	var b struct {
		Email   string `json:"email"`
		Purpose string `json:"purpose"`
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}
	b.Email = strings.ToLower(strings.TrimSpace(b.Email))
	if !reEmail.MatchString(b.Email) {
		writeJSON(w, 400, map[string]string{"error": "邮箱格式不正确"})
		return
	}
	purpose := strings.TrimSpace(b.Purpose)
	if purpose == "" {
		purpose = "register"
	}
	if purpose != "register" && purpose != "reset" {
		writeJSON(w, 400, map[string]string{"error": "purpose 只支持 register / reset"})
		return
	}

	// 注册：邮箱已存在要提前拦；重置：邮箱不存在也要拦（但不暴露太明显）
	var exist int
	_ = db.QueryRow(`SELECT COUNT(*) FROM c_users WHERE email=?`, b.Email).Scan(&exist)
	if purpose == "register" && exist > 0 {
		writeJSON(w, 409, map[string]string{"error": "该邮箱已注册，可直接登录或找回密码"})
		return
	}
	if purpose == "reset" && exist == 0 {
		writeJSON(w, 404, map[string]string{"error": "该邮箱未注册"})
		return
	}

	// 内测模式：未配置 SMTP 时不发信，直接把码返回（响应里明确标注）
	if !smtpConfigured() {
		code := newCode()
		now := time.Now()
		db.Exec(`INSERT INTO c_email_codes(email,code,purpose,created_at,expires_at,ip)
			VALUES(?,?,?,?,?,?)`, b.Email, code, purpose,
			now.UnixMilli(), now.Add(codeTTL).UnixMilli(), clientIP(r))
		writeJSON(w, 200, map[string]interface{}{
			"code": 0,
			"data": map[string]interface{}{
				"sent":      false,
				"devMode":   true,
				"devCode":   code,
				"message":   "服务端未配置 SMTP，验证码直接返回（内测模式）",
				"expiresIn": int(codeTTL.Seconds()),
			},
		})
		return
	}

	ok, wait := canSendCode(db, b.Email, purpose)
	if !ok {
		writeJSON(w, 429, map[string]string{
			"error": fmt.Sprintf("发送太频繁，请 %d 秒后再试", wait)})
		return
	}

	code := newCode()
	now := time.Now()
	if _, err := db.Exec(`INSERT INTO c_email_codes(email,code,purpose,created_at,expires_at,ip)
		VALUES(?,?,?,?,?,?)`, b.Email, code, purpose,
		now.UnixMilli(), now.Add(codeTTL).UnixMilli(), clientIP(r)); err != nil {
		writeJSON(w, 500, map[string]string{"error": "验证码保存失败"})
		return
	}

	subject := "超米 · 注册验证码"
	if purpose == "reset" {
		subject = "超米 · 重置密码验证码"
	}
	if err := smtpSend(b.Email, subject, codeMailHtml(code, purpose)); err != nil {
		writeJSON(w, 502, map[string]string{"error": "邮件发送失败：" + err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"sent":      true,
			"expiresIn": int(codeTTL.Seconds()),
		},
	})
}

// 校验验证码（消费式：用过即作废）
func verifyCode(db *sql.DB, email, purpose, code string) bool {
	email = strings.ToLower(strings.TrimSpace(email))
	code = strings.TrimSpace(code)
	if code == "" {
		return false
	}
	var id int64
	err := db.QueryRow(`SELECT id FROM c_email_codes
		WHERE email=? AND purpose=? AND code=? AND used=0 AND expires_at>?
		ORDER BY id DESC LIMIT 1`,
		email, purpose, code, time.Now().UnixMilli()).Scan(&id)
	if err != nil {
		return false
	}
	db.Exec(`UPDATE c_email_codes SET used=1 WHERE id=?`, id)
	return true
}

// handleResetPassword POST /api/auth/reset {email, code, password}
func handleResetPassword(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	var b struct {
		Email    string `json:"email"`
		Code     string `json:"code"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}
	b.Email = strings.ToLower(strings.TrimSpace(b.Email))
	if len([]rune(b.Password)) < 6 {
		writeJSON(w, 400, map[string]string{"error": "密码至少 6 位"})
		return
	}
	if !verifyCode(db, b.Email, "reset", b.Code) {
		writeJSON(w, 400, map[string]string{"error": "验证码不正确或已过期"})
		return
	}
	salt := cRandHex(16)
	hash := cHashPassword(b.Password, salt)
	res, err := db.Exec(`UPDATE c_users SET pass_hash=?, salt=? WHERE email=?`,
		hash, salt, b.Email)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": "重置失败"})
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeJSON(w, 404, map[string]string{"error": "该邮箱未注册"})
		return
	}
	// 清掉该用户所有会话，强制重新登录
	var uid int64
	_ = db.QueryRow(`SELECT id FROM c_users WHERE email=?`, b.Email).Scan(&uid)
	db.Exec(`DELETE FROM c_sessions WHERE user_id=?`, uid)
	feishuNotify("超米 · 密码已重置", fmt.Sprintf("邮箱 %s 重置了密码\nIP %s",
		b.Email, clientIP(r)))
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": true})
}

// handleAvatarUpload POST /api/auth/avatar  上传头像（登录态）
func handleAvatarUpload(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	u := cAuthUser(db, r)
	if u == nil {
		writeJSON(w, 401, map[string]string{"error": "请先登录"})
		return
	}
	url, err := cSaveUpload(r, "file")
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	if _, err := db.Exec(`UPDATE c_users SET avatar=? WHERE id=?`, url, u.ID); err != nil {
		writeJSON(w, 500, map[string]string{"error": "保存失败"})
		return
	}
	// 昵称/头像变更同步到已发内容
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": map[string]string{"avatar": url}})
}

// handleCheckUID GET /api/auth/checkuid?uid=CM123456  查唯一号是否可用
func handleCheckUID(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	uid := strings.ToUpper(strings.TrimSpace(r.URL.Query().Get("uid")))
	if uid == "" {
		writeJSON(w, 400, map[string]string{"error": "缺少 uid"})
		return
	}
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM c_users WHERE uid=?`, uid).Scan(&n)
	writeJSON(w, 200, map[string]interface{}{"code": 0,
		"data": map[string]interface{}{"uid": uid, "available": n == 0}})
}

// handleUserByUID GET /api/user/uid/CM123456  用唯一号查主页
func handleUserByUID(db *sql.DB, w http.ResponseWriter, r *http.Request, uid string) {
	var id int64
	if err := db.QueryRow(`SELECT id FROM c_users WHERE uid=?`,
		strings.ToUpper(strings.TrimSpace(uid))).Scan(&id); err != nil {
		writeJSON(w, 404, map[string]string{"error": "用户不存在"})
		return
	}
	handleUserProfile(db, w, r, id)
}
