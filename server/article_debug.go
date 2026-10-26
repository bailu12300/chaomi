package main

// 本地解析自测：go run -tags test 不方便，这里做成临时路由
// 实际用 /api/article/debug 在测试环境验证

import (
	"net/http"
	"os"
	"strings"

	"github.com/PuerkitoBio/goquery"
)

// handleArticleDebug GET /api/article/debug?file=/tmp/x.html
// 只读本地文件，用于验证解析质量（生产环境无影响）
func handleArticleDebug(w http.ResponseWriter, r *http.Request) {
	f := strings.TrimSpace(r.URL.Query().Get("file"))
	if f == "" {
		writeJSON(w, 400, map[string]string{"error": "缺少 file"})
		return
	}
	// 只允许 /tmp 下的文件，避免被滥用读任意路径
	if !strings.HasPrefix(f, "/tmp/") {
		writeJSON(w, 403, map[string]string{"error": "只允许 /tmp 下的文件"})
		return
	}
	fh, err := os.Open(f)
	if err != nil {
		writeJSON(w, 404, map[string]string{"error": "文件不存在"})
		return
	}
	defer fh.Close()

	doc, err := goquery.NewDocumentFromReader(fh)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": "解析失败"})
		return
	}
	content := doc.Find("#js_content")
	if content.Length() == 0 {
		content = doc.Find("body")
	}
	blocks := cHTMLToBlocksV2(content)
	writeJSON(w, 200, map[string]interface{}{
		"code": 0, "data": map[string]interface{}{"count": len(blocks), "blocks": blocks},
	})
}
