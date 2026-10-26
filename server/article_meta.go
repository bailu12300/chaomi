package main

// ---------------- 公众号 / 网页元信息提取（完整公开字段） ----------------
//
// 目标：不登录微信，拿到页面上「公开可抓」的全部信息。
//
// 公众号文章页里公开可用的字段（无需登录）：
//   var nickname           公众号名称
//   var user_name          公众号原始 id（gh_xxx）
//   var round_head_img     圆形头像（微信 CDN 会带 /0?wx_fmt=... 参数）
//   var ori_head_img_url   原始方形头像
//   var msg_title          标题
//   var msg_desc           摘要
//   var msg_cdn_url        封面图
//   var ct / publish_time  发布时间
//   var author             原文作者署名
//   og:title / og:image / og:description（meta 兜底）
//   #js_content            正文容器（仅用于估算字数与首图，不转 blocks）
//
// 这些都在 HTML 里以 JS 变量或 meta 形式存在，正则提取即可，不需要执行 JS。

import (
	"fmt"
	"io"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// ArticleMeta 完整元信息
type ArticleMeta struct {
	Title        string `json:"title"`
	Author       string `json:"author"`       // 文章署名作者
	Summary      string `json:"summary"`      // 摘要
	Cover        string `json:"cover"`        // 封面
	URL          string `json:"url"`
	// 公众号信息
	AccountName  string `json:"accountName"`  // 公众号名称
	AccountID    string `json:"accountId"`    // 原始 id（gh_xxx）
	AccountAvatar string `json:"accountAvatar"` // 公众号头像
	// 文章信息
	PublishTime  int64  `json:"publishTime"`  // 发布时间（毫秒）
	PublishText  string `json:"publishText"`  // 原始时间文本
	WordCount    int    `json:"wordCount"`    // 正文字数（估算）
	ImageCount   int    `json:"imageCount"`   // 正文图片数
	FirstImage   string `json:"firstImage"`   // 正文首图
	IsWechat     bool   `json:"isWechat"`
	Kind         string `json:"kind"`         // wechat / web
}

// varRe 提取 JS 变量：var name = "value" 或 var name = 'value'
func varRe(name string) *regexp.Regexp {
	return regexp.MustCompile(`(?m)\bvar\s+` + regexp.QuoteMeta(name) +
		`\s*=\s*(?:"([^"]*)"|'([^']*)')`)
}

var (
	reNickname   = varRe("nickname")
	reUserName   = varRe("user_name")
	reRoundHead  = varRe("round_head_img")
	reOriHead    = varRe("ori_head_img_url")
	reMsgTitle   = varRe("msg_title")
	reMsgDesc    = varRe("msg_desc")
	reMsgCdn     = varRe("msg_cdn_url")
	reAuthor     = varRe("author")
	rePublishTxt = varRe("publish_time")
	reCt         = varRe("ct")
	// data-* 属性里的头像（部分模板）
	reHeadImgAttr = regexp.MustCompile(`(?:data-headimg|data-headimgurl)\s*=\s*["']([^"']+)["']`)
	// 发布时间文本，如 "2026年10月26日 08:00"
	reTimeText = regexp.MustCompile(`(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日`)
)

func pickVar(re *regexp.Regexp, html string) string {
	m := re.FindStringSubmatch(html)
	if len(m) >= 3 {
		if strings.TrimSpace(m[1]) != "" {
			return strings.TrimSpace(m[1])
		}
		return strings.TrimSpace(m[2])
	}
	return ""
}

// parseArticleMetaFull 从 HTML 里抽全部公开信息
func parseArticleMetaFull(rawURL string, html string, dm *goquery.Document) *ArticleMeta {
	m := &ArticleMeta{URL: rawURL}
	m.IsWechat = strings.Contains(rawURL, "mp.weixin.qq.com")
	if m.IsWechat {
		m.Kind = "wechat"
	} else {
		m.Kind = "web"
	}

	// ---- 公众号信息 ----
	m.AccountName = pickVar(reNickname, html)
	m.AccountID = pickVar(reUserName, html)
	m.AccountAvatar = pickVar(reRoundHead, html)
	if m.AccountAvatar == "" {
		m.AccountAvatar = pickVar(reOriHead, html)
	}
	if m.AccountAvatar == "" {
		if mm := reHeadImgAttr.FindStringSubmatch(html); len(mm) > 1 {
			m.AccountAvatar = strings.TrimSpace(mm[1])
		}
	}
	// 页面上还有 #js_name 文本节点
	if m.AccountName == "" {
		m.AccountName = strings.TrimSpace(dm.Selection.Find("#js_name").First().Text())
	}
	m.AccountAvatar = cAbsURL(strings.ReplaceAll(m.AccountAvatar, `\/`, `/`), rawURL)

	// ---- 标题 ----
	title := pickVar(reMsgTitle, html)
	if title == "" {
		title = cMetaContent(dm, "og:title")
	}
	if title == "" {
		title = strings.TrimSpace(dm.Selection.Find("#activity-name").Text())
	}
	if title == "" {
		title = strings.TrimSpace(dm.Selection.Find("h1.rich_media_title").Text())
	}
	if title == "" {
		title = strings.TrimSpace(dm.Selection.Find("title").Text())
	}
	m.Title = strings.Join(strings.Fields(strings.ReplaceAll(title, `\/`, `/`)), " ")

	// ---- 作者 / 摘要 / 封面 ----
	m.Author = pickVar(reAuthor, html)
	if m.Author == "" {
		m.Author = cMetaContent(dm, "og:article:author")
	}
	if m.Author == "" {
		m.Author = strings.TrimSpace(dm.Selection.Find("#js_author_name").Text())
	}
	m.Author = strings.TrimSpace(m.Author)

	m.Summary = pickVar(reMsgDesc, html)
	if m.Summary == "" {
		m.Summary = cMetaContent(dm, "og:description")
	}
	if m.Summary == "" {
		m.Summary = cMetaContent(dm, "description")
	}
	m.Summary = strings.Join(strings.Fields(strings.ReplaceAll(m.Summary, `\/`, `/`)), " ")

	m.Cover = pickVar(reMsgCdn, html)
	if m.Cover == "" {
		m.Cover = cMetaContent(dm, "og:image")
	}
	m.Cover = cAbsURL(strings.ReplaceAll(m.Cover, `\/`, `/`), rawURL)

	// ---- 发布时间 ----
	// ct 是秒级时间戳
	if ct := pickVar(reCt, html); ct != "" {
		var sec int64
		if _, err := fmt.Sscanf(ct, "%d", &sec); err == nil && sec > 1000000000 {
			m.PublishTime = sec * 1000
		}
	}
	m.PublishText = pickVar(rePublishTxt, html)
	if m.PublishText == "" {
		// #publish_time 元素
		m.PublishText = strings.TrimSpace(dm.Selection.Find("#publish_time").First().Text())
	}
	if m.PublishTime == 0 && m.PublishText != "" {
		if mm := reTimeText.FindStringSubmatch(m.PublishText); len(mm) == 4 {
			var y, mo, d int
			fmt.Sscanf(mm[1], "%d", &y)
			fmt.Sscanf(mm[2], "%d", &mo)
			fmt.Sscanf(mm[3], "%d", &d)
			if y > 2000 && mo >= 1 && mo <= 12 && d >= 1 && d <= 31 {
				t := timeDate(y, mo, d)
				m.PublishTime = t
			}
		}
	}

	// ---- 正文统计（首图 / 字数 / 图片数），不转 blocks ----
	content := dm.Selection.Find("#js_content")
	if content.Length() == 0 {
		content = dm.Selection.Find(".rich_media_content")
	}
	if content.Length() == 0 {
		content = dm.Selection.Find("article")
	}
	if content.Length() > 0 {
		text := strings.Join(strings.Fields(content.Text()), " ")
		m.WordCount = len([]rune(text))

		imgs := content.Find("img")
		m.ImageCount = imgs.Length()
		imgs.EachWithBreak(func(_ int, s *goquery.Selection) bool {
			src, _ := s.Attr("data-src")
			if strings.TrimSpace(src) == "" {
				src, _ = s.Attr("src")
			}
			src = strings.TrimSpace(src)
			if src != "" && !strings.HasPrefix(src, "data:") {
				m.FirstImage = cAbsURL(src, rawURL)
				return false // break
			}
			return true
		})
	}
	if m.FirstImage == "" {
		m.FirstImage = m.Cover
	}

	if m.Summary == "" && m.WordCount > 0 {
		// 摘要兜底：取正文前 120 字
		text := strings.Join(strings.Fields(content.Text()), " ")
		r := []rune(text)
		if len(r) > 120 {
			text = string(r[:120]) + "…"
		}
		m.Summary = text
	}

	return m
}

// cParseArticleMeta 轻量解析：只取元信息，正文交给客户端浏览器渲染。
func cParseArticleMeta(rawURL string) (*ArticleMeta, error) {
	resp, err := cHttpGetUA(rawURL)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("远端返回 %d", resp.StatusCode)
	}

	// 同时保留原始 HTML（用于 JS 变量提取）和 goquery（用于 DOM 查询）
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	html := string(body)

	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return nil, err
	}
	m := parseArticleMetaFull(rawURL, html, doc)
	if m.Title == "" {
		// 有些页面被删/需登录，正文为空但标题能拿到
		return m, fmt.Errorf("没能解析出标题，可能页面已被删除或需要登录")
	}
	return m, nil
}

// timeDate 构造毫秒时间戳（按北京时间，公众号多在国内发布）
func timeDate(y, mo, d int) int64 {
	loc := time.FixedZone("CST", 8*3600)
	return time.Date(y, time.Month(mo), d, 8, 0, 0, 0, loc).UnixMilli()
}

// metaToMap 统一输出结构，客户端一次拿到全部公开字段
func metaToMap(m *ArticleMeta) map[string]interface{} {
	return map[string]interface{}{
		"title":         m.Title,
		"author":        m.Author,
		"summary":       m.Summary,
		"cover":         m.Cover,
		"url":           m.URL,
		"accountName":   m.AccountName,
		"accountId":     m.AccountID,
		"accountAvatar": m.AccountAvatar,
		"publishTime":   m.PublishTime,
		"publishText":   m.PublishText,
		"wordCount":     m.WordCount,
		"imageCount":    m.ImageCount,
		"firstImage":    m.FirstImage,
		"isWechat":      m.IsWechat,
		"kind":          m.Kind,
		"blocks":        []CBlock{},
		"count":         0,
	}
}

// 保留 old 签名兼容（部分调用方只用 Title/Author/Cover/Summary）
func (m *ArticleMeta) CArticle() *CArticle {
	return &CArticle{
		Title: m.Title, Author: m.Author, Summary: m.Summary,
		Cover: m.Cover, URL: m.URL,
	}
}

// absOrEmpty 工具
func absOrEmpty(s, base string) string {
	if strings.TrimSpace(s) == "" {
		return ""
	}
	if u, err := url.Parse(s); err == nil && u.IsAbs() {
		return s
	}
	return cAbsURL(s, base)
}
