package main

// ---------------- 短视频去水印 ----------------
//
// 现实约束（都是实测结论，不是猜的）：
//
//   · B 站：API 开放、无需签名，但**必须带 buvid3/buvid4 cookie**，
//     否则服务器 IP 会被判定为爬虫直接 -412 封禁。带上 cookie 后完全可用：
//     view → cid → playurl → durl[0].url 就是无水印直链（实测 98MB mp4）。
//
//   · 抖音：自研抓不到（ArgusSecurityPlugin 签名 + msToken 风控），
//     所以改为**委托给本地 DTK 容器**（Evil0ctal/Douyin_TikTok_Download_API v4，
//     跑在 127.0.0.1:18080）。DTK 内部实现了 a_bogus/X-Bogus 签名算法。
//     ★ 但抖音需要**有效的登录 cookie（含 msToken）**才能真正解析；
//     只配游客 ttwid 仍会 403。没配 cookie 时如实告知，不返回假数据。
//
//   · 快手/小红书：同理，仅做短链展开与平台识别。
//
// 原则：宁可如实说"暂不支持自动解析"，也不返回假数据。

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

// ---- buvid cookie 缓存（B站风控的钥匙）----

var (
	buvidMu   sync.Mutex
	buvidVal  string
	buvidAt   time.Time
	buvidLife = 30 * time.Minute
)

const biliUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// biliBuvid 取（并缓存）B站指纹 cookie。拿不到就返回空，请求会 -412，此时告知用户稍后再试。
func biliBuvid() string {
	buvidMu.Lock()
	defer buvidMu.Unlock()
	if buvidVal != "" && time.Since(buvidAt) < buvidLife {
		return buvidVal
	}
	req, err := http.NewRequest("GET", "https://api.bilibili.com/x/frontend/finger/spi", nil)
	if err != nil {
		return buvidVal
	}
	req.Header.Set("User-Agent", biliUA)
	cl := &http.Client{Timeout: 12 * time.Second}
	resp, err := cl.Do(req)
	if err != nil {
		return buvidVal
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var r struct {
		Data struct {
			B3 string `json:"b_3"`
			B4 string `json:"b_4"`
		} `json:"data"`
	}
	if json.Unmarshal(body, &r) == nil && r.Data.B3 != "" {
		buvidVal = "buvid3=" + r.Data.B3 + "; buvid4=" + r.Data.B4
		buvidAt = time.Now()
	}
	return buvidVal
}

// biliGet 带齐指纹 cookie / Referer / UA 请求 B站 API
func biliGet(u string) ([]byte, error) {
	req, err := http.NewRequest("GET", u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", biliUA)
	req.Header.Set("Referer", "https://www.bilibili.com/")
	req.Header.Set("Accept", "application/json, text/plain, */*")
	if ck := biliBuvid(); ck != "" {
		req.Header.Set("Cookie", ck)
	}
	cl := &http.Client{Timeout: 20 * time.Second}
	resp, err := cl.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(io.LimitReader(resp.Body, 4<<20))
}

// ---- 平台识别 ----

type wmPlatform struct {
	Key  string
	Name string
}

var platformRules = []struct {
	Key    string
	Name   string
	Hosts  []string
	Re     *regexp.Regexp // 用于从文本中抓链接
}{
	{"bilibili", "哔哩哔哩",
		[]string{"bilibili.com", "b23.tv"},
		regexp.MustCompile(`https?://(?:www\.|m\.)?bilibili\.com/[^\s"']+|https?://b23\.tv/[A-Za-z0-9]+`)},
	{"douyin", "抖音",
		[]string{"douyin.com", "iesdouyin.com"},
		regexp.MustCompile(`https?://v\.douyin\.com/[A-Za-z0-9_\-]+|https?://(?:www\.)?douyin\.com/[^\s"']+`)},
	{"kuaishou", "快手",
		[]string{"kuaishou.com", "v.kuaishou.com", "chenzhongtech.com"},
		regexp.MustCompile(`https?://v\.kuaishou\.com/[A-Za-z0-9_\-]+|https?://(?:www\.)?kuaishou\.com/[^\s"']+`)},
	{"xiaohongshu", "小红书",
		[]string{"xiaohongshu.com", "xhslink.com"},
		regexp.MustCompile(`https?://xhslink\.com/[A-Za-z0-9_\-]+|https?://(?:www\.)?xiaohongshu\.com/[^\s"']+`)},
	{"tiktok", "TikTok",
		[]string{"tiktok.com", "vm.tiktok.com"},
		regexp.MustCompile(`https?://(?:vm\.)?tiktok\.com/[^\s"']+`)},
}

// extractURL 从用户粘贴的整段分享口令里抓出链接
// （抖音/快手的分享文本通常是「7.94 复制打开抖音... https://v.douyin.com/xxx/ ...」）
func extractURL(text string) (string, *wmPlatform) {
	text = strings.TrimSpace(text)
	if text == "" {
		return "", nil
	}
	for _, r := range platformRules {
		if m := r.Re.FindString(text); m != "" {
			return strings.TrimRight(m, "，,。.、）)】]\"'"), &wmPlatform{r.Key, r.Name}
		}
	}
	// 没有匹配规则时，若本身就是 http 链接，按域名猜
	if strings.HasPrefix(text, "http") {
		u, err := url.Parse(text)
		if err == nil {
			h := u.Host
			for _, r := range platformRules {
				for _, ph := range r.Hosts {
					if strings.Contains(h, ph) {
						return text, &wmPlatform{r.Key, r.Name}
					}
				}
			}
			return text, &wmPlatform{"unknown", "未知平台"}
		}
	}
	return "", nil
}

// expandShortLink 跟随 3xx 拿到真实地址，并在页面里兜底搜视频 ID。
//
// ★ 关键：必须带**移动端 UA**。抖音的 v.douyin.com 对桌面/无 UA 请求
//   返回 200 的 JS 跳转页（不是 302），所以纯看 Location 永远展不开。
//   移动 UA 下才是真正的 302。
func expandShortLink(raw string) string {
	const mobileUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
		"AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

	cur := raw
	cl := &http.Client{
		Timeout: 15 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return http.ErrUseLastResponse
			}
			return nil
		},
	}
	for i := 0; i < 3; i++ {
		req, err := http.NewRequest("GET", cur, nil)
		if err != nil {
			return cur
		}
		req.Header.Set("User-Agent", mobileUA)
		req.Header.Set("Accept", "text/html,application/xhtml+xml")
		req.Header.Set("Accept-Language", "zh-CN,zh;q=0.9")
		resp, err := cl.Do(req)
		if err != nil {
			return cur
		}
		loc := resp.Header.Get("Location")
		// 读一小段 body：没 302 时可能在 HTML 里带真实链接
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 256<<10))
		resp.Body.Close()

		if resp.StatusCode >= 300 && resp.StatusCode < 400 && loc != "" {
			if strings.HasPrefix(loc, "/") {
				u, err := url.Parse(cur)
				if err == nil {
					loc = u.Scheme + "://" + u.Host + loc
				}
			}
			cur = loc
			continue
		}

		// 兜底：页面里搜 /video/{id}
		if m := reVideoID.FindSubmatch(body); m != nil {
			return "https://www.douyin.com/video/" + string(m[1])
		}
		break
	}
	return cur
}

// ---- 解析结果 ----

type wmResult struct {
	Platform  string   `json:"platform"`
	PlatformName string `json:"platformName"`
	Title     string   `json:"title"`
	Author    string   `json:"author"`
	Cover     string   `json:"cover"`
	Duration  int      `json:"duration"`  // 秒
	VideoURL  string   `json:"videoUrl"`  // 无水印直链
	OriginalURL string `json:"originalUrl"`
	Images    []string `json:"images"`
	Note      string   `json:"note"`      // 提示 / 不支持的原因
	OK        bool     `json:"ok"`
}

var reBV = regexp.MustCompile(`(BV[0-9A-Za-z]{10})`)
var reAV = regexp.MustCompile(`(?:^|/)av(\d+)`)
var reAweme = regexp.MustCompile(`/video/(\d+)`)

// reVideoID 从页面正文/跳转地址里抓视频 ID（短链兜底用）
var reVideoID = regexp.MustCompile(`/video/(\d{15,25})`)

// parseBilibili 真正解析 B站（无水印直链）
func parseBilibili(link, bufid string) (*wmResult, error) {
	bv := ""
	if m := reBV.FindStringSubmatch(link); m != nil {
		bv = m[1]
	}
	aid := ""
	if m := reAV.FindStringSubmatch(link); m != nil {
		aid = m[1]
	}
	if bv == "" && aid == "" {
		return nil, fmt.Errorf("链接里没找到 BV 号或 av 号")
	}

	viewURL := "https://api.bilibili.com/x/web-interface/view?"
	if bv != "" {
		viewURL += "bvid=" + bv
	} else {
		viewURL += "aid=" + aid
	}
	body, err := biliGet(viewURL)
	if err != nil {
		return nil, err
	}
	var view struct {
		Code int    `json:"code"`
		Msg  string `json:"message"`
		Data struct {
			Bvid   string `json:"bvid"`
			Aid    int64  `json:"aid"`
			Title  string `json:"title"`
			Pic    string `json:"pic"`
			Cid    int64  `json:"cid"`
			Desc   string `json:"desc"`
			Owner  struct {
				Name string `json:"name"`
			} `json:"owner"`
			Duration int `json:"duration"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &view); err != nil {
		return nil, fmt.Errorf("返回格式异常")
	}
	if view.Code == -412 {
		// 指纹失效，强制刷新一次
		buvidMu.Lock()
		buvidVal = ""
		buvidMu.Unlock()
		return nil, fmt.Errorf("B站风控拦截，请稍后重试")
	}
	if view.Code != 0 {
		return nil, fmt.Errorf("B站返回：%s", view.Msg)
	}
	if view.Data.Cid == 0 {
		return nil, fmt.Errorf("该视频需要登录才能解析（可能是付费/会员内容）")
	}

	res := &wmResult{
		Platform: "bilibili", PlatformName: "哔哩哔哩",
		Title:    view.Data.Title,
		Author:   view.Data.Owner.Name,
		Cover:    strings.Replace(view.Data.Pic, "http://", "https://", 1),
		Duration: view.Data.Duration,
		OriginalURL: link,
		OK:       false,
	}

	// playurl 拿直链（platform=html5 拿到的是 mp4 单文件，最适合直接下载）
	pu := fmt.Sprintf("https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%d&qn=64&fnval=1&platform=html5",
		view.Data.Bvid, view.Data.Cid)
	pbody, err := biliGet(pu)
	if err != nil {
		res.Note = "已识别视频，但取直链失败：" + err.Error()
		return res, nil
	}
	var pl struct {
		Code int    `json:"code"`
		Msg  string `json:"message"`
		Data struct {
			Durl []struct {
				URL  string `json:"url"`
				Size int64  `json:"size"`
			} `json:"durl"`
		} `json:"data"`
	}
	if err := json.Unmarshal(pbody, &pl); err != nil || pl.Code != 0 || len(pl.Data.Durl) == 0 {
		msg := pl.Msg
		if msg == "" {
			msg = "无可用直链"
		}
		res.Note = "已识别视频，但该视频需登录才能取直链（" + msg + "）"
		return res, nil
	}

	res.VideoURL = pl.Data.Durl[0].URL
	res.OK = true
	res.Note = "解析成功，点下载保存到本地"
	return res, nil
}

// handleWatermark POST /api/watermark {url}
func handleWatermark(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	var b struct {
		URL string `json:"url"`
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}

	link, plat := extractURL(b.URL)
	if link == "" {
		writeJSON(w, 400, map[string]string{
			"error": "没识别到视频链接，请把分享内容整段粘贴进来"})
		return
	}
	if plat == nil {
		plat = &wmPlatform{"unknown", "未知平台"}
	}

	// 短链展开（b23.tv / v.douyin.com / v.kuaishou.com / xhslink.com）
	expanded := link
	if strings.Contains(link, "b23.tv") || strings.Contains(link, "v.douyin.com") ||
		strings.Contains(link, "v.kuaishou.com") || strings.Contains(link, "xhslink.com") ||
		strings.Contains(link, "vm.tiktok.com") {
		expanded = expandShortLink(link)
		// 展开后重新识别（短链最终会跳到主站域名）
		if _, p2 := extractURL(expanded); p2 != nil && p2.Key != "unknown" {
			plat = p2
		}
	}

	res := &wmResult{
		Platform: plat.Key, PlatformName: plat.Name,
		OriginalURL: expanded,
	}

	switch plat.Key {
	case "bilibili":
		if rr, err := parseBilibili(expanded, ""); err == nil {
			res = rr
		} else {
			res.Note = "解析失败：" + err.Error()
		}
	case "douyin", "tiktok":
		// 抖音 / TikTok 交给 DTK v5：它用无头浏览器自动铸造游客身份，
		// 不需要用户登录。
		//
		// ★ 关键细节：短链展开后可能变成 iesdouyin.com/share/video/{id}/ 这种格式，
		//   v5 的 URL 识别器不认（只认 douyin.com/video/{id}）。
		//   所以这里统一提取视频 ID，重构成标准 URL 再交给 v5。
		target := expanded
		if m := reAweme.FindStringSubmatch(expanded); m != nil {
			target = "https://www.douyin.com/video/" + m[1]
		}
		if dtk5Enabled() {
			if rr, err := parseViaDTK5(target, plat.Key); err == nil {
				rr.OriginalURL = expanded
				res = rr
			} else {
				res.Note = "抖音解析失败：" + err.Error() + "。已为你展开原链接。"
			}
		} else {
			res.Note = "解析服务未就绪（容器未启动或未配置 API Key）。已为你展开原链接。"
		}
	case "kuaishou":
		res.Note = "快手接口有签名校验，暂不支持自动解析。已为你展开原链接。"
	case "xiaohongshu":
		res.Note = "小红书接口需要签名，暂不支持自动解析。已为你展开原链接。"
	default:
		res.Note = "暂不支持这个平台的自动解析。已为你展开原链接。"
	}

	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": res})
}

// handleWatermarkProxy GET /api/watermark/dl?u=...&r=...
//
// 为什么要代理：B站视频直链有 Referer 防盗链，浏览器直接 <a download> 会 403。
// 这里由服务器带上正确的 Referer 转发，并把 Content-Disposition 设成 attachment，
// 用户点一下就存成文件，不会被浏览器播放器接管。
func handleWatermarkProxy(w http.ResponseWriter, r *http.Request) {
	raw := r.URL.Query().Get("u")
	if raw == "" {
		http.Error(w, "missing u", 400)
		return
	}
	referer := r.URL.Query().Get("r")
	if referer == "" {
		referer = "https://www.bilibili.com/"
	}
	// 只允许代理已知平台的视频域名，避免被当成任意 URL 跳板
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		http.Error(w, "bad url", 400)
		return
	}
	// 允许代理的媒体域名白名单（防被当成任意 URL 跳板）
	allow := []string{
		// B站
		"bilivideo.com", "bilivideo.cn", "hdslb.com", "akamaized.net",
		// 抖音（视频 CDN 有多个域名，都要放行，否则下载 403）
		"douyin.com", "douyinvod.com", "douyinpic.com", "douyinstatic.com",
		"amemv.com", "byteimg.com", "bytecdn.cn", "ixigua.com",
		"snssdk.com", "zjcdn.com", "pstatp.com",
		// 其它平台
		"kuaishou.com", "xiaohongshu.com", "tiktok.com", "tiktokcdn.com",
	}
	okHost := false
	for _, a := range allow {
		if strings.HasSuffix(u.Host, a) || strings.Contains(u.Host, a) {
			okHost = true
			break
		}
	}
	if !okHost {
		http.Error(w, "host not allowed", 403)
		return
	}

	req, err := http.NewRequest("GET", raw, nil)
	if err != nil {
		http.Error(w, "bad request", 400)
		return
	}
	req.Header.Set("User-Agent", biliUA)
	req.Header.Set("Referer", referer)
	if rg := r.Header.Get("Range"); rg != "" {
		req.Header.Set("Range", rg)
	}

	cl := &http.Client{Timeout: 10 * time.Minute}
	resp, err := cl.Do(req)
	if err != nil {
		http.Error(w, "upstream error", 502)
		return
	}
	defer resp.Body.Close()

	if ct := resp.Header.Get("Content-Type"); ct != "" {
		w.Header().Set("Content-Type", ct)
	}
	if cl2 := resp.Header.Get("Content-Length"); cl2 != "" {
		w.Header().Set("Content-Length", cl2)
	}
	if cr := resp.Header.Get("Content-Range"); cr != "" {
		w.Header().Set("Content-Range", cr)
	}
	if ar := resp.Header.Get("Accept-Ranges"); ar != "" {
		w.Header().Set("Accept-Ranges", ar)
	}
	name := r.URL.Query().Get("name")
	if name == "" {
		name = "video.mp4"
	}
	w.Header().Set("Content-Disposition", "attachment; filename=\""+name+"\"")
	w.WriteHeader(resp.StatusCode)
	io.Copy(w, resp.Body)
}


// ---------------- DTK v5 集成（抖音/TikTok 解析） ----------------
//
// 为什么是 v5 而不是 v4：
//   v4 只能靠"用户自己的 cookie"，因为它的签名参数（msToken）会过期。
//   v5 用 CloakBrowser（无头 Chromium）**自动铸造游客身份**——
//   浏览器里跑平台自己的 JS，签名由平台算，所以不需要用户登录。
//   实测：抖音 content_detail 的 login=none，短链也能解析。
//
// 部署形态：docker compose 跑在 127.0.0.1:18000，只监听本机。
//   postgres(TimescaleDB) + redis + api + worker + browser-rpc
//
// 调用约定：
//   POST /api/v1/parse  → 202 + task_id（默认异步）
//   GET  /api/v1/tasks/{id} → state=done/failed + data
//   鉴权：X-API-Key

const dtk5Base = "http://127.0.0.1:18000"

// dtk5Key 从环境变量读，缺失时返回空（此时抖音通道不可用，但 B 站不受影响）
func dtk5Key() string {
	return env("DTK_API_KEY", "")
}

// dtk5Enabled 探测 v5 是否可用
func dtk5Enabled() bool {
	if dtk5Key() == "" {
		return false
	}
	cl := &http.Client{Timeout: 1500 * time.Millisecond}
	resp, err := cl.Get(dtk5Base + "/readyz")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	return resp.StatusCode == 200
}

// dtk5Call 带 API Key 调 v5 接口
func dtk5Call(method, path string, body interface{}, timeout time.Duration) (map[string]interface{}, error) {
	var rdr io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		rdr = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, dtk5Base+path, rdr)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-API-Key", dtk5Key())
	req.Header.Set("Content-Type", "application/json")
	cl := &http.Client{Timeout: timeout}
	resp, err := cl.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	var out map[string]interface{}
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("v5 响应不是 JSON（HTTP %d）", resp.StatusCode)
	}
	return out, nil
}

// dtk5TaskResult 提交解析并等待完成（v5 是异步的，这里轮询到出结果）
func dtk5TaskResult(rawURL string) (map[string]interface{}, error) {
	j, err := dtk5Call("POST", "/api/v1/parse", map[string]interface{}{"url": rawURL}, 30*time.Second)
	if err != nil {
		return nil, err
	}
	if ok, _ := j["success"].(bool); !ok {
		return nil, fmt.Errorf("%s", dtk5ErrMsg(j))
	}
	d, _ := j["data"].(map[string]interface{})
	tid, _ := d["task_id"].(string)
	if tid == "" {
		return nil, fmt.Errorf("v5 没有返回 task_id")
	}

	// 轮询：抖音需要铸造身份（首次约 12 秒），给足 60 秒
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		time.Sleep(2 * time.Second)
		t, err := dtk5Call("GET", "/api/v1/tasks/"+tid, nil, 20*time.Second)
		if err != nil {
			continue
		}
		td, _ := t["data"].(map[string]interface{})
		state, _ := td["state"].(string)
		switch state {
		case "done":
			data, _ := td["data"].(map[string]interface{})
			if data == nil {
				return nil, fmt.Errorf("v5 任务完成但没有数据")
			}
			return data, nil
		case "failed":
			e, _ := td["error"].(map[string]interface{})
			code, _ := e["code"].(string)
			msg, _ := e["message"].(string)
			// 身份池暂时耗尽是可重试的，直接告诉用户稍后再试
			if code == "IDENTITY_POOL_EXHAUSTED" {
				return nil, fmt.Errorf("解析服务正在准备身份，请稍后重试")
			}
			if code == "NOT_FOUND" {
				return nil, fmt.Errorf("作品不存在或已被删除")
			}
			if msg == "" {
				msg = code
			}
			return nil, fmt.Errorf("%s", msg)
		}
	}
	return nil, fmt.Errorf("解析超时，请稍后重试")
}

func dtk5ErrMsg(j map[string]interface{}) string {
	e, _ := j["error"].(map[string]interface{})
	if e == nil {
		return "解析失败"
	}
	if m, _ := e["message"].(string); m != "" {
		return m
	}
	if c, _ := e["code"].(string); c != "" {
		return c
	}
	return "解析失败"
}

// parseViaDTK5 把 v5 的返回结构映射成我们自己的 wmResult
func parseViaDTK5(rawURL, platform string) (*wmResult, error) {
	data, err := dtk5TaskResult(rawURL)
	if err != nil {
		return nil, err
	}

	res := &wmResult{
		Platform: platform, OriginalURL: rawURL,
		Title:    firstNonEmpty(strOf(data["desc"]), strOf(data["title"])),
		Duration: int(numOf(data["duration"])),
	}
	if au, ok := data["author"].(map[string]interface{}); ok {
		res.Author = firstNonEmpty(strOf(au["nickname"]), strOf(au["name"]))
	}
	// 平台归属（v5 会明确告知）
	if p := strOf(data["platform"]); p != "" {
		res.Platform = p
		if p == "douyin" {
			res.PlatformName = "抖音"
		} else if p == "tiktok" {
			res.PlatformName = "TikTok"
		} else if p == "bilibili" {
			res.PlatformName = "哔哩哔哩"
		}
	}

	media, _ := data["media"].(map[string]interface{})
	if media != nil {
		// 视频
		if v, ok := media["video"].(map[string]interface{}); ok {
			u := firstNonEmpty(strOf(v["url"]))
			if u == "" {
				if urls, ok := v["urls"].([]interface{}); ok && len(urls) > 0 {
					u = strOf(urls[0])
				}
			}
			if u != "" {
				// 抖音带水印地址含 playwm
				u = strings.Replace(u, "/playwm/", "/play/", 1)
				res.VideoURL = u
			}
			if c, ok := v["cover"].(map[string]interface{}); ok {
				res.Cover = firstNonEmpty(strOf(c["url"]))
			}
		}
		if res.Cover == "" {
			if c, ok := media["cover"].(map[string]interface{}); ok {
				res.Cover = firstNonEmpty(strOf(c["url"]))
			}
		}
		// 图集
		if imgs, ok := media["images"].([]interface{}); ok {
			for _, im := range imgs {
				if m, ok := im.(map[string]interface{}); ok {
					if u := firstNonEmpty(strOf(m["url"])); u != "" {
						res.Images = append(res.Images, u)
					}
				}
			}
		}
	}

	switch {
	case res.VideoURL != "":
		res.OK = true
		res.Note = "解析成功，点下载保存到本地"
	case len(res.Images) > 0:
		res.OK = true
		res.Note = fmt.Sprintf("这是一个图文作品，共 %d 张图片", len(res.Images))
	default:
		res.Note = "已识别作品，但没取到可下载地址"
	}
	return res, nil
}

func strOf(v interface{}) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprint(v)
}

func numOf(v interface{}) float64 {
	if f, ok := v.(float64); ok {
		return f
	}
	return 0
}

// ---------------- 抖音通道状态与管理 ----------------

// handleWatermarkStatus GET /api/watermark/status
//
// 前端据此显示"抖音是否可用"，避免用户每次解析失败后才去猜原因。
// 只暴露状态，不暴露 cookie 内容。
func handleWatermarkStatus(w http.ResponseWriter, r *http.Request) {
	up := dtk5Enabled()
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"dtkUp":       up,
			"cookieSet":   true, // v5 自动铸造身份，不再需要用户 cookie
			"douyinReady": up,
			"bilibili":    "自研解析，无需配置",
			"engine":      "DTK v5 (CloakBrowser)",
			"note": func() string {
				if !up {
					return "解析容器未就绪（docker start 或检查 DTK_API_KEY）"
				}
				return "抖音/B站均已就绪，无需任何配置"
			}(),
		},
	})
}

