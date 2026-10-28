// cmpricesite — DIY 硬件行情中心（Go 版，2026-09 重构）
// 子命令：
//   import : 读 /opt/cm-hwmarket/inbox/jd_prices_latest.json 与 data/*.json 快照 → MySQL（幂等）
//   serve  (默认): HTTP :39581，渲染「硬件行情中心」暗色仪表盘（参考凌清DIY行情中心形态）
//
// 重构说明：旧「跨店比价/飞书文档」需求已推倒。新形态：
//   · 首页：实时行情跑马灯 + 行情雷达(涨榜/跌榜/连续趋势) + 品类卡 + 分类行情表
//   · 详情页 /detail?k= ：五指标卡 + 价格走势图(30/90/全部)
package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"log"
	"math"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	_ "github.com/go-sql-driver/mysql"
)

const (
	base      = "/opt/cm-pricesite"
	dataDir   = base + "/data"
	assetDir  = base + "/site/assets"
	verFile   = dataDir + "/version"
	payload   = "/opt/cm-hwmarket/inbox/jd_prices_latest.json"
	listenDef = "0.0.0.0:39581"
	upC       = "#f0524a"  // 涨红
	downC     = "#3fb950"  // 跌绿
	flatC     = "#8b949e"  // 平
	accC      = "#58a6ff"  // 强调蓝
	maC7      = "#e3b341"  // MA7 黄
	maC30     = "#a371f7"  // MA30 紫
	lineC     = "#30363d"
	pnlBg     = "#0d1117"
	pnlBg2    = "#161b22"

	// windowMonths 数据窗口：图表 / 明细 / 指标一律只看最近 18 个月；
	// 库里同样按 18 个月清理（见 pruneHistory）。数据没累积到 18 个月就按实际渲染。
	windowMonths = 18
)

// windowCutoff 返回 18 个月窗口的起始 Unix 时间戳。
func windowCutoff() int64 { return time.Now().AddDate(0, -windowMonths, 0).Unix() }

// ---------------- 基础工具 ----------------

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func openDB() (*sql.DB, error) {
	dsnv := os.Getenv("CM_DB_DSN")
	if dsnv == "" {
		if b, err := os.ReadFile(base + "/db.conf"); err == nil {
			dsnv = strings.TrimSpace(string(b))
		}
	}
	if dsnv == "" {
		dsnv = "cmprice@unix(/tmp/mysql.sock)/cm_pricesite?charset=utf8mb4&timeout=5s"
	}
	return sql.Open("mysql", dsnv)
}

func fnum(v any) float64 {
	switch x := v.(type) {
	case float64:
		return x
	case int64:
		return float64(x)
	case []byte:
		f, _ := strconv.ParseFloat(string(x), 64)
		return f
	case string:
		f, _ := strconv.ParseFloat(x, 64)
		return f
	}
	return 0
}

func inum(v any) int64 {
	switch x := v.(type) {
	case int64:
		return x
	case float64:
		return int64(x)
	case []byte:
		i, _ := strconv.ParseInt(string(x), 10, 64)
		return i
	case string:
		i, _ := strconv.ParseInt(string(x), 10, 64)
		return i
	}
	return 0
}

func fs(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case []byte:
		return string(x)
	case nil:
		return ""
	}
	return fmt.Sprintf("%v", v)
}

func rs(v any) any {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	case []byte:
		return string(x)
	}
	return fmt.Sprintf("%v", v)
}
func iv(v any) any {
	if v == nil {
		return nil
	}
	return int(inum(v))
}
func ivn(v any) any {
	if v == nil {
		return nil
	}
	return int(inum(v))
}
func nv(v any) any {
	if v == nil {
		return nil
	}
	return fnum(v)
}

// 千分位。整数不带小数；有小数时最多保留 2 位（价格场景），
// 避免浮点乘法误差外泄（如 -433.9899999999998 这种）。
func tho(f float64) string {
	if f == math.Trunc(f) {
		s := strconv.FormatInt(int64(f), 10)
		neg := strings.HasPrefix(s, "-")
		s = strings.TrimPrefix(s, "-")
		var parts []string
		for len(s) > 3 {
			parts = append([]string{s[len(s)-3:]}, parts...)
			s = s[:len(s)-3]
		}
		parts = append([]string{s}, parts...)
		out := strings.Join(parts, ",")
		if neg {
			out = "-" + out
		}
		return out
	}
	// 非整数：四舍五入到 2 位。仅当小数部分恰好为 .00 时省略（6699.00→6699），
	// 否则保留 2 位（899.10 显示为 899.10，不外泄浮点误差）
	s := strconv.FormatFloat(f, 'f', 2, 64)
	s = strings.TrimSuffix(s, ".00")
	// 千分位（整数部分）
	neg := strings.HasPrefix(s, "-")
	s = strings.TrimPrefix(s, "-")
	intPart, frac := s, ""
	if i := strings.Index(s, "."); i >= 0 {
		intPart, frac = s[:i], s[i+1:]
	}
	var parts []string
	for len(intPart) > 3 {
		parts = append([]string{intPart[len(intPart)-3:]}, parts...)
		intPart = intPart[:len(intPart)-3]
	}
	parts = append([]string{intPart}, parts...)
	out := strings.Join(parts, ",")
	if frac != "" {
		out += "." + frac
	}
	if neg {
		out = "-" + out
	}
	return out
}

func money(f float64) string {
	return "¥" + tho(f)
}

// lastRoundAt 取数据库里最近一次采集轮次的时间戳（Unix 秒）。
// 所有页面的「数据更新」显示都来自这里，避免分散查 SQL。
func lastRoundAt(db *sql.DB) int64 {
	var ts int64
	if err := db.QueryRow(`SELECT COALESCE(MAX(ts), 0) FROM jd_rounds`).Scan(&ts); err != nil {
		return 0
	}
	return ts
}

// fmtStamp 时间戳 → "2006-01-02 15:04:05"（绝对时刻）
func fmtStamp(ts int64) string {
	if ts <= 0 {
		return "—"
	}
	return time.Unix(ts, 0).Local().Format("2006-01-02 15:04:05")
}

// humanAgo 时间戳 → 相对中文（如 "5 分钟前" / "2 小时前" / "3 天前"）。
// 用于让用户一眼判断数据新旧，不必心算差值。
func humanAgo(ts int64, now int64) string {
	if ts <= 0 {
		return ""
	}
	diff := now - ts
	if diff < 0 {
		diff = 0
	}
	switch {
	case diff < 60:
		return "刚刚"
	case diff < 3600:
		return fmt.Sprintf("%d 分钟前", diff/60)
	case diff < 86400:
		return fmt.Sprintf("%d 小时前", diff/3600)
	case diff < 86400*30:
		return fmt.Sprintf("%d 天前", diff/86400)
	default:
		return fmtStamp(ts)
	}
}

// dataBadge 通用「数据更新」徽章：绝对时间 + 相对时间，便于一眼判断新旧。
// empty=true 时返回空串（数据库里完全无数据）。
func dataBadge(lastTs, now int64) string {
	if lastTs <= 0 {
		return ""
	}
	ago := humanAgo(lastTs, now)
	if ago == "" || ago == fmtStamp(lastTs) {
		return fmt.Sprintf(`数据更新 <b>%s</b>`, fmtStamp(lastTs))
	}
	return fmt.Sprintf(`数据更新 <b>%s</b> <small>· %s</small>`, fmtStamp(lastTs), ago)
}

// ---------------- schema / import（与上一版一致） ----------------

func ensureSchema(db *sql.DB) error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS jd_rounds(
			module VARCHAR(64) NOT NULL, row_key VARCHAR(191) NOT NULL,
			cat VARCHAR(64), cat_label VARCHAR(64), brand VARCHAR(64), model VARCHAR(191),
			spec VARCHAR(255), sku VARCHAR(64), title VARCHAR(255),
			price DOUBLE NULL, shop VARCHAR(128), score INT NULL, reused TINYINT DEFAULT 0,
			source VARCHAR(16) NULL, platform VARCHAR(16) NULL, note VARCHAR(64) NULL,
			ts INT NOT NULL, UNIQUE KEY uk(module,row_key,ts)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS jd_latest(
			module VARCHAR(64) NOT NULL, row_key VARCHAR(191) NOT NULL,
			cat_label VARCHAR(64), brand VARCHAR(64), model VARCHAR(191),
			spec VARCHAR(255), sku VARCHAR(64), title VARCHAR(255),
			price DOUBLE NULL, shop VARCHAR(128), score INT NULL,
			source VARCHAR(16) NULL, platform VARCHAR(16) NULL, trend VARCHAR(8) NULL, prev_price DOUBLE NULL, ts INT,
			PRIMARY KEY(module,row_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS price_history(
			module VARCHAR(64) NOT NULL, row_key VARCHAR(191) NOT NULL,
			cat VARCHAR(64), ts INT, ref_price DOUBLE NULL, lo_price DOUBLE NULL,
			hi_price DOUBLE NULL, quotes INT NULL, source VARCHAR(64), sample VARCHAR(255),
			UNIQUE KEY uk(module,row_key,ts)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS sku_snapshot(
			cat VARCHAR(64) NOT NULL, cat_label VARCHAR(64), brand VARCHAR(64) NOT NULL,
			model VARCHAR(191) NOT NULL, plat VARCHAR(32) NOT NULL,
			price DOUBLE NULL, usual DOUBLE NULL, low DOUBLE NULL, d30 DOUBLE NULL,
			promo DOUBLE NULL, note VARCHAR(255), updated INT,
			UNIQUE KEY uk(cat,brand,model,plat)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS series(
			ym VARCHAR(16) NOT NULL, cat VARCHAR(64) NOT NULL, price DOUBLE NULL,
			UNIQUE KEY uk(ym,cat)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS jdcompare_sku(
			rec_id VARCHAR(64) NOT NULL, cat VARCHAR(64), cat_label VARCHAR(64), brand VARCHAR(64),
			model VARCHAR(191), spec VARCHAR(255), plat VARCHAR(32),
			price DOUBLE NULL, usual DOUBLE NULL, low DOUBLE NULL, d30 DOUBLE NULL, promo DOUBLE NULL,
			vs_usual DOUBLE NULL, vs_low DOUBLE NULL, price_at VARCHAR(64), price_ts INT, note VARCHAR(255),
			UNIQUE KEY uk(rec_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			return err
		}
	}
	return nil
}

// migrateColumns 给已存在的旧表补内部字段（来源/平台）。
// 这两列只用于入库留档与排错，页面一律不展示。
func migrateColumns(db *sql.DB) error {
	for _, t := range []string{"jd_rounds", "jd_latest"} {
		for _, c := range []struct{ name, ddl string }{
			{"source", "source VARCHAR(16) NULL"},
			{"platform", "platform VARCHAR(16) NULL"},
		} {
			var n int
			db.QueryRow(`SELECT COUNT(*) FROM information_schema.columns
				WHERE table_schema=DATABASE() AND table_name=? AND column_name=?`, t, c.name).Scan(&n)
			if n == 0 {
				if _, err := db.Exec("ALTER TABLE " + t + " ADD COLUMN " + c.ddl); err == nil {
					log.Printf("[migrate] %s.%s 已补列", t, c.name)
				}
			}
		}
	}
	return nil
}

// ---------------- 收录规范过滤 & 历史窗口 ----------------

// 明显不能作为行情样本的标题（占位/定金/运费/测试等）
var reBadRow = regexp.MustCompile(
	`测试|无效|下架|定金|订金|补差|补邮|差价|咨询|拍前|勿拍|专拍|运费|邮费|预售|占位|示例|test`)

func sval(v any) string {
	s, _ := rs(v).(string)
	return strings.TrimSpace(s)
}

// acceptRow 收录规范：不通过的样本一律不入库（宁缺毋滥）。
func acceptRow(r map[string]any) (bool, string) {
	price := fnum(r["price"])
	if price < 5 || price > 300000 {
		return false, "价格越界"
	}
	if sval(r["brand"]) == "" || sval(r["model"]) == "" {
		return false, "缺品牌/型号"
	}
	if sval(r["cat_label"]) == "" {
		return false, "缺品类"
	}
	title := sval(r["title"])
	if len([]rune(title)) < 4 {
		return false, "标题过短"
	}
	if reBadRow.MatchString(title) {
		return false, "标题含无效词"
	}
	return true, ""
}

// pruneHistory 18 个月窗口：
//   · 90 天以前：同一型号同一天只保留最后一条（抽稀，日线趋势不受影响）
//   · 18 个月以前：整条删除
func pruneHistory(db *sql.DB) {
	cut90 := time.Now().AddDate(0, 0, -90).Unix()
	cut18 := windowCutoff()
	if r, err := db.Exec(`DELETE r1 FROM jd_rounds r1 JOIN jd_rounds r2
		ON r1.module=r2.module AND r1.row_key=r2.row_key
		AND DATE(FROM_UNIXTIME(r1.ts))=DATE(FROM_UNIXTIME(r2.ts)) AND r1.ts<r2.ts
		WHERE r1.ts < ?`, cut90); err == nil {
		if n, _ := r.RowsAffected(); n > 0 {
			log.Printf("[prune] 抽稀 90 天前明细 %d 条", n)
		}
	}
	if r, err := db.Exec(`DELETE FROM jd_rounds WHERE ts < ?`, cut18); err == nil {
		if n, _ := r.RowsAffected(); n > 0 {
			log.Printf("[prune] 清理 18 个月前明细 %d 条", n)
		}
	}
	// 库侧同样只留 18 个月：历史快照表一并清理
	if r, err := db.Exec(`DELETE FROM price_history WHERE ts < ?`, cut18); err == nil {
		if n, _ := r.RowsAffected(); n > 0 {
			log.Printf("[prune] 清理 18 个月前历史快照 %d 条", n)
		}
	}
}

func insertIgnore(db *sql.DB, table string, cols []string, rows [][]any) error {
	if len(rows) == 0 {
		return nil
	}
	ph := "(" + strings.TrimSuffix(strings.Repeat("?,", len(cols)), ",") + ")"
	q := "INSERT IGNORE INTO " + table + " (" + strings.Join(cols, ",") + ") VALUES " + ph
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	st, err := tx.Prepare(q)
	if err != nil {
		tx.Rollback()
		return err
	}
	defer st.Close()
	for _, r := range rows {
		if _, err := st.Exec(r...); err != nil {
			tx.Rollback()
			return err
		}
	}
	return tx.Commit()
}

func insertReplace(db *sql.DB, table string, cols []string, rows [][]any) error {
	if len(rows) == 0 {
		return nil
	}
	ph := "(" + strings.TrimSuffix(strings.Repeat("?,", len(cols)), ",") + ")"
	q := "REPLACE INTO " + table + " (" + strings.Join(cols, ",") + ") VALUES " + ph
	tx, _ := db.Begin()
	st, err := tx.Prepare(q)
	if err != nil {
		tx.Rollback()
		return err
	}
	defer st.Close()
	for _, r := range rows {
		if _, err := st.Exec(r...); err != nil {
			tx.Rollback()
			return err
		}
	}
	return tx.Commit()
}

func jsonRows(name string) []map[string]any {
	b, err := os.ReadFile(dataDir + "/" + name + ".json")
	if err != nil {
		return nil
	}
	var out []map[string]any
	if json.Unmarshal(b, &out) != nil {
		return nil
	}
	return out
}

func tableCount(db *sql.DB, t string) int64 {
	var n int64
	db.QueryRow("SELECT COUNT(*) FROM " + t).Scan(&n)
	return n
}

func loadSnapshots(db *sql.DB) error {
	if tableCount(db, "price_history") == 0 {
		var rows [][]any
		for _, r := range jsonRows("cm_server_price_history") {
			rows = append(rows, []any{rs(r["module"]), rs(r["row_key"]), rs(r["cat"]), iv(r["ts"]),
				nv(r["ref_price"]), nv(r["lo_price"]), nv(r["hi_price"]), ivn(r["quotes"]),
				rs(r["source"]), rs(r["sample"])})
		}
		if err := insertIgnore(db, "price_history",
			[]string{"module", "row_key", "cat", "ts", "ref_price", "lo_price", "hi_price", "quotes", "source", "sample"}, rows); err != nil {
			return err
		}
		log.Printf("[migrate] price_history %d rows", len(rows))
	}
	if tableCount(db, "jdcompare_sku") == 0 {
		var rows [][]any
		for _, r := range jsonRows("cm_jdcompare_sku") {
			rows = append(rows, []any{rs(r["id"]), rs(r["cat"]), rs(r["cat_label"]), rs(r["brand"]),
				rs(r["model"]), rs(r["spec"]), rs(r["plat"]), nv(r["price"]), nv(r["usual"]),
				nv(r["low"]), nv(r["d30"]), nv(r["promo"]),
				nv(r["vs_usual"]), nv(r["vs_low"]), rs(r["price_at"]), ivn(r["price_ts"]), rs(r["note"])})
		}
		if err := insertReplace(db, "jdcompare_sku",
			[]string{"rec_id", "cat", "cat_label", "brand", "model", "spec", "plat", "price", "usual",
				"low", "d30", "promo", "vs_usual", "vs_low", "price_at", "price_ts", "note"}, rows); err != nil {
			return err
		}
		log.Printf("[migrate] jdcompare_sku %d rows", len(rows))
	}
	if tableCount(db, "sku_snapshot") == 0 {
		var rows [][]any
		for _, r := range jsonRows("cm_hwmarket_sku") {
			catLabel := r["cat_label"]
			if catLabel == nil {
				catLabel = r["cat"]
			}
			rows = append(rows, []any{rs(r["cat"]), rs(catLabel), rs(r["brand"]), rs(r["model"]),
				rs(r["plat"]), nv(r["price"]), nv(r["usual"]), nv(r["low"]), nv(r["d30"]),
				nv(r["promo"]), rs(r["note"]), ivn(r["updated"])})
		}
		if err := insertReplace(db, "sku_snapshot",
			[]string{"cat", "cat_label", "brand", "model", "plat", "price", "usual", "low", "d30", "promo", "note", "updated"}, rows); err != nil {
			return err
		}
		log.Printf("[migrate] sku_snapshot %d rows", len(rows))
	}
	if tableCount(db, "series") == 0 {
		var rows [][]any
		for _, r := range jsonRows("cm_hwmarket_series") {
			rows = append(rows, []any{rs(r["ym"]), rs(r["cat"]), nv(r["price"])})
		}
		if err := insertReplace(db, "series", []string{"ym", "cat", "price"}, rows); err != nil {
			return err
		}
		log.Printf("[migrate] series %d rows", len(rows))
	}
	if tableCount(db, "jd_rounds") == 0 {
		var rows [][]any
		for _, r := range jsonRows("jd_rounds") {
			rows = append(rows, []any{rs(r["module"]), rs(r["row_key"]), rs(r["cat"]), rs(r["cat_label"]),
				rs(r["brand"]), rs(r["model"]), rs(r["spec"]), rs(r["sku"]), rs(r["title"]),
				nv(r["price"]), rs(r["shop"]), ivn(r["score"]), ivn(r["reused"]), iv(r["ts"])})
		}
		if err := insertIgnore(db, "jd_rounds",
			[]string{"module", "row_key", "cat", "cat_label", "brand", "model", "spec", "sku", "title", "price", "shop", "score", "reused", "ts"}, rows); err != nil {
			return err
		}
		log.Printf("[migrate] jd_rounds %d rows", len(rows))
	}
	if tableCount(db, "jd_latest") == 0 {
		var rows [][]any
		for _, r := range jsonRows("jd_latest") {
			rows = append(rows, []any{rs(r["module"]), rs(r["row_key"]), rs(r["cat_label"]),
				rs(r["brand"]), rs(r["model"]), rs(r["spec"]), rs(r["sku"]), rs(r["title"]),
				nv(r["price"]), rs(r["shop"]), ivn(r["score"]), iv(r["ts"])})
		}
		if err := insertReplace(db, "jd_latest",
			[]string{"module", "row_key", "cat_label", "brand", "model", "spec", "sku", "title", "price", "shop", "score", "ts"}, rows); err != nil {
			return err
		}
		log.Printf("[migrate] jd_latest %d rows", len(rows))
	}
	return nil
}

func doImport() error {
	db, err := openDB()
	if err != nil {
		return err
	}
	defer db.Close()
	if err := ensureSchema(db); err != nil {
		return fmt.Errorf("schema: %w", err)
	}
	if err := migrateColumns(db); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	if err := loadSnapshots(db); err != nil {
		return fmt.Errorf("snapshots: %w", err)
	}
	n := 0
	if b, err := os.ReadFile(payload); err == nil {
		var p struct {
			GeneratedAt string                     `json:"generated_at"`
			Rows        map[string]map[string]any `json:"rows"`
		}
		if json.Unmarshal(b, &p) == nil && len(p.Rows) > 0 {
			ts := time.Now().Unix()
			if t, err := time.ParseInLocation("2006-01-02 15:04:05", p.GeneratedAt, time.Local); err == nil {
				ts = t.Unix()
			}
			var rounds [][]any
			var latest [][]any
			var hist [][]any
			dropped := 0
			for rk, r := range p.Rows {
				if ok, why := acceptRow(r); !ok {
					dropped++
					log.Printf("[filter] 剔除样本 %s：%s", rk[:8], why)
					continue
				}
				price := nv(r["price"])
				src, _ := rs(r["source"]).(string)
				plat, _ := rs(r["platform"]).(string)
				if src == "" {
					src = "jd"
				}
				if plat == "" {
					plat = "京东"
				}
				rounds = append(rounds, []any{rs(r["module"]), rk, rs(r["cat"]), rs(r["cat_label"]),
					rs(r["brand"]), rs(r["model"]), rs(r["spec"]), rs(r["sku"]), rs(r["title"]),
					price, rs(r["shop"]), ivn(r["score"]), ivn(r["reused"]), src, plat, int(ts)})
				if price != nil {
					latest = append(latest, []any{rs(r["module"]), rk, rs(r["cat_label"]),
						rs(r["brand"]), rs(r["model"]), rs(r["spec"]), rs(r["sku"]), rs(r["title"]),
						price, rs(r["shop"]), ivn(r["score"]), src, plat,
						rs(r["trend"]), nv(r["prev_price"]), int(ts)})
					// 同步落一条历史点：price_history 已有 uk(module,row_key,ts)，
					// 同一轮重复导入会被 IGNORE，天然幂等。趋势图/涨跌榜依赖这张表。
					hist = append(hist, []any{rs(r["module"]), rk, rs(r["cat_label"]), int(ts),
						price, price, price, ivn(r["score"]), src, rs(r["model"])})
				}
			}
			if err := insertIgnore(db, "jd_rounds",
				[]string{"module", "row_key", "cat", "cat_label", "brand", "model", "spec", "sku", "title", "price", "shop", "score", "reused", "source", "platform", "ts"}, rounds); err != nil {
				return err
			}
			if err := insertReplace(db, "jd_latest",
				[]string{"module", "row_key", "cat_label", "brand", "model", "spec", "sku", "title", "price", "shop", "score", "source", "platform", "trend", "prev_price", "ts"}, latest); err != nil {
				return err
			}
			if err := insertIgnore(db, "price_history",
				[]string{"module", "row_key", "cat", "ts", "ref_price", "lo_price", "hi_price", "quotes", "source", "sample"}, hist); err != nil {
				log.Printf("[import] price_history 写入失败（不阻断）: %v", err)
			} else {
				log.Printf("[import] price_history +%d 点", len(hist))
			}
			n = len(p.Rows)
			if dropped > 0 {
				log.Printf("[filter] 本轮剔除 %d 条不符合收录规范的样本", dropped)
			}
		}
	} else {
		log.Printf("[import] payload 不存在，仅迁移快照")
	}
	pruneHistory(db) // 18 个月窗口：90 天前抽稀为日线、18 个月前清除
	os.WriteFile(verFile, []byte(strconv.FormatInt(time.Now().UnixNano(), 10)), 0644)
	log.Printf("[import] payload_rows=%d", n)
	return nil
}

// ================= 行情中心数据聚合 =================

type pt struct {
	Ts    int64
	Price float64
	Shop  string
	Note  string // 每轮更新的备注（价格变动说明；不含来源信息）
}

type serie struct {
	Cat    string // 归一化品类（显卡/处理器/内存/固态硬盘/游戏主机）
	Brand  string
	Model  string
	Spec   string // 规格（内存=容量，如 16G×2 / 8G 单条；展示用）
	Points []pt
}

// normCat 把各种写法的品类统一成统一的中文名（行情已移除，此函数仅保留兼容）。
//
// ★ 口径必须与 hw_models.json 的 catLabel 一致：
//     显卡 / 处理器 / 固态硬盘 / 内存条 / 笔记本
//   历史上这里写的是「内存」（App 是「内存条」），且完全没有「笔记本」分支，
//   导致笔记本数据全被归到兜底分类、页面上根本不出现笔记本 tab，
//   内存也因为名字对不上而分组错乱。
func normCat(s string) string {
	switch {
	case s == "CPU" || strings.Contains(s, "处理器"):
		return "处理器"
	case s == "GPU" || strings.Contains(s, "显卡"):
		return "显卡"
	case s == "RAM" || strings.Contains(s, "内存"):
		return "内存条"
	case s == "SSD" || strings.Contains(s, "固态"):
		return "固态硬盘"
	case s == "NB" || strings.Contains(s, "笔记本"):
		return "笔记本"
	case s == "PC" || strings.Contains(s, "主机"):
		return "游戏主机"
	}
	return s
}

func skey(cat, brand, model string) string {
	return cat + "|" + brand + "|" + model
}

// specKey 规格归一化键：把「同型号不同规格」分开（如 4060 Ti 金属大师 16G vs 8G、
// SN850X 1TB vs 2TB），避免多个规格的价格混进同一条时序、走势横跳成实心块。
// 显卡只取显存（8G/16G/12G，忽略 GDDR 版本差异）；其他品类规格全等归一化（去空白小写）。
func specKey(cat, model, spec string) string {
	spec = strings.TrimSpace(spec)
	if spec == "" {
		return ""
	}
	if cat == "显卡" {
		if c := capOf(model, spec); c != "" {
			return "gpu|" + c
		}
	}
	return strings.ToLower(strings.Join(strings.Fields(spec), ""))
}

func (s *serie) Name() string {
	if s.Brand != "" {
		return s.Brand + " " + s.Model
	}
	return s.Model
}

// modelCap 型号名 + 显存：型号名里没有显存时从 spec 补上（req#21）。
func modelCap(s *serie) string {
	if s.Spec == "" || capRe.MatchString(s.Model) {
		return s.Model
	}
	if s.Cat == "显卡" {
		if g := capRe.FindString(s.Spec); g != "" {
			return s.Model + " " + strings.ToUpper(strings.Join(strings.Fields(g), ""))
		}
	}
	return s.Model
}

// NameSpec 名称 + 规格：内存带容量（16G×2），显卡型号名缺显存时从 spec 补上（req#21）。
func (s *serie) NameSpec() string {
	n := s.Name()
	if s.Spec == "" || s.Cat != "内存条" {
		return n + specTail(s)
	}
	return n + " · " + s.Spec
}

// specTail 显卡型号名缺显存时返回「 8G」这类后缀，其余情况返回空串。
func specTail(s *serie) string {
	if s.Cat != "显卡" || s.Spec == "" || capRe.MatchString(s.Model) {
		return ""
	}
	if g := capRe.FindString(s.Spec); g != "" {
		return " " + strings.ToUpper(strings.Join(strings.Fields(g), ""))
	}
	return ""
}

func loadSeries(db *sql.DB) (map[string]*serie, []string, error) {
	// 18 个月窗口在这里一刀切：所有页面（离散图 / 走势 / 明细 / 指标）都只拿窗口内的点
	rows, err := db.Query(`SELECT cat_label, brand, model, COALESCE(spec,''), price, ts, COALESCE(shop,''), COALESCE(source,'')
		FROM jd_rounds WHERE price IS NOT NULL AND ts >= ? ORDER BY ts ASC`, windowCutoff())
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	m := map[string]*serie{}
	var order []string
	for rows.Next() {
		var catL, brand, model, spec, shop, src string
		var price float64
		var ts int64
		if err := rows.Scan(&catL, &brand, &model, &spec, &price, &ts, &shop, &src); err != nil {
			return nil, nil, err
		}
		_ = src // 采集来源仅入库留档，页面不展示
		if price <= 0 {
			continue
		}
		cat := normCat(catL)
		k := skey(cat, brand, model)
		if sk := specKey(cat, model, spec); sk != "" {
			k += "|" + sk
		}
		s, ok := m[k]
		if !ok {
			s = &serie{Cat: cat, Brand: brand, Model: model}
			m[k] = s
			order = append(order, k)
		}
		if strings.TrimSpace(spec) != "" {
			s.Spec = strings.TrimSpace(spec) // 后出现的（更新的轮次）覆盖
		}
		s.Points = append(s.Points, pt{Ts: ts, Price: price, Shop: shop})
	}
	sort.Strings(order)
	return m, order, rows.Err()
}

type met struct {
	Cur          float64
	CurTs        int64
	Prev         float64 // 上一轮
	PrevTs       int64
	First        float64
	MinP, MaxP   float64
	MinTs, MaxTs int64
	Pct          [3]float64 // 1日/7日/30日 涨跌%，math.NaN()=无参考
	HasRef       [3]bool
	Total        float64 // 累计涨幅%（cur vs first）
	Pos          float64 // 历史价格位置 0..100；-1=数据不足
	Streak       int     // >0 连涨N天；<0 连跌N天；0=无
	Shops        int
	Samples      int
	Note         string // 最新一轮备注
}

var daySec = int64(86400)
var roundSec = int64(3600)    // 走势点粒度：按「采集轮次」聚合（1 小时桶，任何采集节奏都不丢点）
var scatterSec = int64(21600) // 离散图 X 轴粒度：与 6 小时采集节奏对齐（6×3600）

func calcMetric(s *serie, now int64) met {
	var m met
	pts := s.Points
	m.Samples = len(pts)
	m.Cur = pts[len(pts)-1].Price
	m.CurTs = pts[len(pts)-1].Ts
	m.First = pts[0].Price
	m.MinP, m.MaxP = m.Cur, m.Cur
	m.MinTs, m.MaxTs = pts[0].Ts, pts[0].Ts
	shops := map[string]bool{}
	for _, p := range pts {
		if p.Price < m.MinP {
			m.MinP, m.MinTs = p.Price, p.Ts
		}
		if p.Price > m.MaxP {
			m.MaxP, m.MaxTs = p.Price, p.Ts
		}
		sh := strings.TrimSpace(p.Shop)
		if strings.Contains(sh, "京东") {
			sh = "京东"
		}
		if sh != "" {
			shops[sh] = true
		}
	}
	m.Shops = len(shops)
	m.Note = pts[len(pts)-1].Note
	if len(pts) > 1 {
		m.Prev, m.PrevTs = pts[len(pts)-2].Price, pts[len(pts)-2].Ts
	}
	// 累计涨幅
	if m.First > 0 && len(pts) > 1 {
		m.Total = (m.Cur - m.First) / m.First * 100
	}
	// N日涨跌：找 cutoff 前最后一点；窗口内无点则退化为首点
	windows := [3]int64{1, 7, 30}
	for i, d := range windows {
		cutoff := now - d*daySec
		var ref float64
		found := false
		for _, p := range pts {
			if p.Ts <= cutoff {
				ref = p.Price
				found = true
			} else {
				break
			}
		}
		if !found {
			ref = m.First // 序列整个落在窗口内 → 以首价为参考
			found = len(pts) > 1
		}
		if found && ref > 0 {
			m.HasRef[i] = true
			m.Pct[i] = (m.Cur - ref) / ref * 100
		} else {
			m.Pct[i] = math.NaN()
			m.HasRef[i] = false
		}
	}
	// 价格位置
	if m.MaxP-m.MinP >= 1 {
		m.Pos = (m.Cur - m.MinP) / (m.MaxP - m.MinP) * 100
	} else {
		m.Pos = -1
	}
	// 连涨/连跌（按自然日）
	if len(pts) > 1 {
		dayLast := map[string]float64{}
		var days []string
		for _, p := range pts {
			d := time.Unix(p.Ts, 0).Format("01-02")
			if _, ok := dayLast[d]; !ok {
				days = append(days, d)
			}
			dayLast[d] = p.Price
		}
		sort.Strings(days)
		if len(days) >= 2 {
			streak := 0
			dir := 0
			for i := 1; i < len(days); i++ {
				d := dayLast[days[i]] - dayLast[days[i-1]]
				sd := 0
				if d > 0.5 {
					sd = 1
				} else if d < -0.5 {
					sd = -1
				}
				if sd == 0 {
					streak, dir = 0, 0
					continue
				}
				if sd == dir {
					streak += sd
				} else {
					streak, dir = sd, sd
				}
			}
			m.Streak = streak
		}
	}
	return m
}

func pctCell(v float64, has bool) string {
	if !has || math.IsNaN(v) {
		return `<span class="flat">—</span>`
	}
	if math.Abs(v) < 0.005 {
		return `<span class="flat">+0.0%</span>`
	}
	c, a := upC, "+"
	if v < 0 {
		c, a = downC, ""
	}
	return fmt.Sprintf(`<span class="pc" style="color:%s">%s%.2f%%</span>`, c, a, v)
}

// sparkSVG：30 天内走势 SVG —— 只画离散点，不画折线（req#15 全站统一离散图形态）
func sparkSVG(s *serie) string {
	now := time.Now().Unix()
	var pts []pt
	for _, p := range s.Points {
		if now-p.Ts <= 30*daySec {
			pts = append(pts, p)
		}
	}
	if len(pts) == 0 {
		return `<span class="flat">积累中</span>`
	}
	lo, hi := pts[0].Price, pts[0].Price
	for _, p := range pts {
		lo = math.Min(lo, p.Price)
		hi = math.Max(hi, p.Price)
	}
	w, h := 120.0, 26.0
	pad := 3.0
	t0, t1 := pts[0].Ts, pts[len(pts)-1].Ts
	if t1 == t0 {
		t1 = t0 + 1
	}
	if hi-lo < 1 {
		hi = lo + 1
	}
	var sb strings.Builder
	sb.WriteString(`<svg class="spark" viewBox="0 0 120 26" width="120" height="26">`)
	for i, p := range pts {
		x := pad + (float64(p.Ts)-float64(t0))/(float64(t1)-float64(t0))*(w-2*pad)
		y := h - pad - (p.Price-lo)/(hi-lo)*(h-2*pad)
		c := flatC
		if i > 0 {
			if p.Price > pts[i-1].Price {
				c = upC
			} else if p.Price < pts[i-1].Price {
				c = downC
			}
		}
		r := 1.6
		if i == len(pts)-1 {
			r = 2.5
		}
		sb.WriteString(fmt.Sprintf(`<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>`, x, y, r, c))
	}
	sb.WriteString(`</svg>`)
	return sb.String()
}

func posLabel(m met) string {
	switch {
	case m.Pos < 0:
		return `<span class="flat">积累中</span>`
	case m.Pos < 35:
		return fmt.Sprintf(`<span style="color:%s">%.0f%%<br><small>低位</small></span>`, downC, m.Pos)
	case m.Pos < 75:
		return fmt.Sprintf(`<span style="color:%s">%.0f%%<br><small>正常</small></span>`, flatC, m.Pos)
	default:
		return fmt.Sprintf(`<span style="color:%s">%.0f%%<br><small>高位</small></span>`, upC, m.Pos)
	}
}

func streakLabel(m met) string {
	switch {
	case m.Streak > 0:
		return fmt.Sprintf(`<span style="color:%s">连涨%d天</span>`, upC, m.Streak)
	case m.Streak < 0:
		return fmt.Sprintf(`<span style="color:%s">连跌%d天</span>`, downC, -m.Streak)
	default:
		return fmt.Sprintf(`<span class="flat">积累中 <small>·%s起</small></span>`,
			time.Unix(m.CurTs, 0).Format("01-02"))
	}
}

type rowView struct {
	S       *serie
	M       met
	TotalH  string // 累计涨幅（ticker/雷达用）
	DetailK string
}

// ---------------- 行情矩阵（比价表）辅助 ----------------

// TSE：3DMark Time Spy Extreme 显卡跑分（参考值，来自公开测试中位）。
// 仅显卡品类展示；命中按「长型号优先」，避免 5060 吃掉 5060 Ti。
var tseScores = []struct {
	key string
	val int
}{
	{"RTX 5090D V2", 25042}, {"RTX 5090", 25485}, {"RTX 5090D", 25485},
	{"RTX 5080", 16066}, {"RTX 5070 TI", 13485}, {"RTX 5070", 10096},
	{"RTX 5060 TI 16G", 7136}, {"RTX 5060 TI 8G", 7136}, {"RTX 5060 TI", 7136},
	{"RTX 5060", 6529}, {"RTX 5050", 4607},
	{"RTX 4090", 19100}, {"RTX 4080", 14500}, {"RTX 4070 TI", 12300},
	{"RTX 4070", 10600}, {"RTX 4060 TI", 6900}, {"RTX 4060", 5750},
	{"RTX 3090", 13800}, {"RTX 3080", 10300}, {"RTX 3070", 7900},
	{"RTX 3060", 6100}, {"RTX 3050", 4300}, {"RTX 2060", 4400},
	{"RX 9070 XT", 14120}, {"RX 9070", 12860}, {"RX 9070 GRE", 10686},
	{"RX 9060 XT 16G", 7489}, {"RX 9060 XT 8G", 7234}, {"RX 9060 XT", 7350},
	{"RX 7900 XTX", 14200}, {"RX 7900 XT", 12200}, {"RX 7800 XT", 9800},
	{"RX 7700 XT", 8600}, {"RX 7600", 6900},
	{"RX 6800 XT", 9700}, {"RX 6750 GRE", 8000}, {"RX 6650 XT", 6800},
	{"RX 6600", 5700},
	{"ARC B580", 7113}, {"ARC B570", 6001}, {"ARC A770", 6400}, {"ARC A750", 5900},
}

// tseOf 查型号的 TSE 跑分；未命中返回 0（页面显示 —）。
func tseOf(cat, model, spec string) int {
	if cat != "显卡" {
		return 0
	}
	t := strings.ToUpper(model)
	specU := strings.ToUpper(spec)
	if strings.Contains(specU, "16G") && !strings.Contains(t, "16G") {
		t += " 16G"
	} else if strings.Contains(specU, "8G") && !strings.Contains(t, "8G") {
		t += " 8G"
	}
	t = strings.ReplaceAll(t, " TI", " TI")
	for _, e := range tseScores {
		if strings.Contains(t, e.key) {
			return e.val
		}
	}
	return 0
}

// platOf 店铺/渠道名 → 平台桶（京东 / 淘宝 / 拼多多 / 其他）。
func platOf(shop string) string {
	s := strings.TrimSpace(shop)
	switch {
	case s == "":
		return ""
	case strings.Contains(s, "京东"):
		return "京东"
	case strings.Contains(s, "淘宝"), strings.Contains(s, "天猫"):
		return "淘宝"
	case strings.Contains(s, "拼多多"):
		return "拼多多"
	// req#21：新增短视频电商来源，平台名一律做匿名化映射（不出现「抖音/快手」字样）
	case strings.Contains(s, "抖音"), strings.Contains(s, "快手"):
		return "短视频电商"
	}
	return "其他"
}

// platDisp 平台对外展示名（req#21 匿名化：不暴露具体平台与采集方式，
// 统一用「某东自营 / 某宝 / 某多多 / 短视频电商 / 其他渠道」）。
func platDisp(p string) string {
	switch p {
	case "京东":
		return "某东自营"
	case "淘宝":
		return "某宝"
	case "拼多多":
		return "某多多"
	case "短视频电商":
		return "短视频电商"
	case "其他":
		return "其他渠道"
	}
	return "其他渠道"
}

// platCell 单平台「最近现价 + 上一次价格」。
type platCell struct {
	Cur, Prev float64
	Shop      string // 该平台最近一次的店铺
}

// platBreakdown 遍历序列，按平台桶保留最近两笔价格。
func platBreakdown(s *serie) map[string]*platCell {
	cells := map[string]*platCell{}
	for _, p := range s.Points {
		pl := platOf(p.Shop)
		if pl == "" || pl == "其他" {
			continue
		}
		c, ok := cells[pl]
		if !ok {
			cells[pl] = &platCell{Cur: p.Price, Shop: p.Shop}
			continue
		}
		c.Prev = c.Cur
		c.Cur = p.Price
		c.Shop = p.Shop
	}
	return cells
}

// dayLow 最近 24h 最低价；窗口内无点返回 0。
func dayLow(s *serie, now int64) float64 {
	lo := 0.0
	for _, p := range s.Points {
		if p.Ts < now-86400 {
			continue
		}
		if lo == 0 || p.Price < lo {
			lo = p.Price
		}
	}
	return lo
}

var platOrder = []string{"京东", "淘宝", "拼多多"}

// ---------------- 平台主源冗余路由（req#22） ----------------
// 慢慢买与京东为授权合作关系 → 京东作第一主源。
// 若京东被风控拦截（最近 2 个采集轮次零命中）→ 自动降级到淘宝/拼多多中命中更高者（二选一）；
// 京东恢复出数后自动归还第一排序。判定无状态、实时计算，不需要人工干预或重启。

// platHits 最近 n 个采集轮次里各平台命中的型号数。
func platHits(db *sql.DB, rounds int) map[string]int {
	out := map[string]int{}
	if rounds < 1 {
		rounds = 2
	}
	rows, err := db.Query(`
		SELECT platform, COUNT(DISTINCT model) c
		FROM jd_rounds
		WHERE ts >= (
			SELECT MIN(ts) FROM (
				SELECT DISTINCT ts FROM jd_rounds ORDER BY ts DESC LIMIT ?
			) t
		) AND platform IS NOT NULL AND platform <> ''
		GROUP BY platform`, rounds)
	if err != nil {
		log.Printf("platHits: %v", err)
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var p string
		var c int
		if err := rows.Scan(&p, &c); err == nil {
			out[p] = c
		}
	}
	return out
}

// platRoute 返回按优先级排好的平台列表、当前主源、是否处于降级状态。
func platRoute(db *sql.DB) (order []string, primary string, degraded bool) {
	h := platHits(db, 2)
	jd, tb, pdd := h["京东"], h["淘宝"], h["拼多多"]
	if jd > 0 {
		return []string{"京东", "淘宝", "拼多多"}, "京东", false
	}
	// 京东被拦：淘宝 / 拼多多 二选一，取近期命中型号更多者
	if tb >= pdd {
		return []string{"淘宝", "拼多多", "京东"}, "淘宝", true
	}
	return []string{"拼多多", "淘宝", "京东"}, "拼多多", true
}

// srcBadge 主源徽章（req#22）：正常显示平台名，降级时显示「已降级」警示色。
func srcBadge(primary string, degraded bool) string {
	name := platDisp(primary)
	if !degraded {
		return fmt.Sprintf(`<i class="src-ok"></i>%s`, name)
	}
	return fmt.Sprintf(`<i class="src-warn"></i>%s<span class="src-tip">（京东被拦，已自动降级）</span>`, name)
}

// priceInt 价格取整显示（¥ + 千分位，不带小数）。无效值返回 —。
func priceInt(f float64) string {
	if f <= 0 || math.IsNaN(f) || math.IsInf(f, 0) {
		return "—"
	}
	return "¥" + tho(math.Round(f))
}

// deltaChip 涨跌小标签：涨红跌绿（中国习惯）；无参照值时返回 —。
func deltaChip(cur, prev float64) string {
	if cur <= 0 || prev <= 0 || math.IsNaN(cur) || math.IsNaN(prev) ||
		math.IsInf(cur, 0) || math.IsInf(prev, 0) {
		return `<span style="color:` + flatC + `">—</span>`
	}
	d := cur - prev
	if math.Abs(d) < 0.5 {
		return `<span style="color:` + flatC + `">0</span>`
	}
	if d > 0 {
		return fmt.Sprintf(`<span style="color:%s">+%.0f</span>`, upC, math.Round(d))
	}
	return fmt.Sprintf(`<span style="color:%s">%.0f</span>`, downC, math.Round(d))
}

// heatStyle 蓝色渐变底色（值越大越蓝），用于 TSE 跑分 / 性价比列的色阶。
func heatStyle(v, min, max float64) string {
	if math.IsNaN(v) || math.IsNaN(min) || math.IsNaN(max) || max-min < 1e-9 {
		return `background:rgba(56,139,253,.12)`
	}
	t := (v - min) / (max - min)
	if t < 0 {
		t = 0
	}
	if t > 1 {
		t = 1
	}
	return fmt.Sprintf(`background:rgba(56,139,253,%.2f)`, 0.10+0.45*t)
}

// trendPerPage 走势小多联每页卡片数（req#18：页面不再一次铺满，超过则分页）
const trendPerPage = 24

// ---------------- 价格走势小多联图（req#16：白底卡片 + 品牌色阶梯线） ----------------

// vendorColor 按品牌/系列给走势线着色（参考样式：NVIDIA 橄榄绿 / AMD 红 / Intel 蓝 / 其他石板灰）。
// 注意顺序：RTX/GTX 必须先于 RX 判断（"RTX" 包含 "RX"）。
func vendorColor(cat, model, brand string) string {
	s := strings.ToUpper(model + " " + brand)
	switch {
	case strings.Contains(s, "RTX"), strings.Contains(s, "GTX"),
		strings.Contains(s, "NVIDIA"), strings.Contains(s, "GEFORCE"):
		return "#6b8e23"
	case strings.Contains(s, "ARC"), strings.Contains(s, "INTEL"),
		strings.Contains(s, "ULTRA"), strings.Contains(s, "CORE"):
		return "#2563eb"
	case strings.Contains(s, "RX"), strings.Contains(s, "RADEON"),
		strings.Contains(s, "AMD"), strings.Contains(s, "锐龙"), strings.Contains(s, "RYZEN"):
		return "#c0392b"
	}
	return "#475569"
}

// trendPanelSVG 单型号白底走势小图：浅灰网格 + 品牌色阶梯线 + 标题「型号（史低：X元）」。
// 数据不足时显示「积累中」。X 轴 = 数据首末时间，Y 轴 = 价格（上下各留 8% 余量）。
func trendPanelSVG(s *serie, minP float64) string {
	const (
		w, h           = 300.0, 178.0
		ml, mr, mt, mb = 44.0, 10.0, 24.0, 18.0
	)
	col := vendorColor(s.Cat, s.Model, s.Brand)
	// req#21：型号名缺显存时从 spec 补（「RTX 5060 Ti 黑金」→「…黑金 8G」），
	// 否则与同核心的 16G 版本看起来像同一张卡出现了两次。
	title := html.EscapeString(modelCap(s))
	if minP > 0 {
		title += fmt.Sprintf(`（史低：%s元）`, tho(minP))
	}
	hdr := fmt.Sprintf(`<text x="8" y="15" font-size="12" font-weight="600" fill="%s">%s</text>`, col, title)
	pw, ph := w-ml-mr, h-mt-mb
	pts := make([]pt, 0, len(s.Points))
	for _, p := range s.Points {
		if p.Price > 0 {
			pts = append(pts, p)
		}
	}
	var bd strings.Builder
	if len(pts) == 0 {
		bd.WriteString(fmt.Sprintf(`<svg viewBox="0 0 %v %v" xmlns="http://www.w3.org/2000/svg">%s`+
			`<text x="%v" y="%v" font-size="11" fill="#8b949e" text-anchor="middle">积累中 · 尚无采集点</text></svg>`,
			w, h, hdr, ml+pw/2, mt+ph/2))
		return bd.String()
	}
	x0, x1 := pts[0].Ts, pts[len(pts)-1].Ts
	if x1 <= x0 {
		x1 = x0 + 3600
	}
	lo, hi := pts[0].Price, pts[0].Price
	for _, p := range pts {
		if p.Price < lo {
			lo = p.Price
		}
		if p.Price > hi {
			hi = p.Price
		}
	}
	if hi-lo < 1 {
		pad := hi*0.05 + 1
		lo, hi = lo-pad, hi+pad
	} else {
		pad := (hi - lo) * 0.08
		lo, hi = lo-pad, hi+pad
	}
	xf := func(ts int64) float64 { return ml + float64(ts-x0)/float64(x1-x0)*pw }
	yf := func(v float64) float64 { return mt + (1-(v-lo)/(hi-lo))*ph }
	// 横向网格 + Y 刻度（5 条）
	var grid, ylab strings.Builder
	for i := 0; i < 5; i++ {
		v := lo + (hi-lo)*float64(i)/4
		y := yf(v)
		grid.WriteString(fmt.Sprintf(`<line x1="%v" y1="%v" x2="%v" y2="%v" stroke="#21262d"/>`, ml, y, w-mr, y))
		ylab.WriteString(fmt.Sprintf(`<text x="%v" y="%v" font-size="9.5" fill="#8b949e" text-anchor="end">%s</text>`,
			ml-4, y+3, tho(math.Round(v))))
	}
	// 纵向网格 + X 时间刻度（4 个）；数据跨度 <4 天时带时分，避免日期刻度重复
	var xlab strings.Builder
	fmtX := "2006/1/2"
	if x1-x0 < 4*86400 {
		fmtX = "1/2 15:04"
	}
	for i := 0; i < 4; i++ {
		ts := x0 + (x1-x0)*int64(i)/3
		x := xf(ts)
		anchor := "middle"
		if i == 0 {
			anchor = "start"
		} else if i == 3 {
			anchor = "end"
		}
		grid.WriteString(fmt.Sprintf(`<line x1="%v" y1="%v" x2="%v" y2="%v" stroke="#21262d"/>`, x, mt, x, h-mb))
		xlab.WriteString(fmt.Sprintf(`<text x="%v" y="%v" font-size="9.5" fill="#8b949e" text-anchor="%s">%s</text>`,
			x, h-4, anchor, time.Unix(ts, 0).Format(fmtX)))
	}
	// 阶梯线（step-after：先横后竖）
	var path strings.Builder
	path.WriteString(fmt.Sprintf(`M%v %v`, xf(pts[0].Ts), yf(pts[0].Price)))
	for i := 1; i < len(pts); i++ {
		path.WriteString(fmt.Sprintf(`H%vV%v`, xf(pts[i].Ts), yf(pts[i].Price)))
	}
	last := pts[len(pts)-1]
	bd.WriteString(fmt.Sprintf(
		`<svg viewBox="0 0 %v %v" xmlns="http://www.w3.org/2000/svg">%s`+
			`<g>%s</g><g>%s</g><g>%s</g>`+
			`<path d="%s" fill="none" stroke="%s" stroke-width="1.5" stroke-opacity=".92"/>`+
			`<circle cx="%v" cy="%v" r="2.2" fill="%s"/></svg>`,
		w, h, hdr, grid.String(), ylab.String(), xlab.String(),
		path.String(), col, xf(last.Ts), yf(last.Price), col))
	return bd.String()
}

func buildRowViews(db *sql.DB) (map[string]*rowView, []string, error) {
	sm, order, err := loadSeries(db)
	if err != nil {
		return nil, nil, err
	}
	now := time.Now().Unix()
	out := map[string]*rowView{}
	for k, s := range sm {
		m := calcMetric(s, now)
		out[k] = &rowView{S: s, M: m, DetailK: url.QueryEscape(k)}
	}
	return out, order, nil
}

// ---------------- 同型号去重 + 品牌权重（req#21：一张卡只出现一次） ----------------

// 板卡厂权重（用户指定：华硕 > 微星 > 七彩虹 > 技嘉 > 铭瑄，左高右低）。
// 未列出的品牌统一 50，低于用户指定的五个；同权重时用数据点数多者兜底。
var brandRank = map[string]int{
	"华硕": 100, "微星": 90, "七彩虹": 80, "技嘉": 70, "铭瑄": 60,
}

func brandWeight(b string) int {
	if w, ok := brandRank[b]; ok {
		return w
	}
	bu := strings.ToUpper(strings.TrimSpace(b))
	for k, v := range brandRank {
		if strings.EqualFold(k, bu) {
			return v
		}
	}
	return 50
}

var coreRe = regexp.MustCompile(`(?i)(RTX|GTX|RX|ARC)\s*(\d{3,4})\s*(TI)?\s*(SUPER|XT)?`)
var capRe = regexp.MustCompile(`(?i)(\d{1,2})\s*G\b`)

// capOf 取显存/容量（8G/16G/12G…），优先 model 其次 spec，取不到返回 ""。
func capOf(model, spec string) string {
	if g := capRe.FindStringSubmatch(model); g != nil {
		return g[1] + "G"
	}
	if g := capRe.FindStringSubmatch(spec); g != nil {
		return g[1] + "G"
	}
	return ""
}

// modelCore 显卡专用：从「品牌 + 花名 + 系列词」里抽出核心型号。
// 例：RTX 5060 8G 幻影师 2X OC / iGame RTX 5080 Ultra W / RTX 4070 Ti SUPER 金属大师
//   → RTX 5060 / RTX 5080 / RTX 4070 Ti SUPER
func modelCore(brand, model string) string {
	m := strings.TrimSpace(model)
	if brand != "" {
		m = strings.TrimSpace(strings.TrimPrefix(m, brand))
	}
	if g := coreRe.FindStringSubmatch(m); g != nil {
		core := strings.ToUpper(g[1]) + " " + g[2]
		if g[3] != "" {
			core += " Ti"
		}
		if g[4] != "" {
			core += " " + strings.ToUpper(g[4])
		}
		return core
	}
	return strings.ToLower(strings.Join(strings.Fields(m), ""))
}

// dedupKey 去重分组键。
// 显卡 = 核心型号 + 显存（避免 8G/16G 被误并）；
// 其他品类 = 品牌+型号+规格 全等（保守，只合并写法完全一致的重复条目）。
func dedupKey(s *serie) string {
	if s.Cat == "显卡" {
		return "GPU|" + modelCore(s.Brand, s.Model) + "|" + capOf(s.Model, s.Spec)
	}
	key := strings.ToLower(strings.Join(strings.Fields(s.Brand+s.Model+s.Spec), ""))
	return s.Cat + "|" + key
}

// pickScore 代表得分（req#21）：
//  ① 品牌权重优先（×1000 拉开档次）
//  ② 其次「型号名自带容量」——保证页面显示的是「990 PRO 1TB」而不是缺容量的「990 PRO」
//  ③ 最后比数据点数量，取采集更连续的那条
func pickScore(v *rowView) int {
	s := brandWeight(v.S.Brand)*1000 + len(v.S.Points)
	if capRe.MatchString(v.S.Model) {
		s += 100
	}
	return s
}

// dedupViews 同型号只保留一个代表，解决「一张卡出现第二次」。
// 组内按品牌权重选：华硕 > 微星 > 七彩虹 > 技嘉 > 铭瑄 > 其他，同权重时点数多者胜。
func dedupViews(views map[string]*rowView, order []string) (map[string]*rowView, []string) {
	// 阶段一：统计每个核心型号出现过的显存规格。
	// 若某核心型号只有一种显存（如 RTX 5070 Ti 清一色 16G），则未标显存的条目
	// 归入该规格，避免「影驰 RTX 5070 Ti 金属大师星曜」与「耕升 RTX 5070 Ti 踏雪」被当成两张卡。
	capByCore := map[string]map[string]int{}
	for _, k := range order {
		s := views[k].S
		if s.Cat != "显卡" {
			continue
		}
		if cp := capOf(s.Model, s.Spec); cp != "" {
			core := modelCore(s.Brand, s.Model)
			if capByCore[core] == nil {
				capByCore[core] = map[string]int{}
			}
			capByCore[core][cp]++
		}
	}
	key := func(s *serie) string {
		if s.Cat == "显卡" {
			core := modelCore(s.Brand, s.Model)
			cp := capOf(s.Model, s.Spec)
			if cp == "" && len(capByCore[core]) == 1 {
				for c := range capByCore[core] {
					cp = c
				}
			}
			return "GPU|" + core + "|" + cp
		}
		return s.Cat + "|" + strings.ToLower(strings.Join(strings.Fields(s.Brand+s.Model+s.Spec), ""))
	}
	best := map[string]string{}
	score := map[string]int{}
	for _, k := range order {
		v, ok := views[k]
		if !ok {
			continue
		}
		dk := key(v.S)
		sc := pickScore(v)
		if cur, ok := best[dk]; !ok || sc > score[dk] || (sc == score[dk] && k < cur) {
			best[dk], score[dk] = k, sc
		}
	}
	out := map[string]*rowView{}
	no := make([]string, 0, len(best))
	for _, k := range order {
		v, ok := views[k]
		if !ok || best[key(v.S)] != k {
			continue
		}
		out[k] = v
		no = append(no, k)
	}
	return out, no
}

// ---------------- 首页 ----------------

func pageCSS() string { return cssMain }

const cssMain = `
*{box-sizing:border-box;margin:0;padding:0}
body{background:` + pnlBg + `;color:#e6e8eb;
  font:14px/1.6 -apple-system,"PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif}
a{color:inherit;text-decoration:none}
.wrap{max-width:1240px;margin:0 auto;padding:0 16px 60px}
/* header */
.hd{display:flex;align-items:center;gap:14px;padding:20px 0 14px;flex-wrap:wrap}
.sbox{display:flex;align-items:center;gap:8px}
.sbox input{width:220px;padding:7px 10px;background:#0d1117;border:1px solid #30363d;border-radius:6px;color:#e6edf3;font-size:13px}
.sbox button{padding:7px 14px;background:#1f6feb;color:#fff;border:none;border-radius:6px;cursor:pointer;font-size:13px}
.sbox #sres{font-size:13px;color:#3fb950;max-width:420px}
.hd h1{font-size:22px;font-weight:700;letter-spacing:.5px}
.live{display:inline-flex;align-items:center;gap:5px;font-size:12px;color:` + downC + `;
  background:rgba(63,185,80,.12);border:1px solid rgba(63,185,80,.4);border-radius:20px;
  padding:2px 10px;font-weight:600}
.live i{width:7px;height:7px;border-radius:50%;background:` + downC + `;animation:blink 1.6s infinite}
@keyframes blink{0%,100%{opacity:1}50%{opacity:.25}}
.hd .upd{margin-left:auto;color:#8b949e;font-size:12.5px}
.navlink{color:` + accC + `;font-size:13px;border:1px solid rgba(88,166,255,.35);border-radius:6px;
  padding:4px 12px;background:rgba(88,166,255,.08)}
.navlink:hover{background:rgba(88,166,255,.18)}
/* ticker */
.ticker{overflow:hidden;border:1px solid ` + lineC + `;border-radius:8px;background:` + pnlBg2 + `;
  white-space:nowrap;position:relative}
.tk-move{display:inline-block;padding:9px 0;animation:tk 90s linear infinite}
.ticker:hover .tk-move{animation-play-state:paused}
@keyframes tk{0%{transform:translateX(0)}100%{transform:translateX(-50%)}}
.tk-item{display:inline-block;margin:0 26px;font-size:13px}
.tk-item .nm{color:#c9d1d9;font-weight:600}
.tk-item .pr{color:#e6e8eb;font-weight:700;font-family:Consolas,monospace}
.tk-item .pc{font-family:Consolas,monospace;font-size:12px}
/* radar */
.radar{display:flex;align-items:center;gap:18px;flex-wrap:wrap;margin:12px 0;
  border:1px solid ` + lineC + `;border-radius:8px;background:` + pnlBg2 + `;padding:9px 14px;font-size:12.5px}
.radar .tab1{color:` + accC + `;font-weight:700;border:1px solid rgba(88,166,255,.4);
  border-radius:5px;padding:2px 10px;background:rgba(88,166,255,.1)}
.radar .grp b{color:#8b949e;font-weight:500;margin-right:6px}
.radar .itm{margin-right:14px}
.radar .rank{color:#f0883e;font-weight:700;font-family:Consolas,monospace}
.radar .pc{font-family:Consolas,monospace}
.radar .upd2{margin-left:auto;color:#8b949e}
/* cards */
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin:14px 0}
.card{border:1px solid ` + lineC + `;border-radius:10px;background:` + pnlBg2 + `;padding:16px 18px;
  transition:border-color .2s}
.card:hover{border-color:#3d4653}
.card .t{display:flex;justify-content:space-between;color:#c9d1d9;font-size:14px;font-weight:600}
.card .t small{color:#8b949e;font-weight:400}
.card .big{font-size:26px;font-weight:800;font-family:Consolas,monospace;margin:8px 0 6px}
.card .sub{color:#8b949e;font-size:12px;line-height:1.8}
.card .sub b{font-weight:600}
/* tables */
.cat-hd{display:flex;align-items:baseline;gap:12px;margin:30px 0 4px}
.cat-hd h2{font-size:18px;font-weight:700}
.cat-hd .note{color:#8b949e;font-size:12px;margin-left:auto}
.twrap{overflow-x:auto;border:1px solid ` + lineC + `;border-radius:10px;background:` + pnlBg2 + `;margin-top:8px}
table{width:100%;border-collapse:collapse;font-size:13px;min-width:920px}
th{color:#8b949e;font-weight:500;text-align:left;padding:10px 12px;border-bottom:1px solid ` + lineC + `;
  white-space:nowrap;font-size:12px}
td{padding:10px 12px;border-bottom:1px solid #1c2128;white-space:nowrap;vertical-align:middle}
tr:last-child td{border-bottom:none}
tbody tr{transition:background .15s}
tbody tr:hover{background:#1a2230}
td .mdl{font-weight:700;color:#e6e8eb}
td .mdl small{display:block;color:#8b949e;font-weight:400;font-size:11.5px}
td .pr{font-weight:700;font-family:Consolas,monospace;font-size:14px}
td .pr small{color:#8b949e;font-weight:400;font-family:inherit;font-size:11.5px;margin-left:4px}
.pc,.flat{font-family:Consolas,monospace}
.flat{color:#8b949e}
td .pos{text-align:center;display:inline-block;line-height:1.3}
td small{color:#8b949e}
.badge{display:inline-block;font-size:11px;color:#c9d1d9;background:#21262d;border:1px solid #30363d;
  border-radius:4px;padding:1px 7px;margin-right:4px}
.spark{display:block}
/* 行情矩阵（多平台比价表） */
.sheet-hd{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap;margin:30px 0 6px}
/* 主源徽章（req#22） */
.shd-src{display:inline-flex;align-items:center;gap:5px;font-size:12px;color:#8b949e;
  border:1px solid ` + lineC + `;border-radius:20px;padding:1px 9px;background:` + pnlBg2 + `}
.shd-src i{width:7px;height:7px;border-radius:50%}
.src-ok{background:` + downC + `}
.src-warn{background:#d29922}
.src-tip{color:#d29922;margin-left:2px}
.sheet-hd b{font-size:19px;font-weight:800;letter-spacing:.4px}
.shd-date{color:#8b949e;font-size:15px;font-weight:600}
.shd-note{color:#6e7681;font-size:11.5px;margin-left:auto}
.sheet{overflow-x:auto;border:1px solid ` + lineC + `;border-radius:10px;background:` + pnlBg2 + `}
.sheet-t{min-width:1080px}
.sheet-t th{text-align:center;background:#131a24}
.sheet-t th.grp{border-left:1px solid ` + lineC + `;color:#c9d1d9;font-weight:600}
.sheet-t th.l,.sheet-t td.l{text-align:left}
.sheet-t td.num,.sheet-t th.num{text-align:right;font-family:Consolas,monospace}
.sheet-t td.num.pr{font-weight:700}
.sheet-t td small{display:block;color:#8b949e;font-size:11.5px}
.sheet-t tbody tr:hover{background:#182131}
/* 价格走势小多联（暗色卡片，req#26：由白底统一为暗底，与全站一致） */
.tgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(310px,1fr));gap:12px;margin:10px 0 14px}
.tgrid .hide{display:none}
.tpg{display:flex;gap:6px;margin:0 0 18px}
.tpg button{background:#21262d;color:#c9d1d9;border:1px solid #30363d;border-radius:6px;
  font-size:12px;padding:3px 11px;cursor:pointer}
.tpg button.on{background:rgba(88,166,255,.15);color:` + accC + `;border-color:rgba(88,166,255,.5)}
.tcard{display:block;background:` + pnlBg2 + `;border:1px solid ` + lineC + `;border-radius:8px;padding:4px 4px 2px;
  transition:box-shadow .15s,transform .15s,border-color .15s}
.tcard:hover{box-shadow:0 6px 18px rgba(0,0,0,.5);transform:translateY(-1px);border-color:#3d4653}
.tcard svg{display:block;width:100%;height:auto}
.cat-hd h3{font-size:14px;color:#c9d1d9;font-weight:600;margin-top:4px}
/* footer */
.ft{color:#8b949e;font-size:12px;text-align:center;margin-top:44px;line-height:2}
/* detail */
.dh{display:flex;align-items:center;gap:14px;padding:22px 0 6px;flex-wrap:wrap}
.back{color:` + accC + `;font-size:13px;border:1px solid rgba(88,166,255,.35);border-radius:6px;
  padding:4px 12px;background:rgba(88,166,255,.08)}
.back:hover{background:rgba(88,166,255,.18)}
.dh h1{font-size:24px;font-weight:800}
.dh .sub{color:#8b949e;font-size:12.5px;margin-left:auto}
.stat5{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin:16px 0}
.st{border:1px solid ` + lineC + `;border-radius:10px;background:` + pnlBg2 + `;padding:14px 16px}
.st .t{color:#8b949e;font-size:12px}
.st .v{font-size:22px;font-weight:800;font-family:Consolas,monospace;margin-top:6px}
.st .d{color:#8b949e;font-size:11.5px;margin-top:2px}
.pbar{height:6px;border-radius:4px;margin-top:8px;overflow:hidden;background:#21262d}
.pbar i{display:block;height:100%;border-radius:4px}
.chartbox{border:1px solid ` + lineC + `;border-radius:10px;background:` + pnlBg2 + `;
  padding:18px;margin:14px 0}
.chartbox .ct{display:flex;align-items:center;gap:8px;margin-bottom:12px}
.chartbox .ct h3{font-size:15px;font-weight:700}
.rng{margin-left:auto;display:flex;gap:6px}
.rng button{background:#21262d;color:#c9d1d9;border:1px solid #30363d;border-radius:6px;
  padding:4px 14px;font-size:12px;cursor:pointer}
.rng button.on{background:rgba(88,166,255,.15);color:` + accC + `;border-color:rgba(88,166,255,.5)}
.rngv .hide{display:none}
.legend{display:flex;gap:18px;color:#8b949e;font-size:12px;padding:8px 2px 0}
.legend i{display:inline-block;width:14px;height:3px;border-radius:2px;margin-right:5px;vertical-align:middle}
.catmeta{color:#8b949e;font-size:12px;padding:2px 2px 10px}
.catmeta b{font-size:16px;font-weight:700}
@media(max-width:640px){
  .hd h1{font-size:18px}
  .radar .upd2{display:none}
  .card .big{font-size:22px}
}
/* 悬浮提示（走势点 / 离散点） */
#tip{display:none;position:fixed;z-index:99;background:#161b22;border:1px solid #3d4653;
  border-radius:8px;padding:8px 12px;font-size:12px;color:#e6e8eb;pointer-events:none;
  box-shadow:0 4px 18px rgba(0,0,0,.55);max-width:300px;line-height:1.75;white-space:nowrap}
#tip b{color:#fff}
svg circle.hit{cursor:crosshair}
svg circle.hit:hover{fill:rgba(88,166,255,.14);stroke:#58a6ff;stroke-width:1}
svg circle.spt{cursor:pointer;transition:r .1s}
svg circle.spt:hover{r:5.5;fill-opacity:1;stroke-width:1.2}
/* 单型号离散图（req#15） */
svg circle.mspt{cursor:crosshair}
svg circle.mspt:hover{fill-opacity:1;stroke-width:1.8}
.note2{color:#8b949e;font-size:12px;margin-left:auto}
/* zoom interactions */
svg.chart{cursor:grab;touch-action:none;user-select:none;-webkit-user-select:none}
svg.chart.dragging{cursor:grabbing}
svg.chart .chart-zoom{transition:none}
.chart-zoom-hint{display:block;text-align:center;font-size:11px;
  color:#6e7681;margin:6px 0 0;letter-spacing:.3px;pointer-events:none;user-select:none}
@media (hover:hover){.chart-zoom-hint{opacity:.7}}
`

// zoomJS 离散图/走势图缩放交互：滚轮+拖拽+双击复位+惯性+自适应刻度。
// 挂在 <svg class="chart"> 上的 <g class="chart-zoom">（带 data-mode=scatter|line）会被接管。
// 数据节点需带 data-ts / data-price（scatter 额外 data-cat）。
const zoomJS = `<script>(function(){
if(window.cmZoom)return;
var CFG={minScale:1,maxScale:64,decay:0.92,margin:0.09,rMin:1.5,rMax:6,rBase:3.2};
function nn(r,t){t=t||7;if(r<=0)return 1;var e=Math.floor(Math.log10(r)),f=r/Math.pow(10,e),n=1;
if(f>=1.5&&f<3)n=2;else if(f>=3&&f<7)n=5;else if(f>=7)n=10;return n*Math.pow(10,e);}
function ticks(lo,hi,lg){if(!lg||lo<=0||hi<=0){var st=nn((hi-lo)/7,7),s0=Math.ceil(lo/st)*st,ts=[];
for(var v=s0;v<=hi+1e-9;v+=st)ts.push(+v.toFixed(10));return ts;}
var dec=Math.log10(hi/lo),multi=dec<=3?[1,2,5]:dec<=6?[1,5]:[1],out=[];
for(var e=Math.floor(Math.log10(lo))-1;e<=Math.ceil(Math.log10(hi));e++){
for(var k=0;k<multi.length;k++){var v=multi[k]*Math.pow(10,e);if(v>=lo*0.999&&v<=hi*1.001)out.push(v);}}
return out;}
function cl(v,a,b){return Math.max(a,Math.min(b,v));}
function fmtM(n,d){if(n>=10000)return(n/10000).toFixed(1)+'万';if(n>=1000)return(Math.round(n/10)/100)+'k';return d?n.toFixed(d):Math.round(n)+'';}
function attach(svg){
if(svg.__z)return;svg.__z=1;
var g=svg.querySelector('.chart-zoom');if(!g)return;
var W=+svg.getAttribute('data-w')||960,H=+svg.getAttribute('data-h')||520;
var pL=+svg.getAttribute('data-pad-l')||62,pR=+svg.getAttribute('data-pad-r')||20;
var pT=+svg.getAttribute('data-pad-t')||28,pB=+svg.getAttribute('data-pad-b')||30;
var lg=(g.getAttribute('data-mode')||svg.getAttribute('data-mode'))==='scatter';
var cW=W-pL-pR,cH=H-pT-pB;
var cs=Array.prototype.slice.call(g.querySelectorAll('[data-ts]'));
var D=cs.map(function(c){return{ts:+c.getAttribute('data-ts'),p:+c.getAttribute('data-price'),cat:c.getAttribute('data-cat'),e:c};});
var dTmin=Infinity,dTmax=-Infinity,dPmin=Infinity,dPmax=-Infinity;
for(var i=0;i<D.length;i++){var d=D[i];if(d.ts<dTmin)dTmin=d.ts;if(d.ts>dTmax)dTmax=d.ts;
var lp=lg?Math.log10(d.p):d.p;if(lp<dPmin)dPmin=lp;if(lp>dPmax)dPmax=lp;}
if(D.length<2){return;}
var tSp=dTmax-dTmin||1,pSp=dPmax-dPmin||1;
var vp={tMin:dTmin-tSp*CFG.margin,tMax:dTmax+tSp*CFG.margin,pMin:dPmin-pSp*CFG.margin,pMax:dPmax+pSp*CFG.margin,sc:1};
function clamp(){
var tS=vp.tMax-vp.tMin,pS=vp.pMax-vp.pMin;
var minT=Math.max(60,tSp*0.001),minP=Math.max(pSp*0.001,0.01);
if(tS<minT){var tc=(vp.tMin+vp.tMax)/2;vp.tMin=tc-minT/2;vp.tMax=tc+minT/2;}
if(pS<minP){var pc=(vp.pMin+vp.pMax)/2;vp.pMin=pc-minP/2;vp.pMax=pc+minP/2;}
if(tS>tSp*1.05){var tc=(vp.tMin+vp.tMax)/2,tH=tSp*0.55;vp.tMin=tc-tH;vp.tMax=tc+tH;}
vp.pMin=cl(vp.pMin,dPmin-pSp*0.5,dPmin+pSp*0.05);
vp.pMax=cl(vp.pMax,dPmax-pSp*0.05,dPmax+pSp*0.5);
vp.sc=tSp/Math.max(1e-9,vp.tMax-vp.tMin);
}
function xp(ts){return pL+(ts-vp.tMin)/(vp.tMax-vp.tMin)*cW;}
function yp(lp){return pT+(1-(lp-vp.pMin)/(vp.pMax-vp.pMin))*cH;}
var raf=0;
function render(){
raf=0;
var sF=Math.sqrt((vp.tMax-vp.tMin)/tSp),rA=cl(CFG.rBase/sF,CFG.rMin,CFG.rMax);
for(var i=0;i<D.length;i++){var d=D[i];var xx=xp(d.ts),yy=yp(lg?Math.log10(d.p):d.p);
d.e.setAttribute('cx',xx.toFixed(1));d.e.setAttribute('cy',yy.toFixed(1));
d.e.setAttribute('r',rA.toFixed(1));
var vis=xx>=pL-12&&xx<=W-pR+12&&yy>=pT-12&&yy<=H-pB+12;
d.e.style.display=vis?'':'none';}
renderGrid();}
function renderGrid(){
var yT=lg?ticks(Math.pow(10,vp.pMin),Math.pow(10,vp.pMax),true):ticks(vp.pMin,vp.pMax,false);
var ySt=yT.length>1?Math.abs(yT[1]-yT[0]):0,dec=(!lg&&ySt>0&&ySt<1)?(ySt<0.1?2:1):0;
var yH='';for(var i=0;i<yT.length;i++){var tv=yT[i],lp=lg?Math.log10(tv):tv,yy=yp(lp);
if(yy<pT-4||yy>H-pB+4)continue;
yH+='<line x1="'+pL+'" y1="'+yy.toFixed(1)+'" x2="'+(W-pR)+'" y2="'+yy.toFixed(1)+'" stroke="#21262d" stroke-width="1"/>';
yH+='<text x="'+(pL-8)+'" y="'+(yy+4).toFixed(1)+'" fill="#8b949e" font-size="11" text-anchor="end">'+fmtM(tv,dec)+'</text>';}
var inV=[],seen={};for(var i=0;i<D.length;i++){if(D[i].ts>=vp.tMin&&D[i].ts<=vp.tMax&&!seen[D[i].ts]){seen[D[i].ts]=1;inV.push(D[i].ts);}}
inV.sort(function(a,b){return a-b;});
var step=Math.max(1,Math.ceil(inV.length/10));
var xH='';for(var i=0;i<inV.length;i+=step){var ts=inV[i],xx=xp(ts);
if(xx<pL-4||xx>W-pR+4)continue;
xH+='<line x1="'+xx.toFixed(1)+'" y1="'+pT+'" x2="'+xx.toFixed(1)+'" y2="'+(H-pB)+'" stroke="#21262d" stroke-width="1"/>';
var dt=new Date(ts*1000),lb=(dt.getMonth()+1)+'-'+dt.getDate()+' '+('0'+dt.getHours()).slice(-2)+':'+('0'+dt.getMinutes()).slice(-2);
xH+='<text x="'+xx.toFixed(1)+'" y="'+(H-pB+16)+'" fill="#8b949e" font-size="11" text-anchor="middle">'+lb+'</text>';}
var layer=g.querySelector('.grid-layer');if(!layer){layer=document.createElementNS('http://www.w3.org/2000/svg','g');layer.setAttribute('class','grid-layer');g.insertBefore(layer,g.firstChild);}
layer.innerHTML=yH+xH;}
function sch(){if(raf)return;raf=requestAnimationFrame(render);}
function zoomAt(ax,ay,f){
var tA=vp.tMin+(ax-pL)/cW*(vp.tMax-vp.tMin);
var pA=vp.pMin+(1-(ay-pT)/cH)*(vp.pMax-vp.pMin);
vp.tMin=tA-(tA-vp.tMin)*f;vp.tMax=tA+(vp.tMax-tA)*f;
vp.pMin=pA-(pA-vp.pMin)*f;vp.pMax=pA+(vp.pMax-pA)*f;clamp();sch();}
function pan(sx,sy){
var dt=-sx/cW*(vp.tMax-vp.tMin);vp.tMin+=dt;vp.tMax+=dt;
var dp=sy/cH*(vp.pMax-vp.pMin);vp.pMin+=dp;vp.pMax+=dp;clamp();sch();}
function toSvg(e){var r=svg.getBoundingClientRect();return{x:(e.clientX-r.left)*(W/r.width),y:(e.clientY-r.top)*(H/r.height)};}
var drag=0,lx=0,ly=0,lt=0,lDx=0,lDy=0,lDt=0,vX=0,vY=0,inr=0;
function stopIn(){if(inr){cancelAnimationFrame(inr);inr=0;}vX=vY=0;}
svg.addEventListener('wheel',function(e){e.preventDefault();
var c=toSvg(e),f=e.deltaY>0?1.15:1/1.15;
if(e.shiftKey){var tA=vp.tMin+(c.x-pL)/cW*(vp.tMax-vp.tMin);vp.tMin=tA-(tA-vp.tMin)*f;vp.tMax=tA+(vp.tMax-tA)*f;}
else if(e.ctrlKey){var pA=vp.pMin+(1-(c.y-pT)/cH)*(vp.pMax-vp.pMin);vp.pMin=pA-(pA-vp.pMin)*f;vp.pMax=pA+(vp.pMax-pA)*f;}
else{zoomAt(c.x,c.y,f);}
clamp();sch();},{passive:false});
svg.addEventListener('mousedown',function(e){
if(e.button!==0)return;
if(e.target.tagName==='circle'&&e.target.classList.contains('spt'))return;
drag=1;lx=e.clientX;ly=e.clientY;lt=Date.now();lDx=lDy=0;
svg.classList.add('dragging');stopIn();e.preventDefault();});
window.addEventListener('mousemove',function(e){if(!drag)return;
var dx=e.clientX-lx,dy=e.clientY-ly,now=Date.now();
lDt=now-lt;lDx=dx;lDy=dy;lx=e.clientX;ly=e.clientY;lt=now;
var r=svg.getBoundingClientRect();pan(dx*(W/r.width),dy*(H/r.height));});
window.addEventListener('mouseup',function(){if(!drag)return;drag=0;svg.classList.remove('dragging');
if(lDt>0){vX=lDx;vY=lDy;if(Math.abs(vX)>1||Math.abs(vY)>1)runIn();}});
function runIn(){function s(){if(Math.abs(vX)<0.5&&Math.abs(vY)<0.5){inr=0;return;}
vX*=CFG.decay;vY*=CFG.decay;
var r=svg.getBoundingClientRect();pan(vX*(W/r.width),vY*(H/r.height));inr=requestAnimationFrame(s);}
inr=requestAnimationFrame(s);}
svg.addEventListener('dblclick',function(e){e.preventDefault();
var fT=vp.tMin,fT2=vp.tMax,fP=vp.pMin,fP2=vp.pMax;
var tT=dTmin-tSp*CFG.margin,tT2=dTmax+tSp*CFG.margin;
var tP=dPmin-pSp*CFG.margin,tP2=dPmax+pSp*CFG.margin;
var s=Date.now(),dur=240;
function a(){var t=(Date.now()-s)/dur;if(t>=1){vp.tMin=tT;vp.tMax=tT2;vp.pMin=tP;vp.pMax=tP2;vp.sc=1;clamp();sch();return;}
var e=1-Math.pow(1-t,3);vp.tMin=fT+(tT-fT)*e;vp.tMax=fT2+(tT2-fT2)*e;
vp.pMin=fP+(tP-fP)*e;vp.pMax=fP2+(tP2-fP2)*e;clamp();sch();requestAnimationFrame(a);}
a();});
var tch=null;
svg.addEventListener('touchstart',function(e){e.preventDefault();
if(e.touches.length===1){tch={m:'p',x:e.touches[0].clientX,y:e.touches[0].clientY,lDx:0,lDy:0,lDt:0,lT:Date.now()};stopIn();}
else if(e.touches.length===2){var t1=e.touches[0],t2=e.touches[1];tch={m:'z',mx:(t1.clientX+t2.clientX)/2,my:(t1.clientY+t2.clientY)/2,d:Math.hypot(t1.clientX-t2.clientX,t1.clientY-t2.clientY)};}
},{passive:false});
svg.addEventListener('touchmove',function(e){e.preventDefault();if(!tch)return;
if(e.touches.length===1&&tch.m==='p'){var t=e.touches[0],dx=t.clientX-tch.x,dy=t.clientY-tch.y,nw=Date.now();
tch.lDt=nw-tch.lT;tch.lDx=dx;tch.lDy=dy;tch.lT=nw;tch.x=t.clientX;tch.y=t.clientY;
var r=svg.getBoundingClientRect();pan(dx*(W/r.width),dy*(H/r.height));}
else if(e.touches.length===2&&tch.m==='z'){var t1=e.touches[0],t2=e.touches[1],mx=(t1.clientX+t2.clientX)/2,my=(t1.clientY+t2.clientY)/2;
var d=Math.hypot(t1.clientX-t2.clientX,t1.clientY-t2.clientY);
if(tch.d>0&&Math.abs(Math.log(d/tch.d))>0.01){var c=toSvg({clientX:mx,clientY:my});zoomAt(c.x,c.y,tch.d/d);}
var r=svg.getBoundingClientRect();pan((mx-tch.mx)*(W/r.width),(my-tch.my)*(H/r.height));
tch.d=d;tch.mx=mx;tch.my=my;}
},{passive:false});
svg.addEventListener('touchend',function(e){if(!tch)return;
if(tch.m==='p'&&(Math.abs(tch.lDx)>1||Math.abs(tch.lDy)>1)){vX=tch.lDx;vY=tch.lDy;runIn();}
if(e.touches.length===0)tch=null;});
clamp();sch();
var hint=document.createElement('div');hint.className='chart-zoom-hint';
hint.textContent='滚轮 / 双指捏合缩放 · 拖拽 / 单指平移 · 双击复位';
svg.parentNode&&svg.parentNode.insertBefore(hint,svg.nextSibling);
}
function init(){Array.prototype.forEach.call(document.querySelectorAll('svg.chart'),function(s){if(!s.__z)attach(s);});}
if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',init);}else{init();}
window.cmZoom={attach:attach,nn:nn};
})();</script>`

// tipJS 通用悬浮提示：任何带 data-tip 的元素 hover 时显示其内容（HTML），data-go 点击跳转。
const tipJS = `<script>(function(){
var d=document.createElement('div');d.id='tip';document.body.appendChild(d);
function mv(e){var x=e.clientX+14,y=e.clientY+14,w=d.offsetWidth,h=d.offsetHeight;
if(x+w>innerWidth-8)x=e.clientX-w-12;if(y+h>innerHeight-8)y=e.clientY-h-12;
d.style.left=x+'px';d.style.top=y+'px'}
document.addEventListener('mouseover',function(e){
if(!e.target.closest)return;
var t=e.target.closest('[data-tip]');
if(t){d.innerHTML=t.getAttribute('data-tip');d.style.display='block';mv(e)}
else{d.style.display='none'}});
document.addEventListener('mousemove',function(e){if(d.style.display==='block')mv(e)});
document.addEventListener('click',function(e){
if(!e.target.closest)return;
var t=e.target.closest('[data-go]');
if(t){location.href=t.getAttribute('data-go')}});
})();</script>`

// bucketRound 轮次桶聚合：同一采集轮次（sec 秒桶）内的多轮只保留最后价。
// 折线与均线都以它为基准；桶起始时间戳 = floor(ts / sec) * sec。
// sec=roundSec(3600) 用于走势图（1 小时粒度，任何采集节奏都不丢点）；
// sec=scatterSec(21600) 用于离散图 X 轴（与 6 小时采集节奏对齐）。
func bucketRound(pts []pt, sec int64) []pt {
	idx := map[int64]int{}
	var out []pt
	for _, p := range pts {
		b := p.Ts - p.Ts%sec   // 桶起始（向下取整到 sec 边界）
		if i, ok := idx[b]; ok {
			out[i] = p                  // 后出现的（桶内更晚那轮）覆盖
			continue
		}
		idx[b] = len(out)
		out = append(out, p)
	}
	return out
}

// ---------------- 品类整体走势 ----------------

type catTrend struct {
	Cat  string
	Pts  []pt // Price = 品类指数（基准日 = 100）
	Num  int   // 参与型号数
	Last float64
}

func median(vals []float64) float64 {
	if len(vals) == 0 {
		return 0
	}
	s := append([]float64(nil), vals...)
	sort.Float64s(s)
	n := len(s)
	if n%2 == 1 {
		return s[n/2]
	}
	return (s[n/2-1] + s[n/2]) / 2
}

// geoMean 几何平均（Jevons 指数，CPI 基本类指数的推荐算法）。
// 为什么不用中位数：实测 100 个型号里 57 个价格从未变过 → 中位数被「多数不变」钉死在 100，
// 剩下 43 个型号的真实涨跌完全看不见。几何平均既对部分型号的变化敏感，又比算术平均抗离群
// （单个型号价格翻倍不会带飞整条曲线）。
func geoMean(vals []float64) float64 {
	if len(vals) == 0 {
		return 0
	}
	s := 0.0
	for _, v := range vals {
		if v <= 0 {
			continue
		}
		s += math.Log(v)
	}
	return math.Exp(s / float64(len(vals)))
}

// buildCatTrends 品类等权价格指数（req#19 重写）。
//
// ⚠ 旧逻辑的毛病（实测各轮参与型号数 1 / 15 / 20 / 96 …，波动极大）：
//   · 每轮直接取「该轮采集到的型号价格中位数」→ 成分一变中位价就跳；
//   · 基准 base = 第一个有数据的轮次，而那一轮可能只有 1 条数据 → 整条曲线被单点带偏。
//
// 新逻辑：
//  1) 每个型号先按自身首个有效价格归一化（自身 = 100）→ 消除型号间价位差异造成的权重倾斜；
//  2) 同一轮次（1 小时桶）取各型号指数的**几何平均**（Jevons）→ 等权指数，抗离群又敏感；
//  3) 型号该轮未采集时沿用其上一轮价格（前向填充，仅在其首次出现之后）→ 成分稳定不漂移；
//  4) 真实采集型号数 < max(2, 30% 型号数) 的稀疏轮次直接丢弃（不再用 1~2 条数据的轮次定调）。
func buildCatTrends(views map[string]*rowView, cats []string) []catTrend {
	var out []catTrend
	for _, cat := range cats {
		// 型号 → 桶(轮次) → 价格
		data := map[string]map[int64]float64{}
		bset := map[int64]bool{}
		nModel := 0
		for k, v := range views {
			if v.S.Cat != cat {
				continue
			}
			dc := bucketRound(v.S.Points, roundSec)
			if len(dc) == 0 {
				continue
			}
			m := map[int64]float64{}
			for _, p := range dc {
				b := p.Ts - p.Ts%roundSec
				m[b] = p.Price
				bset[b] = true
			}
			data[k] = m
			nModel++
		}
		if nModel == 0 || len(bset) == 0 {
			continue
		}
		var buckets []int64
		for b := range bset {
			buckets = append(buckets, b)
		}
		sort.Slice(buckets, func(i, j int) bool { return buckets[i] < buckets[j] })
		// 每个型号展开成与 buckets 等长的序列（0 = 该轮未出现），并做前向填充
		seqs := make(map[string][]float64, len(data))
		for k, m := range data {
			seq := make([]float64, len(buckets))
			last, started := 0.0, false
			for i, b := range buckets {
				if p, ok := m[b]; ok && p > 0 {
					last, started = p, true
					seq[i] = p
					continue
				}
				if started && last > 0 {
					seq[i] = last // 未采集 → 沿用上一轮价（价格未变的合理假设）
				}
			}
			seqs[k] = seq
		}
		// 自身基准 = 首个有效价
		base := make(map[string]float64, len(seqs))
		for k, seq := range seqs {
			for _, p := range seq {
				if p > 0 {
					base[k] = p
					break
				}
			}
		}
		minReal := nModel * 3 / 10
		if minReal < 2 {
			minReal = 2
		}
		if minReal > nModel {
			minReal = nModel
		}
		var pts []pt
		for i, b := range buckets {
			vals := make([]float64, 0, nModel)
			real := 0
			for k, m := range data {
				if rp, ok := m[b]; ok && rp > 0 {
					real++
				}
				pv := seqs[k][i]
				if pv <= 0 || base[k] <= 0 {
					continue
				}
				vals = append(vals, pv/base[k]*100)
			}
			if real < minReal || len(vals) < minReal {
				continue // 稀疏轮次不参与，避免假跳变
			}
			pts = append(pts, pt{Ts: b, Price: geoMean(vals)})
		}
		if len(pts) >= 1 {
			out = append(out, catTrend{Cat: cat, Pts: pts, Num: nModel, Last: pts[len(pts)-1].Price})
		}
	}
	return out
}

// ---------------- 页面构建 ----------------

func buildHome(db *sql.DB) (string, error) {
	views, order, err := buildRowViews(db)
	if err != nil {
		return "", err
	}
	// req#21：同型号去重（一张卡只出现一次，按品牌权重选代表）
	views, order = dedupViews(views, order)
	// req#22：平台主源冗余路由（京东被拦 → 自动降级淘宝/拼多多，恢复后自动回切）
	pOrder, primary, degraded := platRoute(db)
	now := time.Now()
	lastTs := lastRoundAt(db)        // 最近一轮采集时间（所有页面的「数据更新」基准）
	dateStr := now.Format("2006-01-02")
	_ = dateStr                      // 兼容旧引用；新版 header 用 lastTs 时间戳

	// 分类归组
	catOrder := []string{"显卡", "处理器"}
	byCat := map[string][]string{}
	for _, k := range order {
		v := views[k]
		byCat[v.S.Cat] = append(byCat[v.S.Cat], k)
	}
	for c := range byCat {
		sort.Slice(byCat[c], func(i, j int) bool {
			return views[byCat[c][i]].S.Name() < views[byCat[c][j]].S.Name()
		})
	}

	var b strings.Builder
	b.WriteString(`<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">` +
		`<meta name="viewport" content="width=device-width,initial-scale=1">` +
		`<title>超米 DIY 硬件行情中心</title><style>` + pageCSS() + `</style></head><body><div class="wrap">`)

	// header
	b.WriteString(`<div class="hd"><h1>超米 DIY 硬件行情中心</h1>` +
		`<div class="sbox"><input id="q" placeholder="搜型号，如 RTX 5070 Ti" onkeydown="if(event.key==='Enter')doSearch()" /><button onclick="doSearch()">搜索</button><span id="sres"></span></div>` +
		`<span class="live"><i></i>LIVE</span>` +
		`<a class="navlink" href="/scatter">整体离散图</a>` +
		`<span class="upd" title="最近一轮采集时间">` + dataBadge(lastTs, now.Unix()) + `</span></div>` +
		`<script>function doSearch(){var q=document.getElementById('q').value.trim();var r=document.getElementById('sres');if(!q){return}r.textContent='搜索中...';fetch('/api/search?q='+encodeURIComponent(q)).then(function(x){return x.json()}).then(function(d){if(d&&d.code===0&&d.data){r.textContent='✔ '+d.data.title+' · ¥'+d.data.price+' ['+d.data.plat+']'}else{r.textContent=(d&&d.msg)||'无结果'}}).catch(function(e){r.textContent='搜索失败'})}</script>`)

	// ticker（所有有价型号，累计涨幅）
	var tk []string
	for _, k := range order {
		v := views[k]
		if v.M.Samples < 2 {
			continue
		}
		c := flatC
		sign := "+"
		if v.M.Total > 0.005 {
			c = upC
		} else if v.M.Total < -0.005 {
			c = downC
			sign = ""
		}
		tk = append(tk, fmt.Sprintf(`<span class="tk-item"><span class="nm">%s</span> `+
			`<span class="pr">%s</span> <span class="pc" style="color:%s">%s%.2f%%</span></span>`,
			html.EscapeString(v.S.Name()), money(v.M.Cur), c, sign, v.M.Total))
	}
	if len(tk) == 0 {
		tk = append(tk, `<span class="tk-item"><span class="nm">数据积累中，暂无完整轮次</span></span>`)
	}
	tkAll := strings.Join(tk, "")
	b.WriteString(`<div class="ticker"><div class="tk-move">` + tkAll + tkAll + `</div></div>`)

	// 雷达（req#26 重做）：涨跌榜改用真实时间窗口（1日/7日），
	// 不再用「累计涨幅（cur vs 首点）」冒充「今日」；无参考点（HasRef）时不上榜。
	var dayUp, dayDown, wkUp, streakList []string
	for _, k := range order {
		v := views[k]
		if v.M.Samples < 2 {
			continue
		}
		if v.M.HasRef[0] {
			if v.M.Pct[0] > 0.05 {
				dayUp = append(dayUp, k)
			} else if v.M.Pct[0] < -0.05 {
				dayDown = append(dayDown, k)
			}
		}
		if v.M.HasRef[1] && v.M.Pct[1] > 0.05 {
			wkUp = append(wkUp, k)
		}
		if v.M.Streak >= 2 || v.M.Streak <= -2 {
			streakList = append(streakList, k)
		}
	}
	sort.Slice(dayUp, func(i, j int) bool { return views[dayUp[i]].M.Pct[0] > views[dayUp[j]].M.Pct[0] })
	sort.Slice(dayDown, func(i, j int) bool { return views[dayDown[i]].M.Pct[0] < views[dayDown[j]].M.Pct[0] })
	sort.Slice(wkUp, func(i, j int) bool { return views[wkUp[i]].M.Pct[1] > views[wkUp[j]].M.Pct[1] })
	topN := func(ks []string, n int) []string {
		if len(ks) > n {
			ks = ks[:n]
		}
		return ks
	}
	grp := func(title string, ks []string, f func(v *rowView) string) string {
		if len(ks) == 0 {
			return ""
		}
		var items []string
		for i, k := range topN(ks, 3) {
			items = append(items, fmt.Sprintf(`<span class="itm"><span class="rank">%d.</span> %s %s</span>`,
				i+1, html.EscapeString(views[k].S.Name()), f(views[k])))
		}
		return fmt.Sprintf(`<span class="grp"><b>%s</b>%s</span>`, title, strings.Join(items, ""))
	}
	pctSpan := func(x float64) string {
		c, sign := upC, "+"
		if x < 0 {
			c, sign = downC, ""
		}
		return fmt.Sprintf(`<span class="pc" style="color:%s">%s%.2f%%</span>`, c, sign, x)
	}
	radar := grp("今日涨榜", dayUp, func(v *rowView) string { return pctSpan(v.M.Pct[0]) }) +
		grp("今日跌榜", dayDown, func(v *rowView) string { return pctSpan(v.M.Pct[0]) }) +
		grp("7日涨榜", wkUp, func(v *rowView) string { return pctSpan(v.M.Pct[1]) }) +
		grp("连续趋势", streakList, func(v *rowView) string {
			if v.M.Streak > 0 {
				return fmt.Sprintf(`<span style="color:%s">连涨%d天</span>`, upC, v.M.Streak)
			}
			return fmt.Sprintf(`<span style="color:%s">连跌%d天</span>`, downC, -v.M.Streak)
		})
	if radar == "" {
		radar = `<span class="grp"><b>行情雷达</b><span class="itm flat">数据积累中——每轮更新后自动生成涨跌榜</span></span>`
	}
	b.WriteString(`<div class="radar"><span class="tab1">行情雷达</span>` + radar +
		`<span class="upd2">数据更新：` + dateStr + `</span></div>`)

	// 品类卡
	var cards strings.Builder
	for _, c := range catOrder {
		ks := byCat[c]
		if len(ks) == 0 {
			continue
		}
		sum, n := 0.0, 0
		hiK, loK := "", ""
		hiV, loV := math.Inf(1), math.Inf(-1)
		for _, k := range ks {
			v := views[k]
			if v.M.Samples < 2 {
				continue
			}
			sum += v.M.Total
			n++
			if v.M.Total > loV {
				loV, hiK = v.M.Total, k
			}
			if v.M.Total < hiV {
				hiV, loK = v.M.Total, k
			}
		}
		avg := 0.0
		if n > 0 {
			avg = sum / float64(n)
		}
		cc := flatC
		sign := "+"
		if avg > 0.005 {
			cc = upC
		} else if avg < -0.005 {
			cc = downC
			sign = ""
		}
		sub := `<div class="sub">涨幅最高：<b style="color:` + upC + `">—</b><br>跌幅最大：<b style="color:` + downC + `">—</b></div>`
		if hiK != "" && loK != "" && hiK != loK {
			sub = fmt.Sprintf(`<div class="sub">涨幅最高：<b style="color:%s">%s %s%.1f%%</b><br>`+
				`跌幅最大：<b style="color:%s">%s %.1f%%</b></div>`,
				upC, html.EscapeString(views[hiK].S.Name()), sign, math.Abs(loV),
				downC, html.EscapeString(views[loK].S.Name()), hiV)
		} else if hiK != "" {
			sub = fmt.Sprintf(`<div class="sub">涨幅最高：<b style="color:%s">%s %s%.1f%%</b><br>跌幅最大：<b>—</b></div>`,
				upC, html.EscapeString(views[hiK].S.Name()), sign, math.Abs(loV))
		}
		cards.WriteString(fmt.Sprintf(`<div class="card"><div class="t">%s<small>%d 型号</small></div>`+
			`<div class="big" style="color:%s">%s%.2f%%</div>%s</div>`,
			c, len(ks), cc, sign, avg, sub))
	}
	if cards.Len() > 0 {
		b.WriteString(`<div class="cards">` + cards.String() + `</div>`)
	}

	// ★ 品类整体走势 —— 【req#20 已下线 2026-09-15】
	// 用户指示：指数算法反复调整（中位数 → 等权中位数 → Jevons 几何平均）仍不达预期，
	// 判定为投入产出比过低，直接撤掉止损。整块渲染代码以注释形式保留，
	// 需要恢复时删掉下面 /* 与对应 */ 即可（buildCatTrends / geoMean / catTrend 定义一并保留未删，
	// 旧的中位数版本见 ai-trace #36 之前的服务端 git-less 备份 /opt/cm-ops/backup/）。
	/*
	trends := buildCatTrends(views, catOrder)
	// 有型号但数据不足 2 轮的品类也占一个按钮位（显示「积累中」），不再整品类消失
	haveTrend := map[string]bool{}
	for _, tr := range trends {
		haveTrend[tr.Cat] = true
	}
	for _, c := range catOrder {
		if !haveTrend[c] && len(byCat[c]) > 0 {
			trends = append(trends, catTrend{Cat: c, Num: len(byCat[c])})
		}
	}
	b.WriteString(`<div class="cat-hd"><h2>品类整体走势</h2>` +
		`<span class="note">等权价格指数（Jevons）：每个型号先按自身首轮价归一，再取每轮几何平均（首轮 = 100）· ` +
		`未采集的轮次沿用上次价格 · 稀疏轮次已剔除 · 离散点（涨红跌绿）· 窗口 18 个月 · 悬停看数值</span></div>`)
	if len(trends) == 0 {
		b.WriteString(`<div class="chartbox"><div style="padding:48px;text-align:center;color:#8b949e">` +
			`积累中 · 需要 ≥ 2 个有效轮次（每轮至少 30% 的型号有采集）才能画出指数走势` +
			`</div></div>`)
	} else {
		b.WriteString(`<div class="chartbox"><div class="ct"><div class="rng">`)
		for i, tr := range trends {
			cls := ""
			if i == 0 {
				cls = ` class="on"`
			}
			b.WriteString(fmt.Sprintf(`<button onclick="showCat('cat%d',this)"%s>%s</button>`,
				i, cls, html.EscapeString(tr.Cat)))
		}
		b.WriteString(`</div></div><div class="rngv">`)
		for i, tr := range trends {
			hide := ""
			if i > 0 {
				hide = ` class="hide"`
			}
			if len(tr.Pts) == 0 {
				b.WriteString(fmt.Sprintf(`<div id="cat%d"%s><div class="catmeta">%d 个型号 · 积累中</div>`+
					`<div style="padding:44px;text-align:center;color:#8b949e">积累中 · 有效轮次不足（每轮需 ≥30% 型号有采集）</div></div>`,
					i, hide, tr.Num))
				continue
			}
			pct := tr.Last - 100
			cc, sign := flatC, ""
			if pct > 0.05 {
				cc, sign = upC, "+"
			} else if pct < -0.05 {
				cc = downC
			}
			b.WriteString(fmt.Sprintf(`<div id="cat%d"%s><div class="catmeta">`+
				`%d 个型号 · %d 个有效轮次 · 当前指数 <b style="color:%s">%.1f</b> `+
				`<span style="color:%s">较首轮 %s%.2f%%</span></div>`,
				i, hide, tr.Num, len(tr.Pts), cc, tr.Last, cc, sign, pct))
			// req#15：折线 → 离散点（只画点，不画线/均线）
			trCat, trNum, trPts := tr.Cat, tr.Num, tr.Pts
			b.WriteString(scatterSVG(trPts, 960, 320,
				func(v float64) string { return fmt.Sprintf("%.1f", v) },
				func(ts int64) string { return time.Unix(ts, 0).Format("01-02 15:04") },
				func(i int, p pt) string {
					chg := `<span style="color:#8b949e">—</span>`
					if i > 0 {
						d := p.Price - trPts[i-1].Price
						cc2, sg := flatC, ""
						if d > 0.005 {
							cc2, sg = upC, "+"
						} else if d < -0.005 {
							cc2 = downC
						}
						chg = fmt.Sprintf(`<span style="color:%s">%s%.2f</span>`, cc2, sg, d)
					}
					return fmt.Sprintf(`<b>%s 指数</b><br>%s<br>指数 <b>%.2f</b> · 较前点 %s<br>%d 个型号参与`,
						trCat, time.Unix(p.Ts, 0).Format("01-02 15:04"), p.Price, chg, trNum)
				}))
			b.WriteString(`</div>`)
		}
		b.WriteString(`</div><div class="legend">` +
			`<span><i style="background:` + upC + `"></i>较前点上涨</span>` +
			`<span><i style="background:` + downC + `"></i>较前点下跌</span>` +
			`<span><i style="background:` + flatC + `"></i>持平</span>` +
			`</div></div>`)
	}
	*/

	// 行情矩阵表（req#16，req#18 加品类 tab：一次只显示一个品类，不再整页平铺）
	nowMs := time.Now().Unix()
	mCats := []string{}
	for _, c := range catOrder {
		if len(byCat[c]) > 0 {
			mCats = append(mCats, c)
		}
	}
	if len(mCats) > 0 {
		b.WriteString(`<div class="cat-hd"><h2>行情矩阵</h2>` +
			`<span class="note">点击品类切换 · 史低价/日低价/原价/跑分/性价比 + 各平台现价涨跌（涨红跌绿）</span></div>`)
		b.WriteString(`<div class="chartbox"><div class="ct"><div class="rng">`)
		for i, c := range mCats {
			cls := ""
			if i == 0 {
				cls = ` class="on"`
			}
			b.WriteString(fmt.Sprintf(`<button onclick="showCat('mcat%d',this)"%s>%s</button>`,
				i, cls, html.EscapeString(c)))
		}
		b.WriteString(`</div></div><div class="rngv">`)
	}
	for i, c := range mCats {
		ks := byCat[c]
		hide := ""
		if i > 0 {
			hide = ` class="hide"`
		}
		b.WriteString(fmt.Sprintf(`<div id="mcat%d"%s>`, i, hide))
		isGPU := c == "显卡"
		// 该品类实际有数据的平台（固定顺序：京东 → 淘宝 → 拼多多）
		platHas := map[string]bool{}
		for _, k := range ks {
			for pl := range platBreakdown(views[k].S) {
				platHas[pl] = true
			}
		}
		var plats []string
		for _, pl := range pOrder {
			if platHas[pl] {
				plats = append(plats, pl)
			}
		}
		// TSE / 性价比 的蓝色渐变尺度（品类内归一）
		tseMin, tseMax := 1<<30, 0
		scMin, scMax := 1e18, -1e18
		if isGPU {
			for _, k := range ks {
				t := tseOf(views[k].S.Cat, views[k].S.Model, views[k].S.Spec)
				if t <= 0 || views[k].M.MinP <= 0 {
					continue
				}
				if t < tseMin {
					tseMin = t
				}
				if t > tseMax {
					tseMax = t
				}
				r := float64(t) / views[k].M.MinP
				if r < scMin {
					scMin = r
				}
				if r > scMax {
					scMax = r
				}
			}
		}
		b.WriteString(fmt.Sprintf(`<div class="sheet-hd"><b>%s价格每日更新</b>` +
			`<span class="shd-date">| %s</span>` +
			`<span class="shd-src">主源 %s</span>` +
			`<span class="shd-note">史低价=收录窗口内最低 · 日低价=24h最低 · 原价=首采价 · 点击型号看走势</span></div>`,
			c, time.Now().Format("1月2日"), srcBadge(primary, degraded)))
		var th strings.Builder
		th.WriteString(`<tr>` +
			`<th rowspan="2" class="l">型号</th><th rowspan="2">史低价</th>` +
			`<th rowspan="2">日低价</th><th rowspan="2">原价</th>`)
		if isGPU {
			th.WriteString(`<th rowspan="2">TSE跑分</th>`)
		}
		th.WriteString(`<th rowspan="2">今日最低平台</th><th rowspan="2">店铺</th><th rowspan="2">品牌型号</th>`)
		if isGPU {
			th.WriteString(`<th rowspan="2">性价比</th>`)
		}
		for _, pl := range plats {
			th.WriteString(`<th colspan="2" class="grp">` + platDisp(pl) + `</th>`)
		}
		th.WriteString(`</tr><tr>`)
		for range plats {
			th.WriteString(`<th>现价</th><th>涨跌</th>`)
		}
		th.WriteString(`</tr>`)
		b.WriteString(`<div class="sheet"><table class="sheet-t"><thead>` + th.String() + `</thead><tbody>`)
		for _, k := range ks {
			v := views[k]
			s, m := v.S, v.M
			cells := platBreakdown(s)
			dLow := dayLow(s, nowMs)
			tse := tseOf(s.Cat, s.Model, s.Spec)
			// 行首型号按 1 日涨跌着色（涨红 / 跌绿，中国习惯）
			nameStyle := ""
			if m.HasRef[0] && !math.IsNaN(m.Pct[0]) {
				if m.Pct[0] > 0.05 {
					nameStyle = ` style="color:#c93030"`
				} else if m.Pct[0] < -0.05 {
					nameStyle = ` style="color:#1d8a4c"`
				}
			}
			brandCell := html.EscapeString(s.Brand)
			if s.Spec != "" && (s.Cat == "内存条" || s.Cat == "固态硬盘") {
				brandCell += `<small>` + html.EscapeString(s.Spec) + `</small>`
			}
			b.WriteString(fmt.Sprintf(
				`<tr><td class="l"><a class="mdl" href="/detail?k=%s"><span%s>%s</span><small>%s</small></a></td>`+
					`<td class="num pr">%s</td><td class="num">%s</td><td class="num">%s</td>`,
				v.DetailK, nameStyle, html.EscapeString(s.Model), html.EscapeString(s.Brand),
				money(m.MinP), priceInt(dLow), money(m.First)))
			if isGPU {
				if tse > 0 {
					b.WriteString(fmt.Sprintf(`<td class="num" style="%s">%s</td>`,
						heatStyle(float64(tse), float64(tseMin), float64(tseMax)), tho(float64(tse))))
				} else {
					b.WriteString(`<td class="num">—</td>`)
				}
			}
			lastShop := ""
			if n := len(s.Points); n > 0 {
				lastShop = s.Points[n-1].Shop
			}
			b.WriteString(fmt.Sprintf(`<td>%s</td><td>%s</td><td>%s</td>`,
				platDisp(platOf(lastShop)), html.EscapeString(lastShop), brandCell))
			if isGPU {
				if tse > 0 && m.MinP > 0 {
					b.WriteString(fmt.Sprintf(`<td class="num" style="%s">%s</td>`,
						heatStyle(float64(tse)/m.MinP, scMin, scMax), tho(float64(tse)/m.MinP)))
				} else {
					b.WriteString(`<td class="num">—</td>`)
				}
			}
			for _, pl := range plats {
				pc := cells[pl]
				if pc == nil || pc.Cur <= 0 {
					b.WriteString(`<td class="num">—</td><td class="num"></td>`)
					continue
				}
				b.WriteString(fmt.Sprintf(`<td class="num pr">%s</td><td class="num">%s</td>`,
					priceInt(pc.Cur), deltaChip(pc.Cur, pc.Prev)))
			}
			b.WriteString(`</tr>`)
		}
		b.WriteString(`</tbody></table></div></div>`)
	}
	if len(mCats) > 0 {
		b.WriteString(`</div></div>`)
	}

	// 价格走势小多联图（req#17；req#18：品类 tab + 每页 24 张分页）
	if len(mCats) > 0 {
		b.WriteString(`<div class="cat-hd" id="trends"><h2>价格走势图</h2>` +
			`<span class="note">按品类切换 · 每页 ` + strconv.Itoa(trendPerPage) +
			` 张 · 品牌色阶梯线（NVIDIA 绿 / AMD 红 / Intel 蓝）· 点击进详情</span></div>`)
		b.WriteString(`<div class="chartbox"><div class="ct"><div class="rng">`)
		for i, c := range mCats {
			cls := ""
			if i == 0 {
				cls = ` class="on"`
			}
			b.WriteString(fmt.Sprintf(`<button onclick="showCat('tcat%d',this)"%s>%s</button>`,
				i, cls, html.EscapeString(c)))
		}
		b.WriteString(`</div></div><div class="rngv">`)
		for i, c := range mCats {
			ks := byCat[c]
			hide := ""
			if i > 0 {
				hide = ` class="hide"`
			}
			b.WriteString(fmt.Sprintf(`<div id="tcat%d"%s>`, i, hide))
			b.WriteString(fmt.Sprintf(`<div class="cat-hd"><h3>%s · %d 个型号</h3></div><div class="tgrid">`,
				html.EscapeString(c), len(ks)))
			for j, k := range ks {
				v := views[k]
				cls := "tcard"
				if j >= trendPerPage {
					cls += " hide"
				}
				b.WriteString(fmt.Sprintf(`<a class="%s" href="/detail?k=%s">%s</a>`,
					cls, v.DetailK, trendPanelSVG(v.S, v.M.MinP)))
			}
			b.WriteString(`</div>`)
			if np := (len(ks) + trendPerPage - 1) / trendPerPage; np > 1 {
				b.WriteString(`<div class="tpg">`)
				for p := 0; p < np; p++ {
					cls := ""
					if p == 0 {
						cls = ` class="on"`
					}
					b.WriteString(fmt.Sprintf(`<button onclick="tpage('tcat%d',%d,this)"%s>%d</button>`,
						i, p, cls, p+1))
				}
				b.WriteString(`</div>`)
			}
			b.WriteString(`</div>`)
		}
		b.WriteString(`</div></div>`)
	}

	if len(order) == 0 {
		b.WriteString(`<div style="color:#8b949e;padding:60px 0;text-align:center">暂无数据——等第一轮数据入库后自动出现</div>`)
	}

	b.WriteString(`<script>function showCat(id,btn){var box=btn.closest('.chartbox');` +
		`box.querySelectorAll('.rngv>div').forEach(function(d){d.classList.add('hide')});` +
		`box.querySelector('#'+id).classList.remove('hide');` +
		`box.querySelectorAll('.rng button').forEach(function(x){x.classList.remove('on')});` +
		`btn.classList.add('on');}</script>`)
	b.WriteString(`<script>function tpage(id,p,btn){var n=` + strconv.Itoa(trendPerPage) +
		`,box=document.querySelector('#'+id);` +
		`box.querySelectorAll('.tcard').forEach(function(a,i){a.classList.toggle('hide',!(i>=p*n&&i<(p+1)*n))});` +
		`box.querySelectorAll('.tpg button').forEach(function(x){x.classList.remove('on')});` +
		`btn.classList.add('on');}</script>`)
	b.WriteString(zoomJS + tipJS)
	b.WriteString(`<div class="ft">超米 DIY 硬件行情中心 · Go + MySQL · 多平台价差对比<br>` +
		`涨红跌绿 · 每轮数据自动更新 · 历史轮次永久留档 · <a href="/healthz" style="color:#58a6ff">healthz</a></div>`)
	b.WriteString(`</div></body></html>`)
	return b.String(), nil
}

// ---------------- 整体离散图（散点图） ----------------

var catColor = map[string]string{
	"显卡":   accC,  // 蓝
	"处理器":  upC,   // 红
	"内存条":  maC7,  // 黄
	"固态硬盘": maC30, // 紫
	"游戏主机": flatC,
}

func catColorOf(cat string) string {
	if c, ok := catColor[cat]; ok {
		return c
	}
	return flatC
}

// ---------------- 离散点图（req#15：全站砍掉折线，统一离散点形态） ----------------

// scatterSVG 通用离散点图：只画点，不画折线 / 均线。
//   · 点色 = 较前点涨跌（涨红 / 跌绿 / 平灰）；全序列最高 / 最低点额外描一圈；
//   · 结构带 class="chart" + <g class="chart-zoom" data-mode="line"> + <g class="grid-layer">，
//     由 zoomJS 接管缩放，renderGrid() 会整体替换网格层（服务端网格必须包在这一层里，否则双网格）；
//   · 点数 ≥ 1 即渲染（数据没累积到就按实际来）；
//   · unit/fmtY 决定 Y 轴刻度文案，fmtX 决定 X 轴两端时间文案。
func scatterSVG(pts []pt, w, h int, fmtY func(float64) string, fmtX func(int64) string,
	tipAt func(i int, p pt) string) string {
	if len(pts) == 0 {
		return ""
	}
	const padL, padR, padT, padB = 58.0, 18.0, 16.0, 40.0
	t0, t1 := pts[0].Ts, pts[len(pts)-1].Ts
	if t1 <= t0 {
		t1 = t0 + scatterSec
	}
	lo, hi := pts[0].Price, pts[0].Price
	for _, p := range pts {
		lo = math.Min(lo, p.Price)
		hi = math.Max(hi, p.Price)
	}
	if hi-lo < 1e-9 {
		d := math.Abs(hi) * 0.02
		if d < 0.5 {
			d = 0.5
		}
		lo, hi = lo-d, hi+d
	}
	sp := hi - lo
	lo -= sp * 0.10 // 上下各留 10% 视觉余量
	hi += sp * 0.10

	cw := float64(w) - padL - padR
	chh := float64(h) - padT - padB
	X := func(ts int64) float64 { return padL + (float64(ts)-float64(t0))/float64(t1-t0)*cw }
	Y := func(v float64) float64 { return padT + (1-(v-lo)/(hi-lo))*chh }

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf(`<svg class="chart" viewBox="0 0 %d %d" width="100%%" style="max-width:%dpx"`+
		` data-w="%d" data-h="%d" data-pad-l="%.0f" data-pad-r="%.0f" data-pad-t="%.0f" data-pad-b="%.0f"`+
		` xmlns="http://www.w3.org/2000/svg"><g class="chart-zoom" data-mode="line"><g class="grid-layer">`,
		w, h, w, w, h, padL, padR, padT, padB))
	// 静态网格（无 JS 时兜底；有 JS 时被 renderGrid 整体替换）
	for i := 0; i <= 4; i++ {
		v := lo + (hi-lo)*float64(i)/4
		yy := Y(v)
		sb.WriteString(fmt.Sprintf(`<line x1="%.0f" y1="%.1f" x2="%.0f" y2="%.1f" stroke="#21262d" stroke-width="1"/>`,
			padL, yy, float64(w)-padR, yy))
		sb.WriteString(fmt.Sprintf(`<text x="%.0f" y="%.1f" fill="#8b949e" font-size="11" text-anchor="end">%s</text>`,
			padL-8, yy+4, fmtY(v)))
	}
	sb.WriteString(fmt.Sprintf(`<text x="%.0f" y="%d" fill="#8b949e" font-size="11">%s</text>`, padL, h-8, fmtX(t0)))
	sb.WriteString(fmt.Sprintf(`<text x="%.0f" y="%d" fill="#8b949e" font-size="11" text-anchor="end">%s</text>`,
		float64(w)-padR, h-8, fmtX(t1)))
	sb.WriteString(`</g>`)

	mn, mx := pts[0].Price, pts[0].Price
	for _, p := range pts {
		mn = math.Min(mn, p.Price)
		mx = math.Max(mx, p.Price)
	}
	for i, p := range pts {
		c := flatC
		if i > 0 {
			if p.Price > pts[i-1].Price {
				c = upC
			} else if p.Price < pts[i-1].Price {
				c = downC
			}
		}
		xx, yy := X(p.Ts), Y(p.Price)
		tip := ""
		if tipAt != nil {
			tip = html.EscapeString(tipAt(i, p))
		}
		sb.WriteString(fmt.Sprintf(`<circle class="mspt" cx="%.1f" cy="%.1f" r="4" fill="%s" fill-opacity="0.85"`+
			` stroke="%s" stroke-width="1" data-ts="%d" data-price="%.2f" data-tip="%s"/>`,
			xx, yy, c, c, p.Ts, p.Price, tip))
		if p.Price == mx {
			sb.WriteString(fmt.Sprintf(`<circle cx="%.1f" cy="%.1f" r="7.5" fill="none" stroke="%s" stroke-width="1.2" opacity=".75"/>`,
				xx, yy, upC))
		}
		if p.Price == mn && mx != mn {
			sb.WriteString(fmt.Sprintf(`<circle cx="%.1f" cy="%.1f" r="7.5" fill="none" stroke="%s" stroke-width="1.2" opacity=".75"/>`,
				xx, yy, downC))
		}
	}
	sb.WriteString(`</g></svg>`)
	return sb.String()
}

// buildScatter 全站离散图：X 轴=采集轮次时间点，Y 轴=价格（对数刻度），每型号每轮一个散点，按品类着色。
func buildScatter(db *sql.DB) (string, error) {
	views, order, err := buildRowViews(db)
	if err != nil {
		return "", err
	}
	// req#21：同型号去重（与首页同一口径，避免同卡重复散点）
	views, order = dedupViews(views, order)
	now := time.Now()
	nowTs := now.Unix()
	lastTs := lastRoundAt(db)

	// 收集所有点（按采集轮次桶聚合，每型号每轮一个点；携带涨跌/极值/详情链接用于交互）
	type sp struct {
		cat                 string
		ts                  int64
		price, prev         float64
		hasPrev             bool
		hi, lo              float64
		name, key           string
	}
	var pts []sp
	t0, t1 := nowTs, int64(0)
	lo, hi := 1e18, 0.0
	perCat := map[string]int{}
	for _, k := range order {
		v := views[k]
		s := v.S
		bk := bucketRound(s.Points, scatterSec)
		for j, p := range bk {
			if p.Price <= 0 {
				continue
			}
			e := sp{cat: s.Cat, ts: p.Ts, price: p.Price, hi: v.M.MaxP, lo: v.M.MinP,
				name: s.NameSpec(), key: v.DetailK}
			if j > 0 && bk[j-1].Price > 0 {
				e.prev, e.hasPrev = bk[j-1].Price, true
			}
			pts = append(pts, e)
			if p.Ts < t0 {
				t0 = p.Ts
			}
			if p.Ts > t1 {
				t1 = p.Ts
			}
			if p.Price < lo {
				lo = p.Price
			}
			if p.Price > hi {
				hi = p.Price
			}
			perCat[s.Cat]++
		}
	}
	if len(pts) < 2 {
		// 数据不足也渲染一个占位
		return scatterHTML(0, 0, 0, perCat, ``, `数据积累中 · 需要更多型号与时间点的数据才能绘制离散图`, lastTs, nowTs), nil
	}

	// 画布
	const W, H = 960, 520
	const padL, padR, padT, padB = 62, 20, 28, 46

	// Y 轴：对数刻度，从 log10(lo) 向下取整到 log10(hi) 向上取整，留 10% 余量
	// 价格跨度大（内存 ¥300 ~ 显卡 ¥8000），必须用对数刻度
	logLo := math.Log10(lo)
	logHi := math.Log10(hi)
	span := logHi - logLo
	if span < 0.01 {
		span = 0.01
	}
	logLo -= span * 0.06
	logHi += span * 0.06

	ypos := func(price float64) float64 {
		lp := math.Log10(price)
		return padT + (logHi-lp)/(logHi-logLo)*float64(H-padT-padB)
	}
	xpos := func(ts int64) float64 {
		tt := float64(t1 - t0)
		if tt <= 0 {
			tt = 1
		}
		return padL + float64(ts-t0)/tt*float64(W-padL-padR)
	}

	var sb strings.Builder
	// 服务端静态网格包进 <g class="grid-layer">：zoomJS.renderGrid() 复用它并整体替换 innerHTML，
	// 否则服务端网格不会被清掉，会叠出两套刻度（2026-09-14 修的「两个网格重叠」）。
	// 无 JS 时这段静态网格仍是有效占位，属渐进增强。
	sb.WriteString(`<g class="grid-layer">`)
	// 网格 + Y 轴刻度（对数刻度：200/300/500/1000/2000/5000/10000/20000）
	ytick := []float64{200, 300, 500, 1000, 2000, 3000, 5000, 10000, 20000, 50000, 100000}
	for _, tv := range ytick {
		if tv < lo/1.5 || tv > hi*1.5 {
			continue
		}
		yy := ypos(tv)
		sb.WriteString(fmt.Sprintf(`<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#21262d" stroke-width="1"/>`,
			padL, yy, W-padR, yy))
		sb.WriteString(fmt.Sprintf(`<text x="%d" y="%.1f" fill="#8b949e" font-size="11" text-anchor="end">%s</text>`,
			padL-8, yy+4, tho(tv)))
	}
	// X 轴刻度：与 6 小时采集节奏对齐——每个刻度是一个采集轮次（天然 6 小时间隔）
	tsSet := map[int64]bool{}
	for _, p := range pts {
		tsSet[p.ts] = true
	}
	var uniqTs []int64
	for ts := range tsSet {
		uniqTs = append(uniqTs, ts)
	}
	sort.Slice(uniqTs, func(i, j int) bool { return uniqTs[i] < uniqTs[j] })
	gridEvery := 1
	if len(uniqTs) > 30 {
		gridEvery = (len(uniqTs) + 29) / 30
	}
	labelEvery := 1
	if len(uniqTs) > 12 {
		labelEvery = (len(uniqTs) + 11) / 12
	}
	for i, ts := range uniqTs {
		if i%gridEvery != 0 && i != len(uniqTs)-1 {
			continue
		}
		xx := xpos(ts)
		sb.WriteString(fmt.Sprintf(`<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="#21262d" stroke-width="1"/>`,
			xx, padT, xx, H-padB))
		if i%labelEvery == 0 || i == len(uniqTs)-1 {
			sb.WriteString(fmt.Sprintf(`<text x="%.1f" y="%d" fill="#8b949e" font-size="11" text-anchor="middle">%s</text>`,
				xx, H-padB+18, time.Unix(ts, 0).Format("01-02 15:04")))
		}
	}
	// 关闭 grid-layer（静态网格到此为止；边框/散点在图层之外）
	sb.WriteString(`</g>`)
	// 边框
	sb.WriteString(fmt.Sprintf(`<rect x="%d" y="%d" width="%d" height="%d" fill="none" stroke="#30363d" stroke-width="1"/>`,
		padL, padT, W-padL-padR, H-padT-padB))

	// 散点（hover 显示涨跌/现价/历史最高最低；点击跳转型号详情页）
	for _, p := range pts {
		c := catColorOf(p.cat)
		xx := xpos(p.ts)
		yy := ypos(p.price)
		chg := `<span style="color:#8b949e">—</span>`
		if p.hasPrev {
			d := p.price - p.prev
			if math.Abs(d) >= 0.5 {
				cc, sign := upC, "+"
				if d < 0 {
					cc, sign = downC, ""
				}
				chg = fmt.Sprintf(`<span style="color:%s">%s%s元</span>`, cc, sign, tho(d))
			} else {
				chg = `<span style="color:#8b949e">持平</span>`
			}
		}
		tip := fmt.Sprintf(`<b>%s</b><br>%s · %s<br>当前价 <b>%s</b> · 较前点 %s<br>`+
			`历史最高 <span style="color:%s">%s</span> · 历史最低 <span style="color:%s">%s</span><br>`+
			`<span style="color:#58a6ff">点击查看型号走势详情</span>`,
			html.EscapeString(p.name), p.cat, time.Unix(p.ts, 0).Format("01-02 15:04"),
			money(p.price), chg, upC, money(p.hi), downC, money(p.lo))
		title := fmt.Sprintf("%s · %s", p.name, money(p.price))
		sb.WriteString(fmt.Sprintf(`<circle class="spt" cx="%.1f" cy="%.1f" r="3.2" fill="%s" fill-opacity="0.72" stroke="%s" stroke-opacity="0.35" stroke-width="0.5" data-ts="%d" data-price="%.2f" data-cat="%s" data-tip="%s" data-go="/detail?k=%s"><title>%s</title></circle>`,
			xx, yy, c, c, p.ts, p.price, p.cat, html.EscapeString(tip), p.key, html.EscapeString(title)))
	}

	// 图例（品类）
	catOrder := []string{"显卡", "处理器"}
	var legend strings.Builder
	for _, c := range catOrder {
		if perCat[c] == 0 {
			continue
		}
		legend.WriteString(fmt.Sprintf(`<span><i style="background:%s"></i>%s（%d 点）</span>`,
			catColorOf(c), c, perCat[c]))
	}

	svg := `<svg class="chart" viewBox="0 0 ` + strconv.Itoa(W) + ` ` + strconv.Itoa(H) + `" data-w="` + strconv.Itoa(W) + `" data-h="` + strconv.Itoa(H) + `" data-pad-l="` + strconv.Itoa(padL) + `" data-pad-r="` + strconv.Itoa(padR) + `" data-pad-t="` + strconv.Itoa(padT) + `" data-pad-b="` + strconv.Itoa(padB) + `" style="width:100%;height:auto" xmlns="http://www.w3.org/2000/svg"><g class="chart-zoom" data-mode="scatter">` +
		sb.String() + `</g></svg>`

	// 统计条
	var stats strings.Builder
	totalModels := len(order)
	for _, c := range catOrder {
		if perCat[c] == 0 {
			continue
		}
		stats.WriteString(fmt.Sprintf(`<span class="chip" style="color:%s">%s</span>`, catColorOf(c), c))
	}

	return scatterHTML(totalModels, len(pts), lo, perCat, svg, "", lastTs, nowTs), nil
}

func scatterHTML(totalModels, totalPts int, lo float64, perCat map[string]int, svg, placeholder string, lastTs, nowTs int64) string {
	var b strings.Builder
	b.WriteString(`<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">` +
		`<meta name="viewport" content="width=device-width,initial-scale=1">` +
		`<title>整体离散图 · 超米 DIY 硬件行情中心</title><style>` + pageCSS() + scatterCSS() + `</style></head><body><div class="wrap">`)

	b.WriteString(`<div class="dh"><a class="back" href="/">← 返回行情中心</a>` +
		`<h1>整体价格离散图</h1>` +
		`<span class="sub">全站型号 · 价格随时间分布 · 对数刻度</span></div>`)

	b.WriteString(`<div class="scatter-meta">` +
		fmt.Sprintf(`<div class="sc-m"><div class="sc-n">%d</div><div class="sc-t">在采型号</div></div>`, totalModels) +
		fmt.Sprintf(`<div class="sc-m"><div class="sc-n">%d</div><div class="sc-t">价格散点</div></div>`, totalPts) +
		fmt.Sprintf(`<div class="sc-m"><div class="sc-n" style="font-size:15px">%s</div><div class="sc-t">数据更新 · %s</div></div>`,
			fmtStamp(lastTs), humanAgo(lastTs, nowTs)) +
		`</div>`)

	if placeholder != "" {
		b.WriteString(`<div class="chartbox"><div style="padding:60px 0;text-align:center;color:#8b949e">` + placeholder + `</div></div>`)
	} else {
		b.WriteString(`<div class="chartbox scatterbox"><div class="ct"><h3>价格离散分布</h3>` +
			`<span class="note2">横轴=时间（6 小时采集轮次）· 纵轴=价格（对数）· 悬停看涨跌与极值 · 点击进型号详情</span></div>` +
			svg + `</div>`)
	}

	// 品类点统计
	if len(perCat) > 0 {
		b.WriteString(`<div class="legend">`)
		for _, c := range []string{"显卡", "处理器"} {
			if perCat[c] == 0 {
				continue
			}
			b.WriteString(fmt.Sprintf(`<span><i style="background:%s"></i>%s（%d 点）</span>`,
				catColorOf(c), c, perCat[c]))
		}
		b.WriteString(`</div>`)
	}

	b.WriteString(`<div class="ft">超米 DIY 硬件行情中心 · 离散图 · <a href="/" style="color:#58a6ff">返回行情中心</a></div>`)
	b.WriteString(zoomJS + tipJS)
	b.WriteString(`</div></body></html>`)
	return b.String()
}

func scatterCSS() string {
	return `
.scatter-meta{display:flex;gap:14px;margin:16px 0}
.sc-m{border:1px solid ` + lineC + `;border-radius:10px;background:` + pnlBg2 + `;padding:14px 20px;min-width:120px}
.sc-n{font-size:26px;font-weight:800;font-family:Consolas,monospace}
.sc-t{color:#8b949e;font-size:12px}
.scatterbox svg{display:block}
.note2{color:#8b949e;font-size:12px;margin-left:auto}
`
}

// ---------------- 详情页 ----------------

func buildDetail(db *sql.DB, key string) (string, bool, error) {
	views, _, err := buildRowViews(db)
	if err != nil {
		return "", false, err
	}
	v, ok := views[key]
	if !ok {
		return "", false, nil
	}
	s, m := v.S, v.M
	now := time.Now()
	lastTs := lastRoundAt(db)
	_ = lastTs        // 留作后续展示用
	var b strings.Builder
	b.WriteString(`<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">` +
		`<meta name="viewport" content="width=device-width,initial-scale=1">` +
		`<title>` + html.EscapeString(s.NameSpec()) + ` - 行情详情</title>` +
		`<style>` + pageCSS() + `</style></head><body><div class="wrap">`)

	b.WriteString(`<div class="dh"><a class="back" href="/">← 返回行情中心</a>` +
		`<h1>` + html.EscapeString(s.NameSpec()) + `</h1>` +
		`<span class="sub">` + s.Cat + ` · ` + dataBadge(lastTs, now.Unix()) + `</span></div>`)

	// 五指标卡
	posPct := 0.0
	posColor := flatC
	posTxt := "数据积累中"
	if m.Pos >= 0 {
		posPct = m.Pos
		posTxt = fmt.Sprintf("%.0f%%", m.Pos)
		if m.Pos < 35 {
			posColor = downC
			posTxt += " · 低位"
		} else if m.Pos < 75 {
			posTxt += " · 正常"
		} else {
			posColor = upC
			posTxt += " · 高位"
		}
	}
	spread := m.MaxP - m.MinP
	b.WriteString(`<div class="stat5">`)
	b.WriteString(fmt.Sprintf(`<div class="st"><div class="t">当前可信底价</div><div class="v">%s</div>`+
		`<div class="d">样本 %d 轮 · %s</div></div>`, money(m.Cur), m.Samples,
		time.Unix(int64(m.CurTs), 0).Format("01-02 15:04")))
	b.WriteString(fmt.Sprintf(`<div class="st"><div class="t">历史最低</div><div class="v" style="color:%s">%s</div>`+
		`<div class="d">%s</div></div>`, downC, money(m.MinP), time.Unix(m.MinTs, 0).Format("2006-01-02")))
	b.WriteString(fmt.Sprintf(`<div class="st"><div class="t">历史最高</div><div class="v" style="color:%s">%s</div>`+
		`<div class="d">%s</div></div>`, upC, money(m.MaxP), time.Unix(m.MaxTs, 0).Format("2006-01-02")))
	b.WriteString(fmt.Sprintf(`<div class="st"><div class="t">平台报价差</div><div class="v">%s</div>`+
		`<div class="d">%d 个平台口径</div></div>`, money(spread), m.Shops))
	b.WriteString(fmt.Sprintf(`<div class="st"><div class="t">历史价格位置</div><div class="v" style="color:%s">%s</div>`+
		`<div class="pbar"><i style="width:%.0f%%;background:%s"></i></div></div>`,
		posColor, posTxt, posPct, posColor))
	b.WriteString(`</div>`)

	// req#15：单一型号离散图 —— 砍掉原 30/90/全部 三张折线图与 MA 均线，窗口统一 18 个月。
	// 粒度 = 每一轮一个点（不做桶聚合），与下方「逐轮价格明细」表逐行 1:1 对应。
	msPts := s.Points
	msName := s.Name()
	var chartInner string
	if len(msPts) == 0 {
		chartInner = `<div style="padding:40px;text-align:center;color:#8b949e">数据积累中 · 尚无本型号的价格点</div>`
	} else {
		chartInner = scatterSVG(msPts, 960, 340,
			func(v float64) string { return money(v) },
			func(ts int64) string { return time.Unix(ts, 0).Format("01-02 15:04") },
			func(i int, p pt) string {
				chg := `<span style="color:#8b949e">—</span>`
				if i > 0 {
					d := p.Price - msPts[i-1].Price
					if math.Abs(d) >= 0.5 {
						cc, sg := upC, "+"
						if d < 0 {
							cc, sg = downC, ""
						}
						chg = fmt.Sprintf(`<span style="color:%s">%s%s元</span>`, cc, sg, tho(d))
					} else {
						chg = `<span style="color:#8b949e">持平</span>`
					}
				}
				shop := p.Shop
				if strings.TrimSpace(shop) == "" {
					shop = "—"
				}
				return fmt.Sprintf(`<b>%s</b><br>%s<br>到手价 <b>%s</b> · 较前点 %s<br>店铺 %s`,
					html.EscapeString(msName), time.Unix(p.Ts, 0).Format("01-02 15:04"),
					money(p.Price), chg, html.EscapeString(shop))
			})
	}
	b.WriteString(fmt.Sprintf(`<div class="chartbox"><div class="ct"><h3>价格离散分布</h3>`+
		`<span class="note2">每轮采集一个点 · 涨红跌绿 · 窗口 %d 个月 · 悬停看价格与店铺 · 滚轮/双指缩放 · 双击复位</span>`+
		`</div><div class="rngv">`, windowMonths))
	b.WriteString(chartInner)
	b.WriteString(`</div><div class="legend">` +
		`<span><i style="background:` + upC + `"></i>较前点上涨</span>` +
		`<span><i style="background:` + downC + `"></i>较前点下跌</span>` +
		`<span><i style="background:` + flatC + `"></i>持平</span>` +
		`<span><i style="background:` + upC + `"></i>历史最高（描圈）</span>` +
		`<span><i style="background:` + downC + `"></i>历史最低（描圈）</span>` +
		`</div></div>`)

	b.WriteString(zoomJS + tipJS)

	// 逐轮明细
	b.WriteString(`<div class="cat-hd"><h2>逐轮价格明细</h2></div><div class="twrap"><table><thead><tr>` +
		`<th>时间</th><th>到手价</th><th>店铺</th></tr></thead><tbody>`)
	for i := len(s.Points) - 1; i >= 0; i-- {
		p := s.Points[i]
		chg := ""
		if i > 0 && math.Abs(p.Price-s.Points[i-1].Price) >= 0.5 {
			cc, sign := upC, "+"
			if p.Price < s.Points[i-1].Price {
				cc, sign = downC, ""
			}
			chg = fmt.Sprintf(`<small style="color:%s;margin-left:6px">%s%s元</small>`, cc, sign, tho(p.Price-s.Points[i-1].Price))
		}
		b.WriteString(fmt.Sprintf(`<tr><td>%s</td><td class="pr">%s%s</td><td>%s</td></tr>`,
			time.Unix(p.Ts, 0).Format("2006-01-02 15:04"), money(p.Price), chg,
			html.EscapeString(p.Shop)))
	}
	b.WriteString(`</tbody></table></div>`)
	b.WriteString(`<div class="ft">` + html.EscapeString(s.Name()) + ` · 价格明细</div>`)
	b.WriteString(`</div></body></html>`)
	return b.String(), true, nil
}

// ---------------- serve ----------------

type cache struct {
	mu      chan struct{}
	home    string
	ok      bool
	ver     int64
	builtAt time.Time // 最近一次重建时间；用于让 dataBadge 的「X 小时/分钟前」每分钟刷新
}

func newCache() *cache {
	return &cache{mu: make(chan struct{}, 1)}
}

func (c *cache) getHome(db *sql.DB) (string, error) {
	c.mu <- struct{}{}
	defer func() { <-c.mu }()
	var ver int64
	if st, err := os.Stat(verFile); err == nil {
		ver = st.ModTime().UnixNano()
	}
	// version 文件变化 OR 缓存超过 60 秒 → 重建首页。
	// 关键：dataBadge 的「X 小时/分钟前」是构建时刻算的，纯靠 version 失效会让相对时间僵住
	// （req#12 实测：12:55 重建后到 16:29 仍显示「2 小时前」，实际已 3.6h）。
	// 60s 重建保证相对时间误差 ≤1 分钟，又避免每次请求都跑 buildHome。
	if c.ok && c.ver == ver && time.Since(c.builtAt) < 60*time.Second {
		return c.home, nil
	}
	page, err := buildHome(db)
	if err != nil {
		return "", err
	}
	c.home, c.ok, c.ver, c.builtAt = page, true, ver, time.Now()
	log.Printf("[serve] 首页已重建 ver=%d", ver)
	return page, nil
}

func doServe() error {
	db, err := openDB()
	if err != nil {
		return err
	}
	defer db.Close()
	if err := ensureSchema(db); err != nil {
		return fmt.Errorf("schema: %w", err)
	}
	if err := migrateColumns(db); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	_ = newCache() // 行情首页已移除，缓存对象保留占位避免大面积改动
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
	})
	// 网页搜索（req#38）：转发到本地电脑搜索服务（经反向隧道 127.0.0.1:39583）
	mux.HandleFunc("/api/search", func(w http.ResponseWriter, r *http.Request) {
		q := strings.TrimSpace(r.URL.Query().Get("q"))
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		if q == "" {
			w.Write([]byte(`{"code":400,"msg":"缺少 q 参数"}`))
			return
		}
		resp, err := http.Get("http://127.0.0.1:39583/search?q=" + url.QueryEscape(q))
		if err != nil {
			w.Write([]byte(`{"code":5000,"msg":"本地搜索服务未连接（隧道未建立）"}`))
			return
		}
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		w.Write(body)
	})
	mux.Handle("/assets/", http.StripPrefix("/assets/", http.FileServer(http.Dir(assetDir))))
	// 行情已移除：首页直接进社区
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" && r.URL.Path != "/index.html" {
			http.NotFound(w, r)
			return
		}
		http.Redirect(w, r, "/community", http.StatusFound)
	})
	// 行情详情 / 散点图已移除（页面不再提供）

	// ---- 社区模块 ----
	if err := ensureCommunitySchema(db); err != nil {
		log.Printf("[serve] community schema: %v", err)
	}
	if err := ensureAuthSchema(db); err != nil {
		log.Printf("[serve] auth schema: %v", err)
	}
	if err := ensureMailSchema(db); err != nil {
		log.Printf("[serve] mail schema: %v", err)
	}
	log.Printf("[serve] SMTP=%v FEISHU=%v", smtpConfigured(), feishuConfigured())
	// 清理旧的云 GPU 表（改用 Amazon 后不再使用）
	// 旧表清理（统一到 gpu_price）
	_, _ = db.Exec("DROP TABLE IF EXISTS amz_gpu")
	_, _ = db.Exec("DROP TABLE IF EXISTS amz_gpu_history")
	_, _ = db.Exec("DROP TABLE IF EXISTS cloud_gpu")
	_, _ = db.Exec("DROP TABLE IF EXISTS cloud_gpu_history")

	if err := ensureGPUSchema(db); err != nil {
		log.Printf("[serve] cloud_gpu schema: %v", err)
	} else {
		// 行情已移除：不再启动 GPU 采集
		log.Printf("[serve] GPU 采集已停用（行情模块移除）")
	}

	// ---- RSS 资讯（一级菜单 + 二级分类，服务端定时拉取并预翻译）----
	if err := ensureRSSSchema(db); err != nil {
		log.Printf("[serve] rss schema: %v", err)
	} else {
		startRSSLoop(db)
		go backfillTranslate(db)
		log.Printf("[serve] RSS 拉取已启动（5 个分类，每 15 分钟一轮）")
	}

	go hub.run()
	// ---- 天气（Open-Meteo，国外免费无需 key）----
	mux.HandleFunc("/api/weather", func(w http.ResponseWriter, r *http.Request) { handleWeather(db, w, r) })

	// ---- RSS 资讯 ----
	mux.HandleFunc("/api/rss/cats", func(w http.ResponseWriter, r *http.Request) { handleRSSCats(db, w, r) })
	mux.HandleFunc("/api/rss/items", func(w http.ResponseWriter, r *http.Request) { handleRSSItems(db, w, r) })
	mux.HandleFunc("/api/rss/refresh", func(w http.ResponseWriter, r *http.Request) {
		// 手动触发一次拉取（前端下拉强刷用，5 分钟内只允许一次）
		if r.Method != http.MethodPost {
			http.NotFound(w, r)
			return
		}
		if !tryLockRSSManual() {
			writeJSON(w, 429, map[string]string{"error": "刷新太频繁，请稍后再试"})
			return
		}
		go func() {
			n, seen := refreshRSS(db)
			log.Printf("[rss] 手动刷新：新增 %d（抓取 %d）", n, seen)
		}()
		writeJSON(w, 200, map[string]interface{}{"code": 0, "data": "已开始刷新"})
	})

	mux.HandleFunc("/api/community/feed", func(w http.ResponseWriter, r *http.Request) { handleCommunityFeed(db, w, r) })
	mux.HandleFunc("/api/community/posts", func(w http.ResponseWriter, r *http.Request) { handleCommunityCreatePost(db, w, r) })
	mux.HandleFunc("/api/community/post/", func(w http.ResponseWriter, r *http.Request) {
		rest := strings.TrimPrefix(r.URL.Path, "/api/community/post/")
		idStr := rest
		if i := strings.Index(idStr, "/"); i >= 0 {
			idStr = idStr[:i]
		}
		id, e := strconv.ParseInt(idStr, 10, 64)
		if e != nil {
			http.NotFound(w, r)
			return
		}
		handleCommunityPost(db, w, r, id)
	})
	mux.HandleFunc("/api/article/parse", func(w http.ResponseWriter, r *http.Request) { handleArticleParse(w, r) })

	// ---- 解析自测（仅 /tmp 只读）----
	mux.HandleFunc("/api/article/debug", handleArticleDebug)

	// ---- 链接卡片 ----
	mux.HandleFunc("/api/link/preview", handleLinkPreview)

	// ---- 行情 API 已移除（保留函数不挂路由）----

	// ---- 去广告规则库（服务端下发，客户端执行）----
	mux.HandleFunc("/api/adrules", func(w http.ResponseWriter, r *http.Request) { handleAdRules(db, w, r) })
	mux.HandleFunc("/api/adrules/report", handleAdRuleReport)
	mux.HandleFunc("/api/adrules/stats", handleAdRuleStats)

	// ---- 采集端（App 回传）----
	mux.HandleFunc("/api/crawler/targets", func(w http.ResponseWriter, r *http.Request) { handleCrawlerTargets(db, w, r) })

	// ---- 节点监控 ----
	mux.HandleFunc("/api/ping", handlePing)
	mux.HandleFunc("/api/ping/report", handlePingReport)
	mux.HandleFunc("/api/node", func(w http.ResponseWriter, r *http.Request) { handleNodeInfo(db, w, r) })

	// ---- 账号体系 ----
	mux.HandleFunc("/api/auth/register", func(w http.ResponseWriter, r *http.Request) { handleAuthRegister(db, w, r) })
	mux.HandleFunc("/api/auth/login", func(w http.ResponseWriter, r *http.Request) { handleAuthLogin(db, w, r) })
	mux.HandleFunc("/api/auth/logout", func(w http.ResponseWriter, r *http.Request) { handleAuthLogout(db, w, r) })
	mux.HandleFunc("/api/auth/me", func(w http.ResponseWriter, r *http.Request) { handleAuthMe(db, w, r) })
	mux.HandleFunc("/api/auth/profile", func(w http.ResponseWriter, r *http.Request) { handleAuthProfile(db, w, r) })
	mux.HandleFunc("/api/auth/sendcode", func(w http.ResponseWriter, r *http.Request) { handleSendCode(db, w, r) })
	mux.HandleFunc("/api/auth/reset", func(w http.ResponseWriter, r *http.Request) { handleResetPassword(db, w, r) })
	mux.HandleFunc("/api/auth/avatar", func(w http.ResponseWriter, r *http.Request) { handleAvatarUpload(db, w, r) })
	mux.HandleFunc("/api/auth/checkuid", func(w http.ResponseWriter, r *http.Request) { handleCheckUID(db, w, r) })
	mux.HandleFunc("/api/user/uid/", func(w http.ResponseWriter, r *http.Request) {
		rest := strings.TrimPrefix(r.URL.Path, "/api/user/uid/")
		handleUserByUID(db, w, r, strings.SplitN(rest, "/", 2)[0])
	})
	mux.HandleFunc("/api/user/", func(w http.ResponseWriter, r *http.Request) {
		rest := strings.TrimPrefix(r.URL.Path, "/api/user/")
		handleUserProfile(db, w, r, cParseID(strings.SplitN(rest, "/", 2)[0]))
	})
	// ---- 社区增强 ----
	mux.HandleFunc("/api/community/tags", func(w http.ResponseWriter, r *http.Request) { handleCommunityTags(db, w, r) })
	mux.HandleFunc("/api/community/comment/", func(w http.ResponseWriter, r *http.Request) {
		rest := strings.TrimPrefix(r.URL.Path, "/api/community/comment/")
		handleCommunityDeleteComment(db, w, r, cParseID(strings.SplitN(rest, "/", 2)[0]))
	})
	mux.HandleFunc("/api/article/import", func(w http.ResponseWriter, r *http.Request) { handleArticleImport(db, w, r) })
	mux.HandleFunc("/api/community/upload", func(w http.ResponseWriter, r *http.Request) { handleCommunityUpload(w, r) })
	mux.HandleFunc("/ws/community", func(w http.ResponseWriter, r *http.Request) { serveWsCommunity(w, r) })
	// 社区 SPA 静态托管
	communityDir = base + "/site/community"
	mux.Handle("/community/assets/", http.StripPrefix("/community/assets/", http.FileServer(http.Dir(communityDir+"/assets"))))
	mux.Handle("/community/uploads/", http.StripPrefix("/community/uploads/", http.FileServer(http.Dir(communityDir+"/uploads"))))
	mux.HandleFunc("/community", func(w http.ResponseWriter, r *http.Request) {
		http.ServeFile(w, r, communityDir+"/index.html")
	})
	mux.HandleFunc("/community/", func(w http.ResponseWriter, r *http.Request) {
		http.ServeFile(w, r, communityDir+"/index.html")
	})
	// 原生 App 下载
	mux.Handle("/downloads/", http.StripPrefix("/downloads/", http.FileServer(http.Dir(base+"/site/downloads"))))
	mux.HandleFunc("/app", func(w http.ResponseWriter, r *http.Request) { handleAppDownloadPage(w, r) })

	addr := envOr("PORT", listenDef)
	if !strings.Contains(addr, ":") {
		addr = "0.0.0.0:" + addr
	}
	log.Printf("[serve] listening on %s", addr)
	return http.ListenAndServe(addr, mux)
}

// ---------------- main ----------------

func main() {
	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	switch cmd {
	case "import":
		if err := doImport(); err != nil {
			log.Fatalf("[import] FATAL: %v", err)
		}
	case "serve":
		if err := doServe(); err != nil {
			log.Fatalf("[serve] FATAL: %v", err)
		}
	case "version":
		fmt.Println("cmpricesite 2.0 (行情中心)")
		_ = exec.Command
	default:
		fmt.Println("usage: cmpricesite [import|serve|version]")
		os.Exit(2)
	}
}
