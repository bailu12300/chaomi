package main

// ---------------- 公众号 / 网页正文解析（改进版） ----------------
//
// 相比旧版的改进：
//   1) blockquote / li / h2 / h3 不再用 n.Text()，改为走同一套「行内缓冲」，
//      避免嵌套结构把文字重复拼接（旧版排版错乱的根因）
//   2) 支持视频：mpvideo / iframe / video / mp-common-videosnap
//   3) 支持表格：转成 code 块保留列对齐，不再被压平成一坨
//   4) 保留超链接：文本里用 Markdown 形式，客户端可识别
//   5) 更细的块类型：h2/h3/quote/code/li/img/video/table/hr

import (
	"strings"

	"github.com/PuerkitoBio/goquery"
)

// cInlineText 收集一个节点下的「行内文本」，块级子元素会被切开。
// 这是修排版错乱的关键：绝不对容器调用 Text()。
func cInlineText(s *goquery.Selection) []string {
	var out []string
	var buf strings.Builder

	flush := func() {
		t := strings.TrimSpace(strings.ReplaceAll(buf.String(), "\u00a0", " "))
		t = strings.Join(strings.Fields(t), " ")
		buf.Reset()
		if t != "" {
			out = append(out, t)
		}
	}

	var walk func(*goquery.Selection)
	walk = func(sel *goquery.Selection) {
		sel.Contents().Each(func(_ int, n *goquery.Selection) {
			tag := goquery.NodeName(n)
			switch tag {
			case "#text":
				buf.WriteString(n.Text())
			case "br":
				flush()
			case "img", "video", "iframe", "mpvideo", "mp-common-videosnap":
				// 块级媒体出现在行内位置时也要断开
				flush()
			case "a":
				href, _ := n.Attr("href")
				href = strings.TrimSpace(href)
				inner := strings.TrimSpace(n.Text())
				if inner == "" {
					return
				}
				// 保留链接：Markdown 形式，客户端可保留可点
				if href != "" && !strings.HasPrefix(href, "javascript:") {
					buf.WriteString("[" + inner + "](" + href + ")")
				} else {
					buf.WriteString(inner)
				}
			case "p", "div", "section", "article", "blockquote", "li", "ul", "ol",
				"h1", "h2", "h3", "h4", "h5", "h6", "table", "tr", "td", "th",
				"figure", "figcaption", "pre", "hr":
				// 块级：先断行，再递归
				flush()
				walk(n)
				flush()
			default:
				if n.Children().Length() > 0 {
					walk(n)
				} else {
					buf.WriteString(n.Text())
				}
			}
		})
	}
	walk(s)
	flush()
	return out
}

// cVideoSrc 从各种视频标签里抠出可播放地址或封面
func cVideoSrc(n *goquery.Selection) (src, poster, kind string) {
	tag := goquery.NodeName(n)

	// 1) 标准 <video>
	if tag == "video" {
		src, _ = n.Attr("src")
		if strings.TrimSpace(src) == "" {
			if s := n.Find("source").First(); s.Length() > 0 {
				src, _ = s.Attr("src")
			}
		}
		poster, _ = n.Attr("poster")
		return strings.TrimSpace(src), strings.TrimSpace(poster), "video"
	}

	// 2) 微信 <mpvideo> / <mp-common-videosnap>
	if tag == "mpvideo" || tag == "mp-common-videosnap" {
		src, _ = n.Attr("src")
		poster, _ = n.Attr("data-cover")
		if poster == "" {
			poster, _ = n.Attr("poster")
		}
		if strings.TrimSpace(src) == "" {
			// 微信常用 data-mpvid，无法直接播放，但能记下 vid
			if vid, ok := n.Attr("data-mpvid"); ok && strings.TrimSpace(vid) != "" {
				return "", strings.TrimSpace(poster), "wechat_video"
			}
		}
		return strings.TrimSpace(src), strings.TrimSpace(poster), "video"
	}

	// 3) iframe 外链（腾讯视频/哔哩等）
	if tag == "iframe" {
		src, _ = n.Attr("data-src")
		if strings.TrimSpace(src) == "" {
			src, _ = n.Attr("src")
		}
		// 微信视频号 iframe 可能只有 data-mpvid
		return strings.TrimSpace(src), "", "iframe"
	}
	return "", "", ""
}

// cTableToText 把表格转成等宽文本，保留结构（列对齐）
func cTableToText(table *goquery.Selection) string {
	var rows [][]string
	maxCols := 0
	table.Find("tr").Each(func(_ int, tr *goquery.Selection) {
		var cells []string
		tr.Find("td,th").Each(func(_ int, td *goquery.Selection) {
			t := strings.Join(strings.Fields(td.Text()), " ")
			cells = append(cells, t)
		})
		if len(cells) > 0 {
			rows = append(rows, cells)
			if len(cells) > maxCols {
				maxCols = len(cells)
			}
		}
	})
	if len(rows) == 0 {
		return ""
	}
	if maxCols == 1 {
		var sb strings.Builder
		for _, r := range rows {
			sb.WriteString(r[0])
			sb.WriteString("\n")
		}
		return strings.TrimRight(sb.String(), "\n")
	}
	// 计算每列宽度（按 rune 计，中文算 2 宽）
	width := func(s string) int {
		w := 0
		for _, r := range s {
			if r > 0x2E80 {
				w += 2
			} else {
				w++
			}
		}
		return w
	}
	pad := func(s string, w int) string {
		d := w - width(s)
		if d <= 0 {
			return s
		}
		return s + strings.Repeat(" ", d)
	}
	colW := make([]int, maxCols)
	for _, r := range rows {
		for i, c := range r {
			if w := width(c); w > colW[i] {
				colW[i] = w
			}
		}
	}
	var sb strings.Builder
	for ri, r := range rows {
		for i := 0; i < maxCols; i++ {
			c := ""
			if i < len(r) {
				c = r[i]
			}
			if i == maxCols-1 {
				sb.WriteString(c)
			} else {
				sb.WriteString(pad(c, colW[i]+2))
			}
		}
		sb.WriteString("\n")
		// 表头下加分隔线
		if ri == 0 {
			for i := 0; i < maxCols; i++ {
				sb.WriteString(strings.Repeat("-", colW[i]))
				if i < maxCols-1 {
					sb.WriteString("  ")
				}
			}
			sb.WriteString("\n")
		}
	}
	return strings.TrimRight(sb.String(), "\n")
}

// cHTMLToBlocksV2 改进版正文转块
func cHTMLToBlocksV2(content *goquery.Selection) []CBlock {
	blocks := []CBlock{}
	content.Find("script,style,noscript,link,meta").Remove()
	// iframe 保留（可能是视频），但普通广告 iframe 过滤
	content.Find("iframe").Each(func(_ int, f *goquery.Selection) {
		src, _ := f.Attr("src")
		src, _ = f.Attr("data-src")
		low := strings.ToLower(src)
		if strings.Contains(low, "pos.baidu") || strings.Contains(low, "cpro") ||
			strings.Contains(low, "googlesyndication") {
			f.Remove()
		}
	})

	add := func(b CBlock) {
		blocks = append(blocks, b)
	}

	var walk func(s *goquery.Selection)
	walk = func(s *goquery.Selection) {
		s.Contents().Each(func(_ int, n *goquery.Selection) {
			tag := goquery.NodeName(n)
			switch tag {
			case "#text":
				t := strings.TrimSpace(strings.ReplaceAll(n.Text(), "\u00a0", " "))
				if t != "" {
					add(CBlock{Type: "p", Text: strings.Join(strings.Fields(t), " ")})
				}
			case "h1", "h2":
				if t := strings.Join(cInlineText(n), " "); t != "" {
					add(CBlock{Type: "h2", Text: t})
				}
			case "h3", "h4", "h5", "h6":
				if t := strings.Join(cInlineText(n), " "); t != "" {
					add(CBlock{Type: "h3", Text: t})
				}
			case "blockquote":
				// ★ 关键修复：走行内缓冲，不再 n.Text()
				for _, p := range cInlineText(n) {
					add(CBlock{Type: "quote", Text: p})
				}
			case "pre":
				if t := strings.TrimSpace(n.Text()); t != "" {
					add(CBlock{Type: "code", Text: t})
				}
			case "code":
				if t := strings.TrimSpace(n.Text()); t != "" {
					add(CBlock{Type: "code", Text: t})
				}
			case "li":
				// ★ 同样修复：逐个行内片段成独立条目
				parts := cInlineText(n)
				if len(parts) > 0 {
					add(CBlock{Type: "li", Text: "• " + parts[0]})
					for _, p := range parts[1:] {
						add(CBlock{Type: "p", Text: p})
					}
				}
			case "img":
				src, _ := n.Attr("data-src")
				if strings.TrimSpace(src) == "" {
					src, _ = n.Attr("src")
				}
				src = strings.TrimSpace(src)
				if src != "" && !strings.HasPrefix(src, "data:") {
					alt, _ := n.Attr("alt")
					add(CBlock{Type: "img", Src: src, Alt: strings.TrimSpace(alt)})
				}
			case "video", "mpvideo", "mp-common-videosnap", "iframe":
				src, poster, kind := cVideoSrc(n)
				if src != "" || poster != "" {
					add(CBlock{Type: "video", Src: src, Alt: poster, Text: kind})
				}
			case "table":
				if t := cTableToText(n); t != "" {
					add(CBlock{Type: "code", Text: t})
				}
			case "hr":
				add(CBlock{Type: "hr"})
			case "br":
				// 单独 br 不产生块
			case "p", "div", "section", "article", "figure", "figcaption",
				"ul", "ol", "dl", "dd", "dt", "span", "strong", "em", "font", "a":
				// 容器 / 行内：先看是否含块级子元素
				if n.Children().Length() > 0 {
					hasBlock := false
					n.Children().Each(func(_ int, c *goquery.Selection) {
						switch goquery.NodeName(c) {
						case "p", "div", "section", "table", "ul", "ol", "blockquote",
							"h1", "h2", "h3", "h4", "h5", "h6", "pre", "img", "video",
							"mpvideo", "mp-common-videosnap", "iframe", "figure",
							"li", "hr", "tr":   // ★ li/tr 也要算块级，否则 ul>li 会被合并成一段
							hasBlock = true
						}
					})
					if hasBlock {
						walk(n)
					} else {
						// 纯行内容器：整段抽取
						if t := strings.Join(cInlineText(n), " "); t != "" {
							add(CBlock{Type: "p", Text: t})
						}
					}
				} else if t := strings.TrimSpace(n.Text()); t != "" {
					add(CBlock{Type: "p", Text: strings.Join(strings.Fields(t), " ")})
				}
			default:
				if n.Children().Length() > 0 {
					walk(n)
				} else if t := strings.TrimSpace(n.Text()); t != "" {
					add(CBlock{Type: "p", Text: strings.Join(strings.Fields(t), " ")})
				}
			}
		})
	}
	walk(content)

	// 后处理：合并被误切的短段落、去重连续重复
	out := make([]CBlock, 0, len(blocks))
	for _, b := range blocks {
		if b.Text != "" {
			b.Text = strings.TrimSpace(b.Text)
		}
		// 连续重复段（公众号模板常见）
		if len(out) > 0 {
			last := out[len(out)-1]
			if last.Type == b.Type && last.Text == b.Text && b.Text != "" {
				continue
			}
			// 极短段落（<=2 字）并入上一段，避免碎行
			if b.Type == "p" && len([]rune(b.Text)) <= 2 && last.Type == "p" {
				out[len(out)-1].Text = strings.TrimSpace(last.Text + " " + b.Text)
				continue
			}
		}
		if b.Type == "p" && b.Text == "" {
			continue
		}
		if b.Type == "hr" && len(out) == 0 {
			continue
		}
		out = append(out, b)
	}
	if len(out) > 500 {
		out = out[:500]
	}
	return out
}
