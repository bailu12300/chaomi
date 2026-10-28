package main

// ---------------- 资讯原文代理 + 天气定位加速 ----------------
//
// 资讯原文代理（对应「把 app 的浏览器用上，点资讯直接把请求发到服务器」）：
//   客户端点资讯 → GET /api/rss/article?id=123
//   → 服务端抓原文 HTML → 注入阅读样式 → 返回给 App
//   → App 用 WebView 渲染
//
// 为什么必须走服务端代理：
//   · App 不直连第三方站，避免被墙、避免运营商劫持插广告
//   · 服务端有固定出口 IP，抓取成功率高于移动网络
//   · 可以统一去广告、去脚本、注入阅读样式（对标公众号沉浸式阅读）
//   · 原文抓取失败也能返回 RSS 摘要，App 不会白屏

import (
	"bytes"
	"database/sql"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

var articleHTTP = &http.Client{Timeout: 15 * time.Second}

// ===== 原文抓取 =====

// fetchArticleHTML 抓取原文。
//
// 返回 (内容, 是否是整页模式, 错误)
//
// 两档策略：
//   ① 精简模式（首选）：抽出正文主体，流量小、干扰少
//   ② 整页模式（兜底）：提取不到正文时把整页 HTML 交给 WebView，
//      用注入的 CSS 隐藏导航/侧栏/广告。**保证任何页面都有内容**，
//      代价是流量大一些，但比白屏或只显示摘要好得多。
func fetchArticleHTML(link string) (string, bool, error) {
	u, err := url.Parse(link)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return "", false, fmt.Errorf("链接不合法")
	}

	req, err := http.NewRequest("GET", link, nil)
	if err != nil {
		return "", false, err
	}
	// 伪装成手机浏览器，很多站点对桌面 UA 返回弹窗墙
	req.Header.Set("User-Agent",
		"Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "+
			"(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	req.Header.Set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

	resp, err := articleHTTP.Do(req)
	if err != nil {
		return "", false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", false, fmt.Errorf("原文返回 HTTP %d", resp.StatusCode)
	}

	// 限 6MB，防止异常页面打爆内存
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 6<<20))
	if err != nil {
		return "", false, err
	}

	html := string(raw)

	// 先尝试精简提取
	body := extractMainContent(html)
	body = sanitizeArticleHTML(body)
	if textLen(body) >= 300 {
		return body, false, nil
	}

	// 提取不到正文 → 整页兜底（去掉脚本和导航后交给 WebView）
	full := sanitizeWholePage(html)
	if textLen(full) >= 200 {
		return full, true, nil
	}
	// 整页也很短（可能是纯图片页或反爬页），返回已拿到的部分
	return body, false, nil
}

// textLen 粗略统计纯文本长度（去标签后）
func textLen(html string) int {
	s := tagRe.ReplaceAllString(html, " ")
	s = spaceRe.ReplaceAllString(s, " ")
	return len([]rune(strings.TrimSpace(s)))
}

// sanitizeWholePage 整页模式清洗：保留结构，只去掉脚本和内联事件
//
// 与精简模式的区别：**保留 base/html/head**，因为整页渲染依赖原有样式与相对路径；
// 我们在 head 里注入一段覆盖样式来压制导航/广告。
func sanitizeWholePage(html string) string {
	out := html

	// 1) 去掉 <script>（安全第一）
	out = removeTagBlocks(out, "script")
	// 2) 去掉内联事件
	out = stripEventAttrs(out)
	// 3) 懒加载图片回填
	out = fixLazyImages(out)
	// 4) 移除 meta refresh（会跳转走）
	out = removeMetaRefresh(out)

	return out
}

// removeTagBlocks 删除指定标签及其内容（大小写不敏感）
func removeTagBlocks(html, tag string) string {
	lower := strings.ToLower(html)
	open := "<" + tag
	close := "</" + tag
	for {
		i := strings.Index(lower, open)
		if i < 0 {
			break
		}
		// 自闭合
		gt := strings.Index(lower[i:], ">")
		if gt < 0 {
			break
		}
		if strings.HasSuffix(strings.TrimSpace(lower[i:i+gt+1]), "/>") {
			html = html[:i] + html[i+gt+1:]
			lower = strings.ToLower(html)
			continue
		}
		j := strings.Index(lower[i:], close)
		if j < 0 {
			html = html[:i] + html[i+gt+1:]
		} else {
			k := strings.Index(lower[i+j:], ">")
			if k < 0 {
				break
			}
			html = html[:i] + html[i+j+k+1:]
		}
		lower = strings.ToLower(html)
	}
	return html
}

// removeMetaRefresh 去掉会带走页面的 meta refresh
func removeMetaRefresh(html string) string {
	lower := strings.ToLower(html)
	pos := 0
	for {
		i := strings.Index(lower[pos:], "<meta")
		if i < 0 {
			break
		}
		abs := pos + i
		gt := strings.Index(lower[abs:], ">")
		if gt < 0 {
			break
		}
		tag := lower[abs : abs+gt+1]
		if strings.Contains(tag, "refresh") {
			html = html[:abs] + html[abs+gt+1:]
			lower = strings.ToLower(html)
			continue
		}
		pos = abs + gt + 1
	}
	return html
}

// extractMainContent 从整页 HTML 里抽出正文主体
//
// 分级策略（命中即止）：
//   1. <article> 标签
//   2. 常见正文容器 id/class（article-content / rich_media_content / post-content …）
//   3. 段落最多的 <div>
//   4. 整个 body
func extractMainContent(html string) string {
	lower := strings.ToLower(html)

	// 1) article 标签
	if i := strings.Index(lower, "<article"); i >= 0 {
		if j := strings.Index(lower[i:], "</article>"); j > 0 {
			return html[i : i+j+len("</article>")]
		}
	}

	// 2) 常见正文容器
	candidates := []string{
		// 中文常见
		"rich_media_content", "js_content", "article-content", "article_content",
		"post-content", "post_content", "entry-content", "content-body",
		"main-content", "news-content", "detail-content", "artical-content",
		"article-body", "articleBody", "artibody", "post_body", "post-body",
		// 维基百科 / MediaWiki
		"mw-content-text", "mw-parser-output", "mw-body-content",
		// 知乎 / 掘金 / CSDN / 简书
		"Post-RichText", "RichText", "article_content", "blog-content-box",
		"markdown-body", "show-content", "article__content",
		// 英文媒体
		"story-body", "article__body", "c-entry-content", "post-body",
		"caas-body", "body__inner-container", "RichTextArticleBody",
	}
	for _, cls := range candidates {
		if seg := extractByClassOrID(html, cls); seg != "" {
			return seg
		}
	}

	// 3) 段落最多的 div
	if seg := densestDiv(html); seg != "" {
		return seg
	}

	// 4) body
	if i := strings.Index(lower, "<body"); i >= 0 {
		if j := strings.Index(lower[i:], ">"); j > 0 {
			start := i + j + 1
			if k := strings.Index(lower[start:], "</body>"); k > 0 {
				return html[start : start+k]
			}
		}
	}
	return html
}

// extractByClassOrID 按 class 或 id 取容器内容
//
// ★ 之前这里有 bug：深度计数把「下一个开标签的位置」和「闭合标签的位置」
//   用不同基准比较，导致维基百科这种深层嵌套的页面永远算不出正确闭合点，
//   结果提取到空串 → 正文只剩 134 字符。
//   现在统一交给 findMatchingClose 处理。
func extractByClassOrID(html, key string) string {
	lower := strings.ToLower(html)
	kl := strings.ToLower(key)

	for _, attr := range []string{"class", "id"} {
		needle := attr + "=\""
		pos := 0
		for {
			i := strings.Index(lower[pos:], needle)
			if i < 0 {
				break
			}
			abs := pos + i
			valStart := abs + len(needle)
			rel := strings.IndexByte(lower[valStart:], '"')
			if rel < 0 {
				break
			}
			val := lower[valStart : valStart+rel]
			if strings.Contains(val, kl) {
				tagStart := strings.LastIndex(lower[:abs], "<")
				if tagStart >= 0 {
					tagName := tagNameAt(lower, tagStart)
					if tagName != "" {
						gt := strings.IndexByte(lower[abs:], '>')
						if gt >= 0 {
							contentStart := abs + gt + 1
							if end := findMatchingClose(lower, contentStart, tagName); end > contentStart {
								return html[contentStart:end]
							}
						}
					}
				}
			}
			pos = valStart + rel
		}
	}
	return ""
}

// findMatchingClose 从 contentStart 开始找 tagName 的配对闭合标签起点
//
// 算法：一次扫描，遇到 <tagName 深度 +1，遇到 </tagName 深度 -1，
// 深度归零处即为闭合标签起点。两者用同一个扫描游标，不会错位。
func findMatchingClose(lower string, contentStart int, tagName string) int {
	openPrefix := "<" + tagName
	closePrefix := "</" + tagName
	depth := 1
	scan := contentStart
	for scan < len(lower) {
		nextOpen := strings.Index(lower[scan:], openPrefix)
		nextClose := strings.Index(lower[scan:], closePrefix)
		if nextClose < 0 {
			return -1
		}
		if nextOpen >= 0 && nextOpen < nextClose {
			// 确认是标签而不是 <divider 这类同前缀词
			after := scan + nextOpen + len(openPrefix)
			if after < len(lower) {
				c := lower[after]
				if c == ' ' || c == '>' || c == '\n' || c == '\t' || c == '/' {
					depth++
				}
			}
			scan = after
			continue
		}
		depth--
		if depth == 0 {
			return scan + nextClose
		}
		scan += nextClose + len(closePrefix)
	}
	return -1
}

func tagNameAt(s string, lt int) string {
	i := lt + 1
	for i < len(s) && (s[i] == ' ' || s[i] == '\n') {
		i++
	}
	start := i
	for i < len(s) {
		c := s[i]
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') {
			i++
			continue
		}
		break
	}
	if i == start {
		return ""
	}
	return s[start:i]
}

// densestDiv 找 <p> 最多的顶层 div（正文通常在段落最密的块里）
func densestDiv(html string) string {
	lower := strings.ToLower(html)
	best := ""
	bestScore := 0
	pos := 0
	for {
		i := strings.Index(lower[pos:], "<div")
		if i < 0 {
			break
		}
		abs := pos + i
		gt := strings.IndexByte(lower[abs:], '>')
		if gt < 0 {
			break
		}
		contentStart := abs + gt + 1
		end := findMatchingClose(lower, contentStart, "div")
		if end > contentStart {
			seg := html[contentStart:end]
			score := strings.Count(strings.ToLower(seg), "<p")
			if score > bestScore && len(seg) < 200000 {
				bestScore = score
				best = seg
			}
			pos = end
		} else {
			pos = contentStart
		}
	}
	if bestScore >= 3 {
		return best
	}
	return ""
}

var (
	// 需要整段删除的标签（脚本、样式、导航、广告位）
	dropBlockRe = []string{
		"<script", "<style", "<noscript", "<iframe", "<svg", "<form",
		"<nav", "<header", "<footer", "<aside",
	}
)

// sanitizeArticleHTML 去掉脚本/样式/广告，只留可读内容
//
// ★ 阅读安全性：一律移除 <script>，避免原文里的脚本在 App WebView 里执行。
func sanitizeArticleHTML(html string) string {
	lower := strings.ToLower(html)

	// 1) 删除整段危险/无用标签
	for _, tag := range dropBlockRe {
		for {
			i := strings.Index(lower, tag)
			if i < 0 {
				break
			}
			name := strings.TrimPrefix(tag, "<")
			// 自闭合标签直接删标签本身
			if name == "iframe" || name == "svg" {
				gt := strings.Index(lower[i:], ">")
				if gt < 0 {
					break
				}
				if string(lower[i:i+gt+1]) != "" && strings.HasSuffix(strings.TrimSpace(lower[i:i+gt+1]), "/>") {
					html = html[:i] + html[i+gt+1:]
					lower = strings.ToLower(html)
					continue
				}
			}
			closeTag := "</" + name
			j := strings.Index(lower[i:], closeTag)
			if j < 0 {
				// 没有闭合：只删开标签，避免误删后面全部内容
				gt := strings.Index(lower[i:], ">")
				if gt < 0 {
					break
				}
				html = html[:i] + html[i+gt+1:]
			} else {
				k := strings.Index(lower[i+j:], ">")
				if k < 0 {
					break
				}
				html = html[:i] + html[i+j+k+1:]
			}
			lower = strings.ToLower(html)
		}
	}

	// 2) 去内联事件处理器 onclick= 等（防 XSS）
	html = stripEventAttrs(html)

	// 3) 常见广告/分享容器整块删
	for _, cls := range []string{"advert", "adsbygoogle", "share-box", "social-share",
		"related-post", "recommend", "comment-area", "qrcode"} {
		if seg := extractByClassOrID(html, cls); seg != "" && len(seg) > 0 {
			// 只在该块很短时删（避免误删正文）
			if len(seg) < 3000 {
				html = strings.Replace(html, seg, "", 1)
			}
		}
	}

	// 4) 懒加载图片：data-src → src
	html = fixLazyImages(html)

	return strings.TrimSpace(html)
}

func stripEventAttrs(html string) string {
	lower := strings.ToLower(html)
	var out bytes.Buffer
	pos := 0
	for {
		i := strings.Index(lower[pos:], " on")
		if i < 0 {
			break
		}
		abs := pos + i
		// 找属性名结束
		j := abs + 1
		for j < len(lower) && lower[j] != '=' && lower[j] != '>' && lower[j] != ' ' {
			j++
		}
		name := strings.TrimSpace(lower[abs+1 : j])
		okName := strings.HasPrefix(name, "on") && len(name) > 2
		if !okName {
			out.WriteString(html[pos : abs+1])
			pos = abs + 1
			continue
		}
		// 跳过 = "value"
		k := j
		for k < len(lower) && (lower[k] == ' ' || lower[k] == '=') {
			k++
		}
		if k < len(lower) && (lower[k] == '"' || lower[k] == '\'') {
			quote := lower[k]
			k++
			for k < len(lower) && lower[k] != quote {
				k++
			}
			if k < len(lower) {
				k++
			}
		} else {
			for k < len(lower) && lower[k] != ' ' && lower[k] != '>' {
				k++
			}
		}
		out.WriteString(html[pos:abs])
		pos = k
	}
	out.WriteString(html[pos:])
	return out.String()
}

func fixLazyImages(html string) string {
	// data-src / data-original → src（懒加载图直接读 src 会是占位图）
	for _, attr := range []string{"data-src", "data-original", "data-lazy-src"} {
		lower := strings.ToLower(html)
		pos := 0
		for {
			i := strings.Index(lower[pos:], attr+"=")
			if i < 0 {
				break
			}
			abs := pos + i
			vStart := abs + len(attr) + 1
			if vStart >= len(html) {
				break
			}
			q := html[vStart]
			if q != '"' && q != '\'' {
				pos = vStart
				continue
			}
			vEnd := strings.IndexByte(html[vStart+1:], q)
			if vEnd < 0 {
				break
			}
			val := html[vStart+1 : vStart+1+vEnd]
			if val == "" {
				pos = vStart + 1 + vEnd
				continue
			}
			// 替换同一标签内的 src="..."
			tagStart := strings.LastIndex(lower[:abs], "<")
			tagEnd := strings.Index(lower[abs:], ">")
			if tagStart >= 0 && tagEnd > 0 {
				tag := html[tagStart : abs+tagEnd+1]
				newTag := replaceAttr(tag, "src", val)
				html = html[:tagStart] + newTag + html[abs+tagEnd+1:]
				lower = strings.ToLower(html)
			}
			pos = vStart + 1 + vEnd
		}
	}
	return html
}

func replaceAttr(tag, name, val string) string {
	lower := strings.ToLower(tag)
	needle := " " + name + "="
	i := strings.Index(lower, needle)
	if i < 0 {
		// 没有该属性：插到标签名后面
		sp := strings.Index(tag, " ")
		if sp < 0 {
			return tag
		}
		return tag[:sp] + " " + name + `="` + val + `"` + tag[sp:]
	}
	vStart := i + len(needle)
	if vStart >= len(tag) {
		return tag
	}
	q := tag[vStart]
	if q != '"' && q != '\'' {
		return tag
	}
	vEnd := strings.IndexByte(tag[vStart+1:], q)
	if vEnd < 0 {
		return tag
	}
	return tag[:vStart+1] + val + tag[vStart+1+vEnd:]
}

// ===== 阅读页 HTML 包装 =====

// wrapArticlePage 把正文片段包成完整可读页面（跟随客户端浅色主题）
func wrapArticlePage(title, source, bodyHTML string) string {
	title = htmlEscape(title)
	source = htmlEscape(source)

	var sb strings.Builder
	sb.WriteString(`<!DOCTYPE html><html lang="zh-CN"><head>`)
	sb.WriteString(`<meta charset="utf-8">`)
	sb.WriteString(`<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">`)
	sb.WriteString(`<title>` + title + `</title>`)
	sb.WriteString(`<style>
:root{color-scheme:light}
*{box-sizing:border-box}
body{margin:0;padding:16px 18px 40px;background:#f6f8fa;color:#1f2328;
  font:16px/1.75 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
  -webkit-text-size-adjust:100%;word-wrap:break-word;overflow-wrap:break-word}
h1.art-title{font-size:21px;line-height:1.4;margin:0 0 6px;font-weight:800;color:#111}
.art-meta{font-size:12.5px;color:#6a737d;margin-bottom:16px;padding-bottom:12px;border-bottom:1px solid #e1e4e8}
.art-body{font-size:16px}
.art-body p{margin:0 0 14px}
.art-body img{max-width:100%!important;height:auto!important;border-radius:8px;margin:10px 0;display:block}
.art-body h1,.art-body h2,.art-body h3,.art-body h4{line-height:1.4;margin:22px 0 10px;font-weight:800;color:#111}
.art-body h1{font-size:20px}.art-body h2{font-size:18.5px}.art-body h3{font-size:17px}.art-body h4{font-size:16px}
.art-body a{color:#0969da;text-decoration:none}
.art-body blockquote{margin:14px 0;padding:10px 14px;background:#eef1f5;border-left:3px solid #0969da;border-radius:0 8px 8px 0;color:#3d444d}
.art-body pre{background:#161b22;color:#e6edf3;padding:12px 14px;border-radius:8px;overflow-x:auto;font-size:13px;line-height:1.6}
.art-body code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:13.5px}
.art-body :not(pre)>code{background:#eef1f5;color:#cf222e;padding:1px 5px;border-radius:4px}
.art-body table{width:100%;border-collapse:collapse;margin:14px 0;font-size:14px;display:block;overflow-x:auto}
.art-body th,.art-body td{border:1px solid #d0d7de;padding:7px 10px;text-align:left}
.art-body th{background:#eef1f5;font-weight:700}
.art-body ul,.art-body ol{padding-left:22px;margin:0 0 14px}
.art-body li{margin:5px 0}
.art-body hr{border:none;border-top:1px solid #e1e4e8;margin:20px 0}
.art-body video,.art-body iframe{max-width:100%}
.art-body figure{margin:14px 0}
.art-body figcaption{font-size:12.5px;color:#6a737d;text-align:center;margin-top:6px}
.art-foot{margin-top:26px;padding-top:14px;border-top:1px solid #e1e4e8;
  font-size:12.5px;color:#6a737d;text-align:center}
</style></head><body>`)

	sb.WriteString(`<h1 class="art-title">` + title + `</h1>`)
	sb.WriteString(`<div class="art-meta">` + source + `</div>`)
	sb.WriteString(`<div class="art-body">` + bodyHTML + `</div>`)
	sb.WriteString(`<div class="art-foot">内容来自原文，超米仅做阅读优化</div>`)
	sb.WriteString(`</body></html>`)
	return sb.String()
}

func htmlEscape(s string) string {
	r := strings.NewReplacer(
		"&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;", "'", "&#39;",
	)
	return r.Replace(s)
}

// ===== HTTP 处理 =====

// handleRSSArticle GET /api/rss/article?id=123
//
// 返回完整阅读页 HTML；抓取失败时降级为 RSS 摘要页（不留白屏）
func handleRSSArticle(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	idStr := strings.TrimSpace(r.URL.Query().Get("id"))
	id, err := parseInt64Safe(idStr)
	if err != nil || id <= 0 {
		http.Error(w, "缺少 id", http.StatusBadRequest)
		return
	}

	var title, titleZh, link, summary, summaryZh, source, image string
	var pubAt int64
	err = db.QueryRow(`SELECT title,IFNULL(title_zh,''),link,IFNULL(summary,''),
		IFNULL(summary_zh,''),source,IFNULL(image,''),pub_at FROM rss_items WHERE id=?`, id).
		Scan(&title, &titleZh, &link, &summary, &summaryZh, &source, &image, &pubAt)
	if err != nil {
		http.Error(w, "资讯不存在", http.StatusNotFound)
		return
	}

	showTitle := title
	if titleZh != "" {
		showTitle = titleZh
	}

	// 先尝试抓原文
	bodyHTML, fullPage, ferr := fetchArticleHTML(link)
	if ferr != nil || textLen(bodyHTML) < 300 {
		// 降级：用 RSS 摘要 + 原文链接拼一个可读页面
		desc := summaryZh
		if desc == "" {
			desc = summary
		}
		var sb strings.Builder
		if image != "" {
			sb.WriteString(`<p><img src="` + htmlEscape(image) + `" alt=""></p>`)
		}
		if desc != "" {
			sb.WriteString(`<p>` + htmlEscape(desc) + `</p>`)
		}
		if titleZh != "" && title != "" && titleZh != title {
			sb.WriteString(`<p style="color:#6a737d;font-size:14px">原文标题：` +
				htmlEscape(title) + `</p>`)
		}
		sb.WriteString(`<p style="margin-top:18px"><a href="` + htmlEscape(link) +
			`">在浏览器中打开原文 →</a></p>`)
		if ferr != nil {
			sb.WriteString(`<p style="color:#9a6700;font-size:13px">（原文抓取失败：` +
				htmlEscape(ferr.Error()) + `）</p>`)
		}
		bodyHTML = sb.String()
		fullPage = false
	}

	// 图片相对路径补全为绝对路径，否则 App 里全是坏图
	if !fullPage {
		bodyHTML = absolutizeURLs(bodyHTML, link)
	}

	var page string
	if fullPage {
		// 整页模式：保留原页面，注入阅读覆盖样式
		page = injectReaderStyle(bodyHTML, link, showTitle, source)
	} else {
		page = wrapArticlePage(showTitle, source, bodyHTML)
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=1800")
	_, _ = w.Write([]byte(page))
}

// injectReaderStyle 整页模式下注入阅读优化样式
//
// 思路：**不破坏原页面**，只加重置规则压制导航/侧栏/弹窗，
// 并让正文宽度自适应手机屏。这样排版保真，又不会满屏菜单。
func injectReaderStyle(html, link, title, source string) string {
	style := `<style id="chaomi-reader">
html{-webkit-text-size-adjust:100%}
body{margin:0!important;padding:14px 16px 48px!important;
  background:#f6f8fa!important;color:#1f2328!important;
  font:16px/1.75 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif!important;
  max-width:100%!important;overflow-x:hidden!important}
img,video{max-width:100%!important;height:auto!important}
table{max-width:100%!important;display:block!important;overflow-x:auto!important}
pre{overflow-x:auto!important;background:#161b22!important;color:#e6edf3!important;
  padding:12px!important;border-radius:8px!important;font-size:13px!important}
/* 压制常见干扰元素：导航、侧栏、分享、评论、浮层 */
nav,header,footer,aside,
[class*="nav"],[class*="Nav"],[class*="header"],[class*="Header"],
[class*="footer"],[class*="Footer"],[class*="sidebar"],[class*="Sidebar"],
[class*="share"],[class*="Share"],[class*="comment"],[class*="Comment"],
[class*="related"],[class*="Related"],[class*="recommend"],[class*="Recommend"],
[class*="advert"],[class*="Advert"],[class*="banner"],[class*="Banner"],
[class*="popup"],[class*="Popup"],[class*="modal"],[class*="Modal"],
[class*="qrcode"],[class*="Qrcode"],[class*="app-download"],
[id*="nav"],[id*="header"],[id*="footer"],[id*="sidebar"],
[id*="share"],[id*="comment"],[id*="related"],[id*="banner"]
{display:none!important}
/* 固定定位的浮层（返回顶部、客服、下载 App）一律隐藏，避免遮正文 */
[style*="position:fixed"],[style*="position: fixed"]{display:none!important}
a{color:#0969da;text-decoration:none}
h1,h2,h3,h4{line-height:1.4!important;margin:20px 0 10px!important;color:#111!important}
p{margin:0 0 14px!important}
blockquote{margin:14px 0!important;padding:10px 14px!important;background:#eef1f5!important;
  border-left:3px solid #0969da!important;border-radius:0 8px 8px 0!important}
.chaomi-bar{position:sticky;top:0;z-index:99;background:#f6f8fa;
  border-bottom:1px solid #e1e4e8;padding:8px 0 10px;margin:-14px -16px 14px}
.chaomi-bar b{display:block;font-size:15.5px;color:#111;line-height:1.4}
.chaomi-bar span{font-size:11.5px;color:#6a737d}
</style>`

	bar := `<div class="chaomi-bar"><b>` + htmlEscape(title) + `</b><span>` +
		htmlEscape(source) + `</span></div>`

	lower := strings.ToLower(html)
	// 注入到 </head> 前
	if i := strings.Index(lower, "</head>"); i >= 0 {
		html = html[:i] + style + html[i:]
		lower = strings.ToLower(html)
	} else if i := strings.Index(lower, "<body"); i >= 0 {
		html = html[:i] + style + html[i:]
		lower = strings.ToLower(html)
	} else {
		html = "<html><head>" + style + "</head><body>" + html + "</body></html>"
		lower = strings.ToLower(html)
	}

	// 标题栏插到 <body ...> 之后
	if i := strings.Index(lower, "<body"); i >= 0 {
		if gt := strings.Index(lower[i:], ">"); gt >= 0 {
			pos := i + gt + 1
			html = html[:pos] + bar + html[pos:]
		}
	}

	// 补 <base> 让相对路径正确解析
	bu, err := url.Parse(link)
	if err == nil {
		base := `<base href="` + htmlEscape(bu.Scheme+"://"+bu.Host+bu.Path) + `">`
		if i := strings.Index(strings.ToLower(html), "<head>"); i >= 0 {
			html = html[:i+6] + base + html[i+6:]
		}
	}
	return html
}

// absolutizeURLs 把 img src / a href 的相对路径补成绝对地址
func absolutizeURLs(html, base string) string {
	bu, err := url.Parse(base)
	if err != nil {
		return html
	}
	origin := bu.Scheme + "://" + bu.Host
	dir := origin + bu.Path
	if i := strings.LastIndex(dir, "/"); i > 0 {
		dir = dir[:i+1]
	}

	lower := strings.ToLower(html)
	for _, attr := range []string{"src=", "href="} {
		pos := 0
		for {
			i := strings.Index(lower[pos:], attr)
			if i < 0 {
				break
			}
			abs := pos + i
			// 避免匹配到 data-src=
			if abs > 0 && (lower[abs-1] == 'a' || lower[abs-1] == '-') {
				pos = abs + len(attr)
				continue
			}
			vStart := abs + len(attr)
			if vStart >= len(html) {
				break
			}
			q := html[vStart]
			if q != '"' && q != '\'' {
				pos = vStart
				continue
			}
			vEnd := strings.IndexByte(html[vStart+1:], q)
			if vEnd < 0 {
				break
			}
			val := html[vStart+1 : vStart+1+vEnd]
			newVal := val
			switch {
			case strings.HasPrefix(val, "http://"), strings.HasPrefix(val, "https://"),
				strings.HasPrefix(val, "data:"), strings.HasPrefix(val, "//"),
				strings.HasPrefix(val, "#"), strings.HasPrefix(val, "mailto:"):
				// 保持原样（// 开头的补协议）
				if strings.HasPrefix(val, "//") {
					newVal = bu.Scheme + ":" + val
				}
			case strings.HasPrefix(val, "/"):
				newVal = origin + val
			case val == "":
				newVal = val
			default:
				newVal = dir + val
			}
			if newVal != val {
				html = html[:vStart+1] + newVal + html[vStart+1+vEnd:]
				lower = strings.ToLower(html)
			}
			pos = vStart + 1 + len(newVal)
		}
	}
	return html
}
