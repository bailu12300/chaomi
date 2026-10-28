package main

// ---------------- 本地离线翻译引擎 ----------------
//
// 背景：Google 免费端点被限流（返回 Sorry 页面），MyMemory 免费额度一天就用完。
// 依赖第三方在线翻译 = 翻译随时挂掉，用户看到的还是英文。
//
// 方案：**内置词典 + 最长匹配本地翻译**。
//   · 零网络依赖，永不失败，微秒级响应
//   · 覆盖资讯标题/摘要的高频词（科技/AI/能源/国际 领域）
//   · 不认识的专有名词原样保留，绝不瞎翻
//
// 质量取舍：这不是「信达雅」的机器翻译，而是**可读的要点翻译**。
// 对「看标题知道大概讲什么」这个目标，比随时挂掉的在线 API 更可靠。

import (
	"database/sql"
	"sort"
	"strings"
)

// 词典：英文词/短语 → 中文
var dictRaw = map[string]string{
	// ---- 通用高频 ----
	"of": "的", "to": "到", "in": "在", "on": "关于",
	"the": "", "a": "", "an": "", "is": "", "are": "", "was": "", "were": "",
	"for": "为", "with": "与", "and": "和", "or": "或",
	"be": "是", "been": "已经", "will": "将",
	"would": "会", "can": "能", "could": "可能", "should": "应该", "may": "可能",
	"has": "有", "have": "有", "had": "曾有", "do": "做", "does": "做", "did": "做了",
	"not": "不", "no": "无", "never": "从不", "more": "更多", "most": "最",
	"new": "新", "first": "首个", "last": "最后", "next": "下一个", "latest": "最新",
	"how": "如何", "why": "为何", "what": "什么", "when": "何时", "where": "何处",
	"who": "谁", "this": "这", "that": "那", "it": "它", "its": "它的",
	"their": "他们的", "our": "我们的", "your": "你的",
	"up": "上升", "down": "下降", "out": "出", "over": "超过", "under": "低于",
	"after": "之后", "before": "之前", "now": "现在", "today": "今天",
	"year": "年", "month": "月", "week": "周", "day": "天",
	"time": "时间", "way": "方式",

	// ---- 科技 / AI ----
	"artificial intelligence": "人工智能", "machine learning": "机器学习",
	"deep learning": "深度学习", "neural network": "神经网络",
	"data center": "数据中心", "open source": "开源",
	"ai": "AI", "model": "模型", "models": "模型", "training": "训练",
	"trained": "训练", "dataset": "数据集", "algorithm": "算法", "algorithms": "算法",
	"compute": "算力", "gpu": "GPU", "chip": "芯片", "chips": "芯片",
	"semiconductor": "半导体", "processor": "处理器", "cloud": "云",
	"server": "服务器", "software": "软件", "hardware": "硬件",
	"app": "应用", "apps": "应用", "platform": "平台",
	"launch": "发布", "launches": "发布", "launched": "发布了",
	"release": "发布", "released": "已发布", "update": "更新",
	"upgrade": "升级", "version": "版本", "feature": "功能", "features": "功能",
	"support": "支持", "supports": "支持", "user": "用户", "users": "用户",
	"developer": "开发者", "developers": "开发者",
	"openai": "OpenAI", "google": "谷歌", "microsoft": "微软",
	"apple": "苹果", "meta": "Meta", "amazon": "亚马逊", "nvidia": "英伟达",
	"anthropic": "Anthropic", "gemini": "Gemini", "chatgpt": "ChatGPT",
	"llm": "大模型", "llms": "大模型", "agent": "智能体", "agents": "智能体",
	"reasoning": "推理", "benchmark": "基准测试", "performance": "性能",
	"faster": "更快", "fast": "快速", "slow": "缓慢", "better": "更好",
	"best": "最佳", "powerful": "强大", "smart": "智能", "efficient": "高效",
	"research": "研究", "researchers": "研究者", "paper": "论文", "study": "研究",
	"robot": "机器人", "robotics": "机器人技术", "autonomous": "自主",
	"self-driving": "自动驾驶", "generative": "生成式", "generation": "生成",
	"image": "图像", "images": "图像", "video": "视频", "text": "文本",
	"voice": "语音", "speech": "语音", "language": "语言", "translation": "翻译",
	"chatbot": "聊天机器人", "chat": "对话", "search": "搜索", "browser": "浏览器",
	"security": "安全", "privacy": "隐私", "hack": "入侵", "hacked": "遭入侵",
	"breach": "泄露", "vulnerability": "漏洞", "bug": "缺陷",
	"fix": "修复", "fixed": "已修复", "issue": "问题", "issues": "问题",
	"problem": "问题", "frontier": "前沿", "next frontier": "下一个前沿",

	// ---- 数码 / 消费电子 ----
	"phone": "手机", "smartphone": "智能手机", "iphone": "iPhone",
	"android": "安卓", "tablet": "平板", "laptop": "笔记本", "computer": "电脑",
	"display": "显示屏", "screen": "屏幕", "camera": "相机", "battery": "电池",
	"charging": "充电", "charger": "充电器", "wireless": "无线", "bluetooth": "蓝牙",
	"headphone": "耳机", "headphones": "耳机", "earbuds": "耳机", "speaker": "音箱",
	"watch": "手表", "wearable": "可穿戴", "device": "设备", "devices": "设备",
	"review": "评测", "reviews": "评测", "test": "测试", "tested": "测试",
	"price": "价格", "prices": "价格", "pricing": "定价", "cost": "成本",
	"cheap": "便宜", "expensive": "昂贵", "sale": "促销", "deal": "优惠",
	"design": "设计", "thin": "轻薄", "light": "轻", "heavy": "重",
	"size": "尺寸", "storage": "存储", "memory": "内存", "ram": "内存",

	// ---- 能源 ----
	"clean energy": "清洁能源", "natural gas": "天然气",
	"battery storage": "电池储能", "electric vehicle": "电动汽车",
	"energy": "能源", "power": "电力", "electricity": "电力", "electric": "电动",
	"solar": "太阳能", "wind": "风能", "nuclear": "核能", "coal": "煤炭",
	"oil": "石油", "gas": "天然气", "fuel": "燃料",
	"renewable": "可再生", "renewables": "可再生能源",
	"carbon": "碳", "emission": "排放", "emissions": "排放", "climate": "气候",
	"grid": "电网", "hydrogen": "氢能", "ev": "电动车", "evs": "电动车",
	"megawatt": "兆瓦", "gigawatt": "吉瓦", "kilowatt": "千瓦", "capacity": "容量",
	"plant": "电厂", "investment": "投资", "invest": "投资",
	"billion": "十亿", "million": "百万", "trillion": "万亿",

	// ---- 国际 / 新闻 ----
	"at least": "至少", "more than": "超过", "less than": "低于",
	"as well as": "以及", "because of": "由于", "due to": "由于",
	"instead of": "而非", "in order to": "为了", "according to": "根据",
	"such as": "例如", "expected to": "预计", "is set to": "将",
	"high-speed": "高速", "spain": "西班牙", "spanish": "西班牙",
	"china": "中国", "chinese": "中国", "america": "美国", "american": "美国",
	"europe": "欧洲", "european": "欧洲", "japan": "日本", "japanese": "日本",
	"korea": "韩国", "russia": "俄罗斯", "ukraine": "乌克兰", "india": "印度",
	"britain": "英国", "france": "法国", "germany": "德国", "israel": "以色列",
	"iran": "伊朗", "turkey": "土耳其", "brazil": "巴西", "mexico": "墨西哥",
	"world": "世界", "global": "全球", "international": "国际", "country": "国家",
	"countries": "国家", "government": "政府", "president": "总统",
	"minister": "部长", "official": "官员", "officials": "官员",
	"leader": "领导人", "leaders": "领导人",
	"war": "战争", "peace": "和平", "conflict": "冲突", "attack": "袭击",
	"killed": "死亡", "dead": "死亡", "death": "死亡", "injured": "受伤",
	"crash": "事故", "fire": "火灾", "flood": "洪水", "earthquake": "地震",
	"storm": "风暴", "disaster": "灾难", "crisis": "危机",
	"economy": "经济", "economic": "经济", "market": "市场", "markets": "市场",
	"trade": "贸易", "tariff": "关税", "tariffs": "关税", "inflation": "通胀",
	"growth": "增长", "recession": "衰退", "bank": "银行", "stock": "股市",
	"report": "报道", "reports": "报道", "reported": "报道称", "news": "新闻",
	"announced": "宣布", "announce": "宣布", "said": "表示", "says": "表示",
	"according": "据", "officially": "正式", "plan": "计划", "plans": "计划",
	"agreement": "协议", "talks": "会谈", "meeting": "会议",
	"election": "选举", "vote": "投票", "law": "法律", "policy": "政策",
	"rule": "规定", "rules": "规定", "ban": "禁令", "banned": "禁止",
	"train": "列车", "airport": "机场", "flight": "航班",
	"hospital": "医院", "school": "学校", "city": "城市", "region": "地区",
	"people": "人们", "public": "公众", "group": "团体", "company": "公司",
	"companies": "公司", "business": "商业", "industry": "产业", "sector": "行业",
	"live": "直播", "breaking": "突发", "exclusive": "独家", "opinion": "观点",
	"analysis": "分析", "explainer": "解读",

	// ---- 补充：常见动词/名词（提高覆盖率） ----
	"future": "未来", "firm": "公司", "org chart": "组织架构", "beneath": "之下",
	"beside": "旁边", "between": "之间", "might": "可能", "enter": "进入",
	"make": "做", "made": "做了", "making": "正在做", "get": "获得", "got": "获得",
	"give": "提供", "given": "给予", "take": "采取", "took": "采取了",
	"come": "来", "came": "来了", "go": "去", "went": "去了", "see": "看到",
	"seen": "已见", "look": "看", "find": "发现", "found": "发现",
	"know": "知道", "known": "已知", "think": "认为", "thought": "认为",
	"want": "想要", "need": "需要", "needs": "需要", "use": "使用",
	"used": "使用", "using": "使用", "help": "帮助", "helps": "帮助",
	"start": "开始", "started": "开始", "begin": "开始", "end": "结束",
	"stop": "停止", "continue": "继续", "grow": "增长", "grew": "增长了",
	"rise": "上涨", "rose": "上涨", "fall": "下跌", "fell": "下跌",
	"add": "增加", "added": "增加了", "remove": "移除", "cut": "削减",
	"build": "构建", "built": "构建了", "create": "创建", "created": "创建了",
	"change": "改变", "changed": "改变", "improve": "改进", "improved": "改进",
	"allow": "允许", "enable": "启用", "enabled": "已启用", "offer": "提供",
	"include": "包括", "includes": "包括", "including": "包括",
	"become": "成为", "became": "成为了", "remain": "保持",
	"call": "称为", "called": "称为", "name": "名称", "named": "名为",
	"show": "显示", "shows": "显示", "shown": "显示", "reveal": "揭示",
	"warn": "警告", "warns": "警告", "claim": "声称", "claims": "声称",
	"expect": "预计", "expects": "预计", "face": "面临", "faces": "面临",
	"hit": "达到", "reach": "达到", "reached": "达到", "top": "最高",
	"major": "重大", "minor": "轻微", "key": "关键", "main": "主要",
	"big": "大", "large": "大型", "small": "小型", "high": "高", "low": "低",
	"long": "长", "short": "短", "early": "早期", "late": "后期",
	"full": "完整", "free": "免费", "paid": "付费", "open": "开放",
	"private": "私有",
	"real": "真实", "fake": "虚假", "true": "真实", "false": "虚假",
	"only": "仅", "also": "也", "still": "仍", "even": "甚至",
	"just": "刚刚", "very": "非常", "much": "很多", "many": "许多",
	"all": "全部", "both": "两者", "each": "每个", "every": "每个",
	"some": "一些", "any": "任何", "other": "其他", "another": "另一个",
	"same": "相同", "different": "不同", "such": "此类",
	"about": "关于", "around": "约", "across": "跨越", "through": "通过",
	"during": "期间", "without": "没有", "within": "之内", "against": "反对",
	"among": "之中", "toward": "朝向", "upon": "在…之上",
	"monday": "周一", "tuesday": "周二", "wednesday": "周三",
	"thursday": "周四", "friday": "周五", "saturday": "周六", "sunday": "周日",
	"january": "1月", "february": "2月", "march": "3月", "april": "4月",
	"june": "6月", "july": "7月", "august": "8月",
	"september": "9月", "october": "10月", "november": "11月", "december": "12月",
	"yesterday": "昨天", "tomorrow": "明天",
	"quarter": "季度", "annual": "年度", "monthly": "月度",
	"percent": "%", "percentage": "百分比", "rate": "率", "ratio": "比率",
	"number": "数量", "numbers": "数量", "amount": "金额", "total": "总计",
	"increase": "增长", "decrease": "下降", "decline": "下滑", "surge": "激增",
	"drop": "下降", "jump": "跃升", "record": "创纪录",
	"peak": "峰值", "level": "水平", "target": "目标",
	"goal": "目标", "result": "结果", "results": "结果", "data": "数据",
	"information": "信息", "details": "细节", "detail": "细节",
	"example": "示例", "case": "案例", "cases": "案例", "fact": "事实",
	"reason": "原因", "cause": "原因", "effect": "影响", "impact": "影响",
	"risk": "风险", "risks": "风险", "threat": "威胁", "opportunity": "机会",
	"challenge": "挑战", "solution": "解决方案", "solutions": "解决方案",
	"option": "选项", "options": "选项", "choice": "选择", "decision": "决定",
	"step": "步骤", "steps": "步骤", "process": "流程", "system": "系统",
	"systems": "系统", "network": "网络", "networks": "网络",
	"internet": "互联网", "web": "网页", "site": "网站", "website": "网站",
	"page": "页面", "content": "内容", "post": "帖子", "article": "文章",
	"story": "报道", "stories": "报道", "blog": "博客", "channel": "频道",
	"media": "媒体", "press": "媒体", "journal": "期刊", "journalist": "记者",
	"interview": "采访", "statement": "声明", "comment": "评论",
	"comments": "评论", "response": "回应", "reply": "回复",
	"question": "问题", "answer": "答案", "idea": "想法", "ideas": "想法",
	"project": "项目", "projects": "项目", "product": "产品",
	"products": "产品", "service": "服务", "services": "服务",
	"customer": "客户", "customers": "客户", "client": "客户",
	"partner": "合作伙伴", "team": "团队", "staff": "员工", "worker": "工人",
	"workers": "工人", "job": "工作", "jobs": "就业", "work": "工作",
	"office": "办公室", "home": "家", "house": "房屋", "building": "建筑",
	"road": "道路", "street": "街道", "area": "区域", "place": "地点",
	"point": "要点", "points": "要点", "part": "部分", "parts": "部分",
	"side": "方面", "sides": "方面", "form": "形式", "type": "类型",
	"types": "类型", "kind": "种类", "list": "列表", "item": "条目",
	"items": "条目", "entry": "条目", "unit": "单位", "units": "单位",
}

var dictKeys []string
var dictOnce bool

func initDict() {
	if dictOnce {
		return
	}
	for k := range dictRaw {
		dictKeys = append(dictKeys, k)
	}
	// 长词优先：保证 "artificial intelligence" 命中在 "intelligence" 之前
	sort.Slice(dictKeys, func(i, j int) bool {
		return len(dictKeys[i]) > len(dictKeys[j])
	})
	dictOnce = true
}

// translateByDict 最长匹配词典翻译。未命中的整词原样保留。
func translateByDict(text string) string {
	if text == "" {
		return ""
	}
	initDict()

	lower := strings.ToLower(text)
	n := len(lower)
	var sb strings.Builder
	sb.Grow(len(text) * 2)

	i := 0
	for i < n {
		ch := lower[i]
		if !isLetterByte(ch) {
			sb.WriteByte(text[i])
			i++
			continue
		}

		// 找从 i 开始的最长命中
		hit := ""
		for _, k := range dictKeys {
			if len(k) == 0 || i+len(k) > n {
				continue
			}
			if lower[i:i+len(k)] != k {
				continue
			}
			if !isWordBoundary(lower, i, i+len(k)) {
				continue
			}
			hit = k
			break // dictKeys 已按长度倒序
		}

		if hit != "" {
			sb.WriteString(dictRaw[hit])
			i += len(hit)
		} else {
			// 未命中：整词原样输出
			j := i
			for j < n && isLetterByte(lower[j]) {
				j++
			}
			sb.WriteString(text[i:j])
			i = j
		}
	}
	return cleanupZh(sb.String())
}

// 单词边界：前后都不是字母数字
func isWordBoundary(s string, start, end int) bool {
	if start > 0 && isAlphaNum(s[start-1]) {
		return false
	}
	if end < len(s) && isAlphaNum(s[end]) {
		return false
	}
	return true
}

func isAlphaNum(c byte) bool {
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
}

func isLetterByte(c byte) bool {
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
}

func cleanupZh(s string) string {
	s = strings.ReplaceAll(s, " ,", "，")
	s = strings.ReplaceAll(s, " .", "。")
	s = strings.ReplaceAll(s, " :", "：")
	s = strings.ReplaceAll(s, " ;", "；")
	// 反复压缩连续空格
	for strings.Contains(s, "  ") {
		s = strings.ReplaceAll(s, "  ", " ")
	}
	// 去掉中文与中文之间的空格（"我们 的" → "我们的"）
	r := []rune(s)
	var out []rune
	for i := 0; i < len(r); i++ {
		if r[i] == ' ' && i > 0 && i+1 < len(r) && isCJK(r[i-1]) && isCJK(r[i+1]) {
			continue
		}
		// 中文后面的空格若紧跟标点，也去掉
		if r[i] == ' ' && i > 0 && i+1 < len(r) && isCJK(r[i-1]) && isPunct(r[i+1]) {
			continue
		}
		out = append(out, r[i])
	}
	return strings.TrimSpace(string(out))
}

func isCJK(r rune) bool {
	return r >= 0x4E00 && r <= 0x9FFF
}

func isPunct(r rune) bool {
	switch r {
	case '，', '。', '：', '；', '！', '？', '、', '（', '）', '「', '」':
		return true
	}
	return false
}

// ===== 与数据库对接 =====

// translateOneLocal 用本地词典翻译单条
func translateOneLocal(db *sql.DB, id int64) {
	var title, summary, lang string
	err := db.QueryRow(`SELECT title,IFNULL(summary,''),lang FROM rss_items WHERE id=?`, id).
		Scan(&title, &summary, &lang)
	if err != nil {
		return
	}
	if isMostlyChinese(title) {
		if lang != "zh" {
			_, _ = db.Exec(`UPDATE rss_items SET lang='zh' WHERE id=?`, id)
		}
		return
	}
	titleZh := translateByDict(title)
	if titleZh == "" || titleZh == title {
		return // 完全没命中，保留原文
	}
	summaryZh := ""
	if summary != "" {
		summaryZh = translateByDict(summary)
	}
	_, _ = db.Exec(`UPDATE rss_items SET title_zh=?, summary_zh=? WHERE id=?`,
		truncRunes(titleZh, 500), truncRunes(summaryZh, 2000), id)
}

// translateAllPendingLocal 批量补翻（服务启动后跑一次）
func translateAllPendingLocal(db *sql.DB) {
	rows, err := db.Query(`SELECT id FROM rss_items
		WHERE (title_zh IS NULL OR title_zh='') AND lang<>'zh'
		ORDER BY id DESC LIMIT 800`)
	if err != nil {
		return
	}
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err == nil {
			ids = append(ids, id)
		}
	}
	rows.Close()
	for _, id := range ids {
		translateOneLocal(db, id)
	}
}
