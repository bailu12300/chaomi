package main

// ---------------- 万能格式转换 ----------------
//
// 设计取舍：**不依赖任何外部二进制**。
//   服务器上没有 ffmpeg / ImageMagick / libreoffice / pandoc，
//   装这些动辄几百 MB 且容易和宝塔环境打架。
//   所以只用 Go 本身能做到的，全部本地完成、零外部调用：
//
//   1) 图片互转：PNG / JPEG / GIF / BMP / WebP(解码) → PNG / JPEG / GIF / BMP
//      用标准库 + golang.org/x/image（webp/bmp 解码）
//   2) 数据格式互转：JSON / YAML / CSV / TSV / XML / Markdown表格 / SQL INSERT
//   3) 编码转换：Base64 / URL / Hex / HTML 实体 / Unicode 转义
//   4) 文本工具：JSON 美化/压缩、时间戳↔日期、进制转换
//
// 这些都是"确定能做到且结果可验证"的，不做能力之外的假承诺。

import (
	"bytes"
	"encoding/base64"
	"encoding/csv"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"html"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"image/png"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"golang.org/x/image/bmp"
	_ "golang.org/x/image/webp"
)

// ---- 图片 ----

// decodeAnyImage 解码任意支持的图片格式（webp 走 x/image 的注册解码器）
func decodeAnyImage(data []byte) (image.Image, string, error) {
	img, format, err := image.Decode(bytes.NewReader(data))
	if err == nil {
		return img, format, nil
	}
	// image.Decode 已注册 png/jpeg/gif/webp/bmp(x/image)，再试一次 bmp 兜底
	if b, e2 := bmp.Decode(bytes.NewReader(data)); e2 == nil {
		return b, "bmp", nil
	}
	if g, e3 := gif.Decode(bytes.NewReader(data)); e3 == nil {
		return g, "gif", nil
	}
	return nil, "", fmt.Errorf("无法识别图片格式（支持 PNG/JPEG/GIF/BMP/WebP）")
}

// encodeImage 按目标格式编码；带质量参数与背景色（JPEG 不支持透明，需要铺白底）
func encodeImage(img image.Image, target string, quality int) ([]byte, string, error) {
	if quality <= 0 || quality > 100 {
		quality = 90
	}
	var buf bytes.Buffer
	switch target {
	case "png":
		if err := png.Encode(&buf, img); err != nil {
			return nil, "", err
		}
		return buf.Bytes(), "image/png", nil

	case "jpg", "jpeg":
		// PNG 带透明通道时直接转 JPEG 会得到黑底，这里先铺白
		flat := flattenOnWhite(img)
		if err := jpeg.Encode(&buf, flat, &jpeg.Options{Quality: quality}); err != nil {
			return nil, "", err
		}
		return buf.Bytes(), "image/jpeg", nil

	case "gif":
		if err := gif.Encode(&buf, img, nil); err != nil {
			return nil, "", err
		}
		return buf.Bytes(), "image/gif", nil

	case "bmp":
		if err := bmp.Encode(&buf, img); err != nil {
			return nil, "", err
		}
		return buf.Bytes(), "image/bmp", nil

	default:
		return nil, "", fmt.Errorf("不支持的目标图片格式：%s", target)
	}
}

// flattenOnWhite 把透明区域铺成白色（转 JPEG 必需）
func flattenOnWhite(src image.Image) image.Image {
	b := src.Bounds()
	dst := image.NewRGBA(b)
	white := color.RGBA{255, 255, 255, 255}
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			r, g, bl, a := src.At(x, y).RGBA()
			if a == 0 {
				dst.Set(x, y, white)
				continue
			}
			// 与非透明白底做 alpha 混合
			af := float64(a) / 65535.0
			dst.Set(x, y, color.RGBA{
				R: uint8(float64(r>>8)*af + 255*(1-af)),
				G: uint8(float64(g>>8)*af + 255*(1-af)),
				B: uint8(float64(bl>>8)*af + 255*(1-af)),
				A: 255,
			})
		}
	}
	return dst
}

// handleImageConvert POST /api/convert/image（multipart: file, to=png|jpg|gif|bmp, quality）
func handleImageConvert(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	// 20MB 上限，避免被大图拖死
	if err := r.ParseMultipartForm(20 << 20); err != nil {
		writeJSON(w, 400, map[string]string{"error": "上传失败或文件过大（上限 20MB）"})
		return
	}
	f, hdr, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": "没有收到文件"})
		return
	}
	defer f.Close()

	to := strings.ToLower(strings.TrimSpace(r.FormValue("to")))
	if to == "" {
		to = "png"
	}
	quality := 90
	if q := r.FormValue("quality"); q != "" {
		if n, e := strconv.Atoi(q); e == nil {
			quality = n
		}
	}

	data, err := io.ReadAll(io.LimitReader(f, 20<<20))
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": "读取文件失败"})
		return
	}

	img, srcFmt, err := decodeAnyImage(data)
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	out, ctype, err := encodeImage(img, to, quality)
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": "转换失败：" + err.Error()})
		return
	}

	b := img.Bounds()
	name := baseNameNoExt(hdr.Filename) + "." + normExt(to)
	w.Header().Set("Content-Type", ctype)
	w.Header().Set("Content-Disposition", "attachment; filename=\""+name+"\"")
	w.Header().Set("X-Source-Format", srcFmt)
	w.Header().Set("X-Image-Size", fmt.Sprintf("%dx%d", b.Dx(), b.Dy()))
	w.Header().Set("Access-Control-Expose-Headers", "X-Source-Format,X-Image-Size")
	w.WriteHeader(200)
	w.Write(out)
}

func baseNameNoExt(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	if i := strings.LastIndex(name, "/"); i >= 0 {
		name = name[i+1:]
	}
	if i := strings.LastIndex(name, "."); i > 0 {
		name = name[:i]
	}
	if name == "" {
		name = "converted"
	}
	return name
}

func normExt(to string) string {
	switch strings.ToLower(to) {
	case "jpg", "jpeg":
		return "jpg"
	case "png", "gif", "bmp":
		return strings.ToLower(to)
	}
	return "png"
}

// ---- 数据格式转换 ----

// genericDoc 中间表示：所有格式先转成它，再从它转出
type genericDoc struct {
	Columns []string
	Rows    [][]string
	IsTable bool          // 是否为表格型数据（CSV/表格）
	Any     interface{}   // 非表格型（JSON 对象/数组）
}

// parseData 把输入文本按 from 格式解析
func parseData(from, text string) (*genericDoc, error) {
	switch strings.ToLower(from) {
	case "json":
		var v interface{}
		if err := json.Unmarshal([]byte(text), &v); err != nil {
			return nil, fmt.Errorf("JSON 解析失败：%v", err)
		}
		d := &genericDoc{Any: v}
		// 若是一组同构对象，抽成表格（转 CSV 用）
		if arr, ok := v.([]interface{}); ok && len(arr) > 0 {
			if m, ok2 := arr[0].(map[string]interface{}); ok2 {
				cols := make([]string, 0, len(m))
				for k := range m {
					cols = append(cols, k)
				}
				sort.Strings(cols)
				d.Columns = cols
				d.IsTable = true
				for _, it := range arr {
					mm, ok3 := it.(map[string]interface{})
					if !ok3 {
						d.IsTable = false
						break
					}
					row := make([]string, len(cols))
					for i, c := range cols {
						row[i] = scalarStr(mm[c])
					}
					d.Rows = append(d.Rows, row)
				}
			}
		}
		return d, nil

	case "csv", "tsv":
		sep := ','
		if strings.ToLower(from) == "tsv" {
			sep = '\t'
		}
		rd := csv.NewReader(strings.NewReader(text))
		rd.Comma = sep
		rd.FieldsPerRecord = -1
		recs, err := rd.ReadAll()
		if err != nil {
			return nil, fmt.Errorf("CSV 解析失败：%v", err)
		}
		if len(recs) == 0 {
			return nil, fmt.Errorf("内容为空")
		}
		d := &genericDoc{IsTable: true, Columns: recs[0], Rows: recs[1:]}
		return d, nil

	case "yaml":
		// 不引入 yaml 库：只支持「key: value」的简单层（够用且零依赖）
		var v interface{}
		lines := strings.Split(text, "\n")
		simple := map[string]interface{}{}
		ok := true
		for _, ln := range lines {
			t := strings.TrimSpace(ln)
			if t == "" || strings.HasPrefix(t, "#") {
				continue
			}
			if strings.HasPrefix(t, "- ") {
				ok = false
				break
			}
			i := strings.Index(t, ":")
			if i <= 0 {
				ok = false
				break
			}
			k := strings.TrimSpace(t[:i])
			val := strings.TrimSpace(t[i+1:])
			simple[k] = coerceScalar(val)
		}
		if !ok {
			return nil, fmt.Errorf("仅支持简单 YAML（每行 key: value）；复杂结构请用 JSON")
		}
		v = simple
		// 同时提供表格视图
		cols := make([]string, 0, len(simple))
		for k := range simple {
			cols = append(cols, k)
		}
		sort.Strings(cols)
		row := make([]string, len(cols))
		for i, c := range cols {
			row[i] = scalarStr(simple[c])
		}
		return &genericDoc{Any: v, IsTable: true, Columns: cols, Rows: [][]string{row}}, nil

	case "xml":
		// 只做「转义后的文本」，XML→表格不在能力内，明确提示
		return nil, fmt.Errorf("XML 作为源格式暂不支持，请用 JSON / CSV / YAML")

	case "markdown", "md":
		// 支持标准 Markdown 表格
		lines := strings.Split(strings.TrimSpace(text), "\n")
		var rows [][]string
		for _, ln := range lines {
			t := strings.TrimSpace(ln)
			if !strings.HasPrefix(t, "|") {
				continue
			}
			cells := strings.Split(strings.Trim(t, "|"), "|")
			out := make([]string, len(cells))
			skip := true
			for i, c := range cells {
				c = strings.TrimSpace(c)
				out[i] = c
				if c != "" && !strings.HasPrefix(c, "-") && !strings.HasPrefix(c, ":") {
					skip = false
				}
			}
			if skip && len(out) > 0 {
				continue // 分隔行 |---|---|
			}
			rows = append(rows, out)
		}
		if len(rows) == 0 {
			return nil, fmt.Errorf("没找到 Markdown 表格（需要 | 列 | 列 | 形式）")
		}
		return &genericDoc{IsTable: true, Columns: rows[0], Rows: rows[1:]}, nil
	}
	return nil, fmt.Errorf("不支持的源格式：%s", from)
}

func coerceScalar(s string) interface{} {
	s = strings.Trim(s, "\"'")
	if s == "true" {
		return true
	}
	if s == "false" {
		return false
	}
	if s == "null" || s == "~" {
		return nil
	}
	if n, err := strconv.ParseFloat(s, 64); err == nil {
		return n
	}
	return s
}

func scalarStr(v interface{}) string {
	switch t := v.(type) {
	case nil:
		return ""
	case string:
		return t
	case bool:
		return strconv.FormatBool(t)
	case float64:
		if t == float64(int64(t)) {
			return strconv.FormatInt(int64(t), 10)
		}
		return strconv.FormatFloat(t, 'f', -1, 64)
	default:
		b, _ := json.Marshal(t)
		return string(b)
	}
}

// renderData 把中间表示输出成目标格式
func renderData(d *genericDoc, to string) (string, string, error) {
	switch strings.ToLower(to) {
	case "json":
		var v interface{}
		if d.IsTable && len(d.Columns) > 0 {
			arr := make([]map[string]interface{}, 0, len(d.Rows))
			for _, row := range d.Rows {
				m := map[string]interface{}{}
				for i, c := range d.Columns {
					if i < len(row) {
						m[c] = coerceScalar(row[i])
					}
				}
				arr = append(arr, m)
			}
			v = arr
		} else {
			v = d.Any
		}
		b, err := json.MarshalIndent(v, "", "  ")
		if err != nil {
			return "", "", err
		}
		return string(b), "application/json", nil

	case "csv", "tsv":
		if !d.IsTable {
			return "", "", fmt.Errorf("该数据不是表格结构，无法转 CSV")
		}
		var buf bytes.Buffer
		wr := csv.NewWriter(&buf)
		if strings.ToLower(to) == "tsv" {
			wr.Comma = '\t'
		}
		wr.Write(d.Columns)
		for _, row := range d.Rows {
			wr.Write(row)
		}
		wr.Flush()
		if err := wr.Error(); err != nil {
			return "", "", err
		}
		return buf.String(), "text/csv", nil

	case "yaml":
		var sb strings.Builder
		// ★ 若源是 JSON（保留了原始结构 d.Any），优先按原结构输出。
		//   否则 [{"a":1}] 会被表格化渲染成 "a: 1"，丢掉列表语义。
		if d.Any != nil {
			switch d.Any.(type) {
			case []interface{}, map[string]interface{}:
				sb.WriteString(toYAML(d.Any, 0))
				return sb.String(), "text/yaml", nil
			}
		}
		if d.IsTable && len(d.Rows) == 1 {
			for i, c := range d.Columns {
				if i < len(d.Rows[0]) {
					sb.WriteString(c + ": " + yamlSafe(d.Rows[0][i]) + "\n")
				}
			}
		} else if d.IsTable {
			for _, row := range d.Rows {
				sb.WriteString("- ")
				for i, c := range d.Columns {
					if i < len(row) {
						if i > 0 {
							sb.WriteString("  ")
						}
						sb.WriteString(c + ": " + yamlSafe(row[i]) + "\n")
					}
				}
			}
		} else {
			// 真正的 YAML 序列化（不用 JSON 冒充，缩进 2 空格）
			sb.WriteString(toYAML(d.Any, 0))
		}
		return sb.String(), "text/yaml", nil

	case "markdown", "md":
		if !d.IsTable {
			return "", "", fmt.Errorf("该数据不是表格结构，无法转 Markdown 表格")
		}
		var sb strings.Builder
		sb.WriteString("| " + strings.Join(d.Columns, " | ") + " |\n")
		seps := make([]string, len(d.Columns))
		for i := range seps {
			seps[i] = "---"
		}
		sb.WriteString("| " + strings.Join(seps, " | ") + " |\n")
		for _, row := range d.Rows {
			cells := make([]string, len(d.Columns))
			for i := range d.Columns {
				if i < len(row) {
					cells[i] = strings.ReplaceAll(row[i], "|", "\\|")
				}
			}
			sb.WriteString("| " + strings.Join(cells, " | ") + " |\n")
		}
		return sb.String(), "text/markdown", nil

	case "xml":
		if !d.IsTable {
			return "", "", fmt.Errorf("该数据不是表格结构，无法转 XML")
		}
		var sb strings.Builder
		sb.WriteString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rows>\n")
		for _, row := range d.Rows {
			sb.WriteString("  <row>\n")
			for i, c := range d.Columns {
				tag := sanitizeXMLTag(c)
				val := ""
				if i < len(row) {
					val = row[i]
				}
				sb.WriteString("    <" + tag + ">" + xmlEscape(val) + "</" + tag + ">\n")
			}
			sb.WriteString("  </row>\n")
		}
		sb.WriteString("</rows>\n")
		return sb.String(), "application/xml", nil

	case "sql":
		if !d.IsTable {
			return "", "", fmt.Errorf("该数据不是表格结构，无法转 SQL")
		}
		table := "converted"
		cols := make([]string, len(d.Columns))
		for i, c := range d.Columns {
			cols[i] = "`" + strings.ReplaceAll(c, "`", "") + "`"
		}
		var sb strings.Builder
		sb.WriteString("CREATE TABLE IF NOT EXISTS `" + table + "` (\n")
		for i, c := range cols {
			comma := ","
			if i == len(cols)-1 {
				comma = ""
			}
			sb.WriteString("  " + c + " TEXT" + comma + "\n")
		}
		sb.WriteString(");\n\n")
		for _, row := range d.Rows {
			vals := make([]string, len(d.Columns))
			for i := range d.Columns {
				v := ""
				if i < len(row) {
					v = row[i]
				}
				vals[i] = "'" + strings.ReplaceAll(v, "'", "''") + "'"
			}
			sb.WriteString("INSERT INTO `" + table + "` (" +
				strings.Join(cols, ",") + ") VALUES (" + strings.Join(vals, ",") + ");\n")
		}
		return sb.String(), "application/sql", nil
	}
	return "", "", fmt.Errorf("不支持的目标格式：%s", to)
}

func yamlSafe(s string) string {
	if s == "" {
		return "\"\""
	}
	if strings.ContainsAny(s, ":#{}[]&*!|>'\"%@`,\n") ||
		strings.HasPrefix(s, " ") || strings.HasSuffix(s, " ") {
		b, _ := json.Marshal(s)
		return string(b)
	}
	return s
}

func xmlEscape(s string) string {
	var b bytes.Buffer
	xml.EscapeText(&b, []byte(s))
	return b.String()
}

func sanitizeXMLTag(s string) string {
	if s == "" {
		return "col"
	}
	var sb strings.Builder
	for i, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || r == '_' ||
			(i > 0 && r >= '0' && r <= '9') {
			sb.WriteRune(r)
		} else {
			sb.WriteByte('_')
		}
	}
	out := sb.String()
	if out == "" {
		return "col"
	}
	return out
}

// handleDataConvert POST /api/convert/data {from, to, text}
func handleDataConvert(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	var b struct {
		From string `json:"from"`
		To   string `json:"to"`
		Text string `json:"text"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 8<<20)).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}
	if strings.TrimSpace(b.Text) == "" {
		writeJSON(w, 400, map[string]string{"error": "请输入要转换的内容"})
		return
	}
	if b.From == "" {
		b.From = "json"
	}
	if b.To == "" {
		b.To = "csv"
	}
	if strings.EqualFold(b.From, b.To) {
		writeJSON(w, 400, map[string]string{"error": "源格式和目标格式相同"})
		return
	}

	d, err := parseData(b.From, b.Text)
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	out, ctype, err := renderData(d, b.To)
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"from": b.From, "to": b.To,
			"result": out, "mime": ctype,
			"rows": len(d.Rows), "cols": len(d.Columns),
		},
	})
}

// ---- 编码 / 文本工具 ----

// handleCodec POST /api/convert/codec {op, text}
func handleCodec(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	var b struct {
		Op   string `json:"op"`
		Text string `json:"text"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "请求体解析失败"})
		return
	}
	text := b.Text
	var out string
	var err error

	switch strings.ToLower(b.Op) {
	case "b64enc":
		out = base64.StdEncoding.EncodeToString([]byte(text))
	case "b64dec":
		var raw []byte
		raw, err = base64.StdEncoding.DecodeString(strings.TrimSpace(text))
		out = string(raw)
	case "urlenc":
		out = url.QueryEscape(text)
	case "urldec":
		var s string
		s, err = url.QueryUnescape(text)
		out = s
	case "hexenc":
		out = hex.EncodeToString([]byte(text))
	case "hexdec":
		var raw []byte
		raw, err = hex.DecodeString(strings.TrimSpace(text))
		out = string(raw)
	case "htmlenc":
		out = html.EscapeString(text)
	case "htmldec":
		out = html.UnescapeString(text)
	case "jsonpretty":
		var v interface{}
		if e := json.Unmarshal([]byte(text), &v); e != nil {
			err = fmt.Errorf("JSON 无效：%v", e)
			break
		}
		var bb []byte
		bb, err = json.MarshalIndent(v, "", "  ")
		out = string(bb)
	case "jsonmin":
		var v interface{}
		if e := json.Unmarshal([]byte(text), &v); e != nil {
			err = fmt.Errorf("JSON 无效：%v", e)
			break
		}
		var bb []byte
		bb, err = json.Marshal(v)
		out = string(bb)
	case "ts2date":
		n, e := strconv.ParseInt(strings.TrimSpace(text), 10, 64)
		if e != nil {
			err = fmt.Errorf("请输入数字时间戳")
			break
		}
		// 10 位按秒，13 位按毫秒
		if len(strings.TrimSpace(text)) <= 10 {
			n *= 1000
		}
		t := time.UnixMilli(n)
		out = t.Format("2006-01-02 15:04:05") + "（UTC" +
			t.Format("-07:00") + "）"
	case "date2ts":
		layouts := []string{"2006-01-02 15:04:05", "2006-01-02T15:04:05", "2006-01-02", "2006/01/02 15:04:05"}
		var t time.Time
		okp := false
		for _, l := range layouts {
			if tt, e := time.ParseInLocation(l, strings.TrimSpace(text), time.Local); e == nil {
				t = tt
				okp = true
				break
			}
		}
		if !okp {
			err = fmt.Errorf("无法解析日期，试试 2024-01-01 12:00:00")
			break
		}
		out = strconv.FormatInt(t.UnixMilli(), 10)
	case "upper":
		out = strings.ToUpper(text)
	case "lower":
		out = strings.ToLower(text)
	case "trim":
		lines := strings.Split(text, "\n")
		for i := range lines {
			lines[i] = strings.TrimSpace(lines[i])
		}
		out = strings.Join(lines, "\n")
	default:
		err = fmt.Errorf("不支持的操作：%s", b.Op)
	}

	if err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{"op": b.Op, "result": out},
	})
}

// handleConvertCaps GET /api/convert/caps —— 前端据此渲染可用选项
func handleConvertCaps(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]interface{}{
		"code": 0,
		"data": map[string]interface{}{
			"image": map[string]interface{}{
				"from": []string{"png", "jpg", "jpeg", "gif", "bmp", "webp"},
				"to":   []string{"png", "jpg", "gif", "bmp"},
				"maxMB": 20,
			},
			"data": map[string]interface{}{
				"from": []string{"json", "csv", "tsv", "yaml", "markdown"},
				"to":   []string{"json", "csv", "tsv", "yaml", "markdown", "xml", "sql"},
			},
			"codec": []map[string]string{
				{"op": "b64enc", "name": "Base64 编码"},
				{"op": "b64dec", "name": "Base64 解码"},
				{"op": "urlenc", "name": "URL 编码"},
				{"op": "urldec", "name": "URL 解码"},
				{"op": "hexenc", "name": "Hex 编码"},
				{"op": "hexdec", "name": "Hex 解码"},
				{"op": "htmlenc", "name": "HTML 转义"},
				{"op": "htmldec", "name": "HTML 反转义"},
				{"op": "jsonpretty", "name": "JSON 美化"},
				{"op": "jsonmin", "name": "JSON 压缩"},
				{"op": "ts2date", "name": "时间戳 → 日期"},
				{"op": "date2ts", "name": "日期 → 时间戳"},
				{"op": "upper", "name": "转大写"},
				{"op": "lower", "name": "转小写"},
				{"op": "trim", "name": "去行首尾空格"},
			},
		},
	})
}


// toYAML 把任意 JSON 结构渲染成真正的 YAML（2 空格缩进）。
//
// 为什么自己写：项目不想为一个转换功能引入 yaml 依赖（go.mod 已很克制），
// 而 JSON→YAML 的映射规则本身很直接：map 展开成 key: value，slice 用 "- "，
// 标量按类型输出并把需要转义的字符串加引号。
func toYAML(v interface{}, indent int) string {
	pad := strings.Repeat("  ", indent)
	var sb strings.Builder

	switch t := v.(type) {
	case map[string]interface{}:
		if len(t) == 0 {
			sb.WriteString("{}\n")
			return sb.String()
		}
		keys := make([]string, 0, len(t))
		for k := range t {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			val := t[k]
			switch val.(type) {
			case map[string]interface{}, []interface{}:
				sb.WriteString(pad + yamlSafeKey(k) + ":\n")
				sb.WriteString(toYAML(val, indent+1))
			default:
				sb.WriteString(pad + yamlSafeKey(k) + ": " + yamlScalar(val) + "\n")
			}
		}

	case []interface{}:
		if len(t) == 0 {
			sb.WriteString("[]\n")
			return sb.String()
		}
		for _, item := range t {
			switch item.(type) {
			case map[string]interface{}, []interface{}:
				// 列表项是对象：第一行接在 "- " 后面，其余缩进对齐
				sub := toYAML(item, indent+1)
				lines := strings.Split(strings.TrimRight(sub, "\n"), "\n")
				if len(lines) > 0 {
					first := strings.TrimLeft(lines[0], " ")
					sb.WriteString(pad + "- " + first + "\n")
					for _, ln := range lines[1:] {
						sb.WriteString(pad + "  " + strings.TrimLeft(ln, " ") + "\n")
					}
				}
			default:
				sb.WriteString(pad + "- " + yamlScalar(item) + "\n")
			}
		}

	default:
		sb.WriteString(pad + yamlScalar(v) + "\n")
	}
	return sb.String()
}

// yamlScalar 标量输出：数字/布尔/null 裸写，字符串按需加引号
func yamlScalar(v interface{}) string {
	switch t := v.(type) {
	case nil:
		return "null"
	case bool:
		return strconv.FormatBool(t)
	case float64:
		if t == float64(int64(t)) {
			return strconv.FormatInt(int64(t), 10)
		}
		return strconv.FormatFloat(t, 'f', -1, 64)
	case string:
		if t == "" {
			return "\"\""
		}
		// 会被 YAML 误解析为其它类型、或含特殊字符 → 加双引号
		low := strings.ToLower(t)
		needQuote := strings.ContainsAny(t, ":#{}[]&*!|>'\"%@`,\n\t") ||
			t != strings.TrimSpace(t) ||
			low == "true" || low == "false" || low == "null" || low == "yes" || low == "no" ||
			low == "on" || low == "off" || low == "~"
		if !needQuote {
			if _, err := strconv.ParseFloat(t, 64); err == nil {
				needQuote = true // 纯数字字符串必须加引号，否则类型丢失
			}
		}
		if needQuote {
			b, _ := json.Marshal(t)
			return string(b)
		}
		return t
	default:
		b, _ := json.Marshal(t)
		return string(b)
	}
}

// yamlSafeKey 键名含特殊字符时加引号
func yamlSafeKey(k string) string {
	if k == "" {
		return "\"\""
	}
	if strings.ContainsAny(k, ":#{}[]&*!|>'\"%@`,\n\t ") {
		b, _ := json.Marshal(k)
		return string(b)
	}
	return k
}
