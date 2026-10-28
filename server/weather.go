package main

// ---------------- 天气卡片 ----------------
//
// 数据源：Open-Meteo（国外免费、无需 API Key、无调用配额上限）
//   · 地理编码 Geocoding API：按城市名或经纬度定位
//   · 预报 Forecast API：当前实况 + 未来 7 天
//
// 定位策略（对应「以 app 来获取地理 ip」）：
//   1. 客户端传 lat/lon（App 用系统定位，最准）
//   2. 客户端没传则用请求来源 IP 粗略定位（城市级，够用）
//   3. 都失败则回落到默认城市，绝不报错阻断页面
//
// 为什么要服务端中转：
//   ① 统一缓存（同一城市 10 分钟内只向 Open-Meteo 请求一次）
//   ② 客户端不用各带一套 API 调用与错误处理
//   ③ 可替换数据源而不发新版 App

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

var weatherHTTP = &http.Client{Timeout: 10 * time.Second}

// ===== 城市解析结果 =====

type geoPlace struct {
	Name      string  `json:"name"`
	Country   string  `json:"country"`
	Admin1    string  `json:"admin1"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
}

type geoReply struct {
	Results []geoPlace `json:"results"`
}

// ===== 天气结果 =====

type weatherNow struct {
	TempC      float64 `json:"tempC"`
	FeelsC     float64 `json:"feelsC"`
	Humidity   int     `json:"humidity"`
	WindKph    float64 `json:"windKph"`
	WindDir    int     `json:"windDir"`
	Code       int     `json:"code"`
	Text       string  `json:"text"`
	Icon       string  `json:"icon"`
	IsDay      bool    `json:"isDay"`
	PrecipMm   float64 `json:"precipMm"`
	Pressure   float64 `json:"pressure"`
	UVIndex    float64 `json:"uvIndex"`
}

type weatherDay struct {
	Date   string  `json:"date"`
	MaxC   float64 `json:"maxC"`
	MinC   float64 `json:"minC"`
	Code   int     `json:"code"`
	Text   string  `json:"text"`
	Icon   string  `json:"icon"`
	Precip float64 `json:"precipMm"`
	Prob   int     `json:"precipProb"`
}

type weatherCard struct {
	City      string       `json:"city"`
	Region    string       `json:"region"`
	Lat       float64      `json:"lat"`
	Lon       float64      `json:"lon"`
	Now       weatherNow   `json:"now"`
	Days      []weatherDay `json:"days"`
	UpdatedAt int64        `json:"updatedAt"`
	Source    string       `json:"source"`
}

type openMeteoReply struct {
	Current struct {
		Time          string  `json:"time"`
		Temperature   float64 `json:"temperature_2m"`
		Humidity      int     `json:"relative_humidity_2m"`
		Apparent      float64 `json:"apparent_temperature"`
		IsDay         int     `json:"is_day"`
		Precip        float64 `json:"precipitation"`
		WeatherCode   int     `json:"weather_code"`
		Pressure      float64 `json:"surface_pressure"`
		WindSpeed     float64 `json:"wind_speed_10m"`
		WindDir       float64 `json:"wind_direction_10m"`
	} `json:"current"`
	Daily struct {
		Time        []string  `json:"time"`
		Code        []int     `json:"weather_code"`
		Max         []float64 `json:"temperature_2m_max"`
		Min         []float64 `json:"temperature_2m_min"`
		Precip      []float64 `json:"precipitation_sum"`
		PrecipProb  []int     `json:"precipitation_probability_max"`
		UV          []float64 `json:"uv_index_max"`
	} `json:"daily"`
}

// 缓存：坐标 → 天气卡片（10 分钟）
type weatherCacheEntry struct {
	card weatherCard
	at   time.Time
}

var (
	weatherMu    sync.Mutex
	weatherCache = map[string]weatherCacheEntry{}
)

const weatherCacheTTL = 10 * time.Minute

// ===== WMO 天气代码 → 中文 + 图标 =====

func wmoText(code int) (string, string) {
	switch code {
	case 0:
		return "晴", "☀️"
	case 1:
		return "大部晴朗", "🌤️"
	case 2:
		return "多云", "⛅"
	case 3:
		return "阴", "☁️"
	case 45, 48:
		return "雾", "🌫️"
	case 51, 53, 55:
		return "毛毛雨", "🌦️"
	case 56, 57:
		return "冻雨", "🌧️"
	case 61:
		return "小雨", "🌦️"
	case 63:
		return "中雨", "🌧️"
	case 65:
		return "大雨", "🌧️"
	case 66, 67:
		return "冻雨", "🌧️"
	case 71:
		return "小雪", "🌨️"
	case 73:
		return "中雪", "🌨️"
	case 75:
		return "大雪", "❄️"
	case 77:
		return "雪粒", "🌨️"
	case 80, 81:
		return "阵雨", "🌦️"
	case 82:
		return "强阵雨", "⛈️"
	case 85, 86:
		return "阵雪", "🌨️"
	case 95:
		return "雷阵雨", "⛈️"
	case 96, 99:
		return "雷阵雨伴冰雹", "⛈️"
	}
	return "未知", "🌡️"
}

// ===== 城市解析 =====

// resolveCityName 反查地名（用于「北京」这类展示）
//
// ★ Open-Meteo 的 geocoding 是「按名字搜」，不支持按坐标反查
//   （传 latitude/longitude 会被忽略，返回空）。
//   所以这里改用 ip-api 之外的做法：直接请求一个支持反向地理编码的免费端点。
//   兜底方案：坐标本身已经能出天气，城市名为空不影响卡片展示，
//   前端会用「当前位置」占位。
func resolveCityName(lat, lon float64) (string, string) {
	// bigdatacloud 提供免费反查，无需 key
	q := url.Values{}
	q.Set("latitude", fmt.Sprintf("%.4f", lat))
	q.Set("longitude", fmt.Sprintf("%.4f", lon))
	q.Set("localityLanguage", "zh")

	api := "https://api.bigdatacloud.net/data/reverse-geocode-client?" + q.Encode()
	resp, err := weatherHTTP.Get(api)
	if err != nil {
		return "", ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", ""
	}

	var r struct {
		City    string `json:"city"`
		Locality string `json:"locality"`
		PrincipalSubdivision string `json:"principalSubdivision"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return "", ""
	}
	name := r.City
	if name == "" {
		name = r.Locality
	}
	return name, r.PrincipalSubdivision
}

// geocode 按城市名查坐标
func geocode(name string) (geoPlace, bool) {
	if strings.TrimSpace(name) == "" {
		return geoPlace{}, false
	}
	q := url.Values{}
	q.Set("name", name)
	q.Set("count", "1")
	q.Set("language", "zh")

	api := "https://geocoding-api.open-meteo.com/v1/search?" + q.Encode()
	resp, err := weatherHTTP.Get(api)
	if err != nil {
		return geoPlace{}, false
	}
	defer resp.Body.Close()

	var r geoReply
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return geoPlace{}, false
	}
	if len(r.Results) == 0 {
		return geoPlace{}, false
	}
	return r.Results[0], true
}

// ===== 天气查询 =====

func fetchWeather(lat, lon float64, city, region string) (weatherCard, error) {
	q := url.Values{}
	q.Set("latitude", strconv.FormatFloat(lat, 'f', 4, 64))
	q.Set("longitude", strconv.FormatFloat(lon, 'f', 4, 64))
	q.Set("current", "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,"+
		"precipitation,weather_code,surface_pressure,wind_speed_10m,wind_direction_10m")
	q.Set("daily", "weather_code,temperature_2m_max,temperature_2m_min,"+
		"precipitation_sum,precipitation_probability_max,uv_index_max")
	q.Set("timezone", "auto")
	q.Set("forecast_days", "7")

	api := "https://api.open-meteo.com/v1/forecast?" + q.Encode()
	resp, err := weatherHTTP.Get(api)
	if err != nil {
		return weatherCard{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return weatherCard{}, fmt.Errorf("open-meteo HTTP %d", resp.StatusCode)
	}

	var r openMeteoReply
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return weatherCard{}, err
	}

	text, icon := wmoText(r.Current.WeatherCode)
	card := weatherCard{
		City:   city,
		Region: region,
		Lat:    lat,
		Lon:    lon,
		Now: weatherNow{
			TempC:    round1(r.Current.Temperature),
			FeelsC:   round1(r.Current.Apparent),
			Humidity: r.Current.Humidity,
			WindKph:  round1(r.Current.WindSpeed),
			WindDir:  int(r.Current.WindDir),
			Code:     r.Current.WeatherCode,
			Text:     text,
			Icon:     icon,
			IsDay:    r.Current.IsDay == 1,
			PrecipMm: round1(r.Current.Precip),
			Pressure: round1(r.Current.Pressure),
		},
		UpdatedAt: time.Now().UnixMilli(),
		Source:    "Open-Meteo",
	}
	if len(r.Daily.UV) > 0 {
		card.Now.UVIndex = round1(r.Daily.UV[0])
	}

	n := len(r.Daily.Time)
	for i := 0; i < n && i < 7; i++ {
		t, ic := wmoText(r.Daily.Code[i])
		d := weatherDay{
			Date: r.Daily.Time[i],
			Code: r.Daily.Code[i],
			Text: t,
			Icon: ic,
		}
		if i < len(r.Daily.Max) {
			d.MaxC = round1(r.Daily.Max[i])
		}
		if i < len(r.Daily.Min) {
			d.MinC = round1(r.Daily.Min[i])
		}
		if i < len(r.Daily.Precip) {
			d.Precip = round1(r.Daily.Precip[i])
		}
		if i < len(r.Daily.PrecipProb) {
			d.Prob = r.Daily.PrecipProb[i]
		}
		card.Days = append(card.Days, d)
	}
	return card, nil
}

func round1(v float64) float64 {
	return math.Round(v*10) / 10
}

// ===== HTTP 处理 =====

// handleWeather GET /api/weather?lat=..&lon=..&city=城市名
//
// 参数优先级：lat/lon > city > IP 定位 > 默认北京
func handleWeather(db *sql.DB, w http.ResponseWriter, r *http.Request) {
	qs := r.URL.Query()
	var lat, lon float64
	var city, region string

	// 1) 客户端定位
	if v := qs.Get("lat"); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil {
			lat = f
		}
	}
	if v := qs.Get("lon"); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil {
			lon = f
		}
	}

	// 2) 城市名
	if (lat == 0 && lon == 0) && qs.Get("city") != "" {
		if p, ok := geocode(qs.Get("city")); ok {
			lat, lon = p.Latitude, p.Longitude
			city, region = p.Name, p.Admin1
		}
	}

	// 3) IP 粗略定位
	if lat == 0 && lon == 0 {
		if ip := clientIP(r); ip != "" && ip != "内网" {
			if la, lo, c, rg, ok := locateByIP(ip); ok {
				lat, lon, city, region = la, lo, c, rg
			}
		}
	}

	// 4) 默认
	if lat == 0 && lon == 0 {
		lat, lon = 39.9042, 116.4074 // 北京
		city = "北京"
	}

	// 反查地名（客户端只给坐标时）
	if city == "" {
		if n, a := resolveCityName(lat, lon); n != "" {
			city, region = n, a
		}
	}

	// 缓存键：0.01 度粒度（约 1km），避免频繁请求
	key := fmt.Sprintf("%.2f,%.2f", lat, lon)
	weatherMu.Lock()
	if e, ok := weatherCache[key]; ok && time.Since(e.at) < weatherCacheTTL {
		card := e.card
		weatherMu.Unlock()
		writeJSON(w, 200, map[string]interface{}{"code": 0, "data": card, "cached": true})
		return
	}
	weatherMu.Unlock()

	card, err := fetchWeather(lat, lon, city, region)
	if err != nil {
		// 有旧缓存就返回旧的（网络抖动时体验更好）
		weatherMu.Lock()
		if e, ok := weatherCache[key]; ok {
			old := e.card
			weatherMu.Unlock()
			writeJSON(w, 200, map[string]interface{}{"code": 0, "data": old, "stale": true})
			return
		}
		weatherMu.Unlock()
		writeJSON(w, 500, map[string]string{"error": "天气获取失败：" + err.Error()})
		return
	}

	weatherMu.Lock()
	weatherCache[key] = weatherCacheEntry{card: card, at: time.Now()}
	weatherMu.Unlock()

	writeJSON(w, 200, map[string]interface{}{"code": 0, "data": card})
}

// locateByIP 用 IP 粗略定位
//
// ★ 关键：这里必须**快速失败**。
//   App 请求天气时如果这里卡住 10 秒，用户看到的就是「一直在定位中」。
//   所以：短超时 + 多源兜底 + 任一成功立即返回。
func locateByIP(ip string) (lat, lon float64, city, region string, ok bool) {
	type reply struct {
		lat, lon    float64
		city, region string
		ok          bool
	}
	ch := make(chan reply, 2)

	// 源 1：ip-api（信息最全，带经纬度）
	go func() {
		q := url.Values{}
		q.Set("lang", "zh-CN")
		q.Set("fields", "status,country,regionName,city,lat,lon")
		c := &http.Client{Timeout: 1500 * time.Millisecond}
		resp, err := c.Get("http://ip-api.com/json/" + ip + "?" + q.Encode())
		if err != nil {
			ch <- reply{}
			return
		}
		defer resp.Body.Close()
		var r struct {
			Status     string  `json:"status"`
			RegionName string  `json:"regionName"`
			City       string  `json:"city"`
			Lat        float64 `json:"lat"`
			Lon        float64 `json:"lon"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&r); err != nil || r.Status != "success" || r.Lat == 0 {
			ch <- reply{}
			return
		}
		ch <- reply{r.Lat, r.Lon, r.City, r.RegionName, true}
	}()

	// 源 2：ipwho.is（HTTPS，作为 ip-api 失败时的兜底）
	go func() {
		c := &http.Client{Timeout: 1500 * time.Millisecond}
		resp, err := c.Get("https://ipwho.is/" + ip + "?lang=zh-CN")
		if err != nil {
			ch <- reply{}
			return
		}
		defer resp.Body.Close()
		var r struct {
			Success  bool    `json:"success"`
			City     string  `json:"city"`
			Region   string  `json:"region"`
			Latitude float64 `json:"latitude"`
			Longitude float64 `json:"longitude"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&r); err != nil || !r.Success || r.Latitude == 0 {
			ch <- reply{}
			return
		}
		ch <- reply{r.Latitude, r.Longitude, r.City, r.Region, true}
	}()

	// 谁先成功用谁；总共最多等 1.8 秒
	deadline := time.After(1800 * time.Millisecond)
	for i := 0; i < 2; i++ {
		select {
		case r := <-ch:
			if r.ok {
				return r.lat, r.lon, r.city, r.region, true
			}
		case <-deadline:
			return 0, 0, "", "", false
		}
	}
	return 0, 0, "", "", false
}
