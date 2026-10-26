package main

// ---------------- 链接卡片解析 ----------------
// 挂链接时抓取目标页的 OG 信息，生成卡片（标题/描述/封面/站点名），
// 前端以卡片而非裸 URL 展示。失败则降级为纯链接。

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

type linkCard struct {
	URL         string `json:"url"`
	Title       string `json:"title"`
	Description string `json:"description"`
	Image       string `json:"image"`
	SiteName    string `json:"siteName"`
	Favicon     string `json:"favicon"`
	Kind        string `json:"kind"` // article | video | image | wechat | github | doc | web
	OK          bool   `json:"ok"`
	Error       string `json:"error,omitempty"`
}

var reWechat = regexp.MustCompile(`^https?://mp\.weixin\.qq\.com/`)
var reBili = regexp.MustCompile(`^https?://(www\.)?bilibili\.com/`)
var reGithub = regexp.MustCompile(`^https?://(www\.)?github\.com/`)

// handleLinkPreview GET /api/link/preview?url=
func handleLinkPreview(w http.ResponseWriter, r *http.Request) {
	raw := strings.TrimSpace(r.URL.Query().Get("url"))
	if raw == "" && r.Method == http.MethodPost {
		var b struct {
			URL string `json:"url"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		raw = strings.TrimSpace(b.URL)
	}
	if raw == "" {
		writeJSON(w, 400, map[string]string{"error": "缺少 url"})
		return
	}
	// 只允许 http/https，避免 file:// 之类
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		writeJSON(w, 400, map[string]string{"error": "只支持 http/https 链接"})
		return
	}

	card := linkCard{URL: raw, Kind: kindOf(raw)}
	if card.Kind == "wechat" {
		// 公众号走已有的图文解析，信息更全
		if art, _, e := cParseArticleNative(raw); e == nil && art != nil {
			card.Title = art.Title
			card.Description = art.Summary
			card.Image = art.Cover
			card.SiteName = "微信公众号"
			if art.Author != "" {
				card.SiteName = art.Author
			}
			card.OK = art.Title != "" || art.Summary != ""
		} else if e != nil {
			card.Error = e.Error()
		}
	} else {
		fillOG(&card, raw)
	}
	if card.Favicon == "" {
		if u2, e := url.Parse(raw); e == nil {
			card.Favicon = u2.Scheme + "://" + u2.Host + "/favicon.ico"
		}
	}
	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": card})
}

func kindOf(raw string) string {
	switch {
	case reWechat.MatchString(raw):
		return "wechat"
	case reBili.MatchString(raw):
		return "video"
	case reGithub.MatchString(raw):
		return "github"
	}
	low := strings.ToLower(raw)
	switch {
	case strings.HasSuffix(low, ".jpg") || strings.HasSuffix(low, ".png") ||
		strings.HasSuffix(low, ".gif") || strings.HasSuffix(low, ".webp"):
		return "image"
	case strings.HasSuffix(low, ".pdf"):
		return "doc"
	default:
		return "web"
	}
}

// fillOG 抓页面 OG 信息，带超时与大小限制
func fillOG(card *linkCard, raw string) {
	done := make(chan struct{})
	go func() {
		defer close(done)
		resp, err := cHttpGetUA(raw)
		if err != nil {
			card.Error = "抓取失败"
			return
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			card.Error = fmt.Sprintf("远端返回 %d", resp.StatusCode)
			return
		}
		// 限制读取 2MB，防止超大页面拖垮进程
		limited := http.MaxBytesReader(nil, resp.Body, 2<<20)
		doc, err := goquery.NewDocumentFromReader(limited)
		if err != nil {
			card.Error = "页面解析失败"
			return
		}

		card.Title = firstNonEmpty(
			cMetaContent(doc, "og:title"),
			cMetaContent(doc, "twitter:title"),
			strings.TrimSpace(doc.Find("title").First().Text()),
			strings.TrimSpace(doc.Find("h1").First().Text()),
		)
		card.Description = firstNonEmpty(
			cMetaContent(doc, "og:description"),
			cMetaContent(doc, "twitter:description"),
			cMetaContent(doc, "description"),
		)
		card.Image = cAbsURL(firstNonEmpty(
			cMetaContent(doc, "og:image"),
			cMetaContent(doc, "twitter:image"),
		), raw)
		card.SiteName = firstNonEmpty(
			cMetaContent(doc, "og:site_name"),
			cMetaContent(doc, "application-name"),
		)
		if card.SiteName == "" {
			if u, e := url.Parse(raw); e == nil {
				card.SiteName = u.Host
			}
		}
		// 截断过长描述，卡片放不下
		if len([]rune(card.Description)) > 160 {
			card.Description = string([]rune(card.Description)[:160]) + "…"
		}
		card.OK = card.Title != ""
	}()

	select {
	case <-done:
	case <-time.After(12 * time.Second):
		card.Error = "抓取超时"
	}
}

func firstNonEmpty(ss ...string) string {
	for _, s := range ss {
		if t := strings.TrimSpace(s); t != "" {
			return t
		}
	}
	return ""
}
