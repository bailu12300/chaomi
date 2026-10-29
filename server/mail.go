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
		{"c_users", "is_admin", "is_admin TINYINT DEFAULT 0"},
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

// domainOfMail 取邮箱域名（用于构造 Message-ID）
func domainOfMail(addr string) string {
	if i := strings.LastIndex(addr, "@"); i >= 0 && i+1 < len(addr) {
		return addr[i+1:]
	}
	return "localhost"
}

// stripHTMLTags 生成纯文本版本（HTML 邮件必须带 alt 纯文本，否则易判垃圾）
func stripHTMLTags(html string) string {
	var sb strings.Builder
	inTag := false
	for _, r := range html {
		switch r {
		case '<':
			inTag = true
		case '>':
			inTag = false
			sb.WriteByte(' ')
		default:
			if !inTag {
				sb.WriteRune(r)
			}
		}
	}
	out := strings.Join(strings.Fields(sb.String()), " ")
	return out
}

// wrapB64 按 76 字符折行（RFC 2045 要求）
func wrapB64(s string) string {
	var sb strings.Builder
	for i := 0; i < len(s); i += 76 {
		end := i + 76
		if end > len(s) {
			end = len(s)
		}
		sb.WriteString(s[i:end])
		sb.WriteString("\r\n")
	}
	return sb.String()
}


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

	// 头部
	//
	// ★ 关键：RFC 5322 要求邮件头必须是纯 ASCII。
	//   显示名「超米」是中文，**必须做 RFC2047 编码**，否则 QQ 邮箱判定格式违规
	//   并直接退信（这就是之前验证码邮件收不到的根因）。
	//   主题同理，也要编码。
	nameEnc := "=?UTF-8?B?" + base64.StdEncoding.EncodeToString([]byte("超米")) + "?="
	subj := "=?UTF-8?B?" + base64.StdEncoding.EncodeToString([]byte(subject)) + "?="

	// Message-ID：QQ/163 等对缺失 Message-ID 的邮件会提高垃圾评分
	msgID := fmt.Sprintf("<%d.%s@%s>", time.Now().UnixNano(),
		cRandHex(8), domainOfMail(from))

	// 正文编码策略（★ 送达率关键）
	//
	// 之前全用「multipart/alternative + 深色 HTML 卡片 + 大按钮链接 + 全 base64」：
	// QQ 邮箱对这套组合垃圾评分很高，会出现 SMTP 收下但随后异步退信。
	// 现在改为：
	//   · 单部分 text/plain（事务性通知的标准形态）
	//   · base64 编码正文（兼容性最好；含中文不会乱码）
	//   · 链接以纯文本 URL 给出（不做 <a> 大按钮，避免被判钓鱼）
	plain := body
	if strings.TrimSpace(plain) == "" {
		plain = "（本邮件无正文）"
	}
	plain = strings.ReplaceAll(plain, "\r\n", "\n")
	plain = strings.ReplaceAll(plain, "\n", "\r\n")

	msg := "From: " + nameEnc + " <" + from + ">\r\n" +
		"To: " + to + "\r\n" +
		"Reply-To: " + from + "\r\n" +
		"Subject: " + subj + "\r\n" +
		"Message-ID: " + msgID + "\r\n" +
		"MIME-Version: 1.0\r\n" +
		"Content-Type: text/plain; charset=UTF-8\r\n" +
		"Content-Transfer-Encoding: base64\r\n" +
		"Date: " + time.Now().Format(time.RFC1123Z) + "\r\n" +
		"X-Mailer: Chaomi-Mailer/1.1\r\n" +
		// 声明是系统自动生成的事务性邮件，帮助收件方正确分类
		"Auto-Submitted: auto-generated\r\n" +
		"\r\n" +
		wrapB64(base64.StdEncoding.EncodeToString([]byte(plain)))

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
	// 纯文本正文（单部分），不拼 HTML 卡片：
	// QQ 对「深色 HTML + 大按钮」的事务邮件垃圾评分高，纯文本更稳。
	return "超米 · " + title + "码\n" +
		"\n" +
		"你的" + title + "码是：\n" +
		"\n" +
		"    " + code + "\n" +
		"\n" +
		"10 分钟内有效。如果不是你本人操作，忽略这封邮件即可。\n" +
		"请勿把验证码告诉任何人，超米不会向你索要验证码。\n" +
		"\n" +
		"-- \n" +
		"超米\n" +
		siteURL() + "/community/\n"
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

	// 未配置 SMTP：明确报错，**不再把验证码回给前端**。
	// （把码返回等于绕过邮箱验证：任何人填别人邮箱就能拿到码）
	if !smtpConfigured() {
		writeJSON(w, 503, map[string]string{
			"error": "邮件服务未配置，暂时无法发送验证码，请联系管理员"})
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

	subject := "超米 · 注册验证"
	if purpose == "reset" {
		subject = "超米 · 密码重置验证"
	}
	if err := smtpSend(b.Email, subject, codeMailHtml(code, purpose)); err != nil {
		// 发信失败：不把验证码回给前端，只提示重试/换邮箱。
		// 那条把码返回的"兜底"是安全漏洞（能拿到别人邮箱的验证码），已移除。
		writeJSON(w, 502, map[string]string{
			"error": "邮件发送失败，请稍后重试，或换一个邮箱（如 QQ 邮箱有时会拦截）"})
		return
	}

	resp := map[string]interface{}{
		"sent":      true,
		"expiresIn": int(codeTTL.Seconds()),
	}

	// 兜底开关：邮件已发出，但同时也把码返回给客户端自动填入。
	//
	// 为什么需要：国内邮件进垃圾箱/被运营商拦的概率不低，
	// 用户拿不到码就完全无法注册登录（这是反复被卡住的真实原因）。
	// 这是自建社区，注册量小，风险可控；正式对外开放时可关掉。
	// ★ 默认关闭（"false"）：把验证码回给客户端 = 任何人打开网站都能拿到别人的验证码，
	//   可以直接注册/重置任意邮箱的密码，是严重安全问题。
	//   只有在自测环境（正确配置 SMTP 前）才临时用 CODE_FALLBACK=true 打开。
	if env("CODE_FALLBACK", "false") == "true" {
		resp["devCode"] = code
		resp["message"] = "验证码已发送到邮箱，同时已为你自动填入"
	}

	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": resp})
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
