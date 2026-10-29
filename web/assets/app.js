// 超米 SPA —— 信息流 / 资讯 / 服务监控 / 天气
(function () {
  "use strict";
  const API = ""; // 同源
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

  // ---------- 主题 ----------
  function applyTheme(mode) {
    if (mode === "light") {
      document.documentElement.setAttribute("data-theme", "light");
      $("#themeIco").textContent = "☀";
      $("#themeTxt").textContent = "浅色";
    } else {
      document.documentElement.removeAttribute("data-theme");
      $("#themeIco").textContent = "◐";
      $("#themeTxt").textContent = "深色";
    }
    localStorage.setItem("chaomi_theme", mode);
    // 更新 theme-color
    const mc = document.querySelector('meta[name="theme-color"]');
    if (mc) mc.content = mode === "light" ? "#eef1f7" : "#0b0f1a";
  }
  (function initTheme() {
    let m = localStorage.getItem("chaomi_theme");
    if (!m) m = (window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches) ? "light" : "dark";
    applyTheme(m);
  })();
  $("#themeToggle").addEventListener("click", () => {
    const cur = document.documentElement.hasAttribute("data-theme") ? "light" : "dark";
    applyTheme(cur === "light" ? "dark" : "light");
  });

  // ---------- 工具 ----------
  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[c]));
  }
  function timeAgo(ms) {
    if (!ms) return "";
    const d = Date.now() - ms, m = 60000, h = 3600000, day = 86400000;
    if (d < m) return "刚刚";
    if (d < h) return Math.floor(d / m) + " 分钟前";
    if (d < day) return Math.floor(d / h) + " 小时前";
    if (d < 30 * day) return Math.floor(d / day) + " 天前";
    return new Date(ms).toLocaleDateString();
  }
  function metaLine(ms, ipLoc) {
    const t = timeAgo(ms);
    const loc = (ipLoc || "").trim();
    if (!loc) return t;
    return t ? loc + " · " + t : loc;
  }
  function toast(msg) {
    const t = $("#toast");
    t.textContent = msg; t.classList.remove("hidden");
    clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.add("hidden"), 2200);
  }
  function nick() {
    const u = ls("chaomi_user");
    if (u && u.nick) return u.nick;
    let n = $("#nickEcho") ? $("#nickEcho").textContent.trim() : "";
    return n && n !== "匿名" ? n : "匿名";
  }

  // ---------- 账号 ----------
  function ls(k) {
    try { return JSON.parse(localStorage.getItem(k) || "null"); } catch (e) { return null; }
  }
  function token() { return localStorage.getItem("chaomi_token") || ""; }
  function currentUser() { return ls("chaomi_user"); }
  function setAuth(t, u) {
    if (t) localStorage.setItem("chaomi_token", t);
    if (u) localStorage.setItem("chaomi_user", JSON.stringify(u));
    paintAccount();
    // ★ 登录/资料更新后必须同步管理员入口，否则管理员登录后看不到后台
    paintAdminEntry();
  }
  function clearAuth() {
    localStorage.removeItem("chaomi_token");
    localStorage.removeItem("chaomi_user");
    paintAccount();
    paintAdminEntry();
    // 退出后把两个与身份相关的弹层都关掉，避免残留旧数据
    const a = $("#adminSheet"); if (a) a.classList.add("hidden");
    const b = $("#accountSheet"); if (b) b.classList.add("hidden");
  }
  async function api(path, opts) {
    const o = Object.assign({ headers: {} }, opts || {});
    o.headers = Object.assign({}, o.headers);
    if (token()) o.headers["Authorization"] = "Bearer " + token();
    if (o.body && typeof o.body === "object") {
      o.headers["Content-Type"] = "application/json";
      o.body = JSON.stringify(o.body);
    }
    const r = await fetch(API + path, o);
    let j = null;
    try { j = await r.json(); } catch (e) { j = null; }
    if (r.status === 401) {
      // ★ 必须显式指定 login：openAuth() 不带参数会沿用上一次的 authMode，
      //   用户之前在「找回密码」页时，会话过期会莫名弹出找回密码，容易误导。
      clearAuth();
      openAuth("login");
      throw new Error((j && j.error) || "请先登录");
    }
    if (!j) throw new Error("响应异常");
    if (j.code !== 0 && j.error) throw new Error(j.error);
    return j;
  }
  function paintAccount() {
    const b = $("#accBtn");
    if (!b) return;
    const u = currentUser();
    if (token() && u) {
      // 管理员加个标记，便于确认当前身份（也解释了为什么能看到后台入口）
      b.textContent = (u.isAdmin ? "⚙ " : "") + (u.nick || "我");
      b.classList.add("on");
      b.title = u.isAdmin ? "管理员：" + (u.email || "") : (u.email || "");
    } else {
      b.textContent = "登录";
      b.classList.remove("on");
      b.removeAttribute("title");
    }
    if ($("#nickEcho")) $("#nickEcho").textContent = nick();
  }

  // ---------- 登录 / 注册 / 忘记密码 ----------
  // mode: login | register | forgot
  let authMode = "login";
  let codeTimer = null;

  function openAuth(mode) {
    if (mode) authMode = mode;
    $("#authSheet").classList.remove("hidden");
    applyAuthMode();
  }

  function applyAuthMode() {
    const isReg = authMode === "register";
    const isForgot = authMode === "forgot";
    $("#authTitle").textContent = isReg ? "注册" : (isForgot ? "找回密码" : "登录");
    $("#authSubmit").textContent = isReg ? "注册并登录" : (isForgot ? "发送重置链接" : "登录");
    // 验证码行：注册和找回密码都要
    $("#codeRow").classList.toggle("hidden", !(isReg || isForgot));
    $("#authNick").classList.toggle("hidden", !isReg);
    // 密码框：找回密码模式隐藏（走邮件链接重置）
    $("#authPass").classList.toggle("hidden", isForgot);
    $("#authSwitch").textContent = isReg ? "已有账号？去登录" : "还没有账号？去注册";
    $("#authForgot").classList.toggle("hidden", isForgot);
    $("#authTip").textContent = isForgot
      ? "填写注册邮箱，我们会发送一封带重置链接的邮件（10 分钟内有效）。"
      : "密码经 PBKDF2-SHA256 加盐后存储，服务端不保存明文。";
    // label 复用：忘记密码时按钮文案回到「去登录」
    if (isForgot) $("#authSwitch").textContent = "返回登录";
  }

  function resetCodeBtn() {
    if (codeTimer) { clearInterval(codeTimer); codeTimer = null; }
    const b = $("#sendCodeBtn");
    b.disabled = false; b.textContent = "获取验证码";
  }

  $("#sendCodeBtn").addEventListener("click", async () => {
    const email = $("#authEmail").value.trim();
    if (!email || email.indexOf("@") < 0) { toast("请先填写正确邮箱"); return; }
    const btn = $("#sendCodeBtn");
    btn.disabled = true; btn.textContent = "发送中…";
    try {
      const j = await api("/api/auth/sendcode", {
        method: "POST",
        body: { email, purpose: authMode === "forgot" ? "reset" : "register" },
      });
      const d = (j && j.data) || {};
      // ★ 不再自动填入验证码。
      //   自动填充意味着验证码会下发到前端，谁都能拿到别人的码直接注册/改密码。
      //   现在一律要求用户去邮箱里取。
      $("#authCode").focus();
      toast(d.message || "验证码已发送到邮箱，请查收（含垃圾箱）");
      // QQ 邮箱会把「自己发自己」的邮件当垃圾退回，提前告知避免白等
      if (email === "2080513935@qq.com") {
        $("#authTip").textContent =
          "注意：本站发件邮箱就是 2080513935@qq.com，QQ 会把「自己发给自己」的邮件退回。" +
          "请换一个其它邮箱接收，否则收不到。";
      }
      // 60 秒倒计时
      let left = 60;
      btn.textContent = left + "s 后重发";
      codeTimer = setInterval(() => {
        left--;
        if (left <= 0) { resetCodeBtn(); }
        else { btn.textContent = left + "s 后重发"; }
      }, 1000);
    } catch (e) {
      toast(e.message || "验证码发送失败");
      btn.disabled = false; btn.textContent = "获取验证码";
    }
  });

  $("#accBtn").addEventListener("click", () => {
    const u = currentUser();
    if (!token() || !u) { openAuth("login"); return; }
    openAccount();
  });

  $("#authSwitch").addEventListener("click", () => {
    authMode = (authMode === "login") ? "register" : "login";
    resetCodeBtn();
    applyAuthMode();
  });

  $("#authForgot").addEventListener("click", () => {
    authMode = "forgot";
    resetCodeBtn();
    applyAuthMode();
  });

  $("#authSubmit").addEventListener("click", async () => {
    const email = $("#authEmail").value.trim();
    const pass = $("#authPass").value;
    const nk = $("#authNick").value.trim();
    const code = $("#authCode").value.trim();
    if (!email || email.indexOf("@") < 0) { toast("请填写正确邮箱"); return; }

    const btn = $("#authSubmit");
    btn.disabled = true;
    try {
      if (authMode === "forgot") {
        // 找回密码：优先发邮件链接；同时也允许用验证码直接重置
        if (code) {
          authMode = "resetByCode";
          await doResetByCode(email, code);
          return;
        }
        await api("/api/auth/forgot", { method: "POST", body: { email } });
        // ★ 不返回、也不自动打开重置页：重置链接只能从邮箱里点进来
        $("#authTip").textContent =
          "重置链接已发送到 " + email + "，请到邮箱里点击链接（30 分钟内有效）。" +
          "没收到就看看垃圾箱。";
        toast("邮件已发送，请查收邮箱");
        return;
      }

      if (pass.length < 6) { toast("密码至少 6 位"); return; }

      if (authMode === "register") {
        if (!code) { toast("请先获取并填写邮箱验证码"); return; }
        const j = await api("/api/auth/register", {
          method: "POST", body: { email, password: pass, nick: nk, code },
        });
        setAuth(j.data.token, j.data.user);
        $("#authSheet").classList.add("hidden");
        $("#authPass").value = ""; $("#authCode").value = "";
        toast("注册成功，欢迎，" + ((j.data.user && j.data.user.nick) || "你"));
      } else {
        const j = await api("/api/auth/login", {
          method: "POST", body: { email, password: pass },
        });
        setAuth(j.data.token, j.data.user);
        $("#authSheet").classList.add("hidden");
        $("#authPass").value = "";
        toast("欢迎回来，" + ((j.data.user && j.data.user.nick) || "你"));
      }
      loadFeed();
      loadTags();
      paintAdminEntry();
    } catch (e) {
      toast(e.message || "操作失败");
    } finally {
      btn.disabled = false;
    }
  });

  // 用验证码直接重置（走已有的 /api/auth/reset）
  async function doResetByCode(email, code) {
    // 切到重置密码界面，复用 resetSheet：先让用户设新密码
    pendingReset = { email, code };
    $("#resetWho").textContent = "验证码已填好，请设置新密码（" + email + "）";
    $("#resetSheet").classList.remove("hidden");
    $("#authSheet").classList.add("hidden");
  }

  // ---------- 重置密码（邮件链接 / 验证码 两种来源） ----------
  let pendingReset = null; // { token } 或 { email, code }

  function openReset(token, email) {
    pendingReset = { token };
    $("#resetWho").textContent = email
      ? "正在为 " + email + " 重置密码"
      : "重置链接有效，请设置新密码";
    $("#resetSheet").classList.remove("hidden");
    $("#authSheet").classList.add("hidden");
  }

  $("#resetSubmit").addEventListener("click", async () => {
    const p1 = $("#resetPass").value;
    const p2 = $("#resetPass2").value;
    if (p1.length < 6) { toast("新密码至少 6 位"); return; }
    if (p1 !== p2) { toast("两次输入的密码不一致"); return; }
    if (!pendingReset) { toast("重置信息已失效，请重新获取"); return; }
    const btn = $("#resetSubmit");
    btn.disabled = true;
    try {
      if (pendingReset.token) {
        await api("/api/auth/reset-token", {
          method: "POST", body: { token: pendingReset.token, password: p1 },
        });
      } else {
        await api("/api/auth/reset", {
          method: "POST", body: { email: pendingReset.email, code: pendingReset.code, password: p1 },
        });
      }
      toast("密码已重置，请用新密码登录");
      $("#resetSheet").classList.add("hidden");
      $("#resetPass").value = ""; $("#resetPass2").value = "";
      pendingReset = null;
      // 清掉地址栏 token
      try {
        const u = new URL(location.href);
        u.searchParams.delete("reset");
        history.replaceState(null, "", u.pathname + (u.search ? u.search : "") + u.hash);
      } catch (e) {}
      openAuth("login");
    } catch (e) {
      toast(e.message || "重置失败");
    } finally {
      btn.disabled = false;
    }
  });

  // ---------- 视图切换 ----------
  const VIEW_TITLES = {
    feed: "信息流", rss: "资讯", fx: "汇率", oil: "油价",
    typhoon: "台风路径", watermark: "去水印", convert: "格式转换",
    monitor: "服务监控", weather: "天气",
  };
  let currentView = "feed";

  function switchView(v) {
    currentView = v;
    $$(".nav-item[data-view]").forEach((x) => x.classList.toggle("active", x.dataset.view === v));
    $$(".view").forEach((x) => x.classList.remove("active"));
    $("#view-" + v).classList.add("active");
    $("#topbarTitle").textContent = VIEW_TITLES[v] || "超米";
    $("#sidenav").classList.remove("open");
    // 首次进入时加载数据
    if (v === "rss") { loadRSSCats(); loadRSS(true); }
    if (v === "monitor") { loadMonitor(); }
    if (v === "weather") { loadWeather(); }
    if (v === "fx") { loadFx(); }
    if (v === "oil") { loadOil(); }
    if (v === "typhoon") { loadTyphoon(); }
    if (v === "watermark") { bindWatermarkOnce(); }
    if (v === "watermark" && wmBound) { refreshChannel(); }
    if (v === "convert") { bindConvertOnce(); }
  }

  $$(".nav-item[data-view]").forEach((t) => t.addEventListener("click", () => switchView(t.dataset.view)));
  $("#hamburger").addEventListener("click", () => $("#sidenav").classList.toggle("open"));
  $("#openComposer").addEventListener("click", () => {
    if (!token()) { toast("请先登录再发布"); openAuth(); return; }
    $("#composer").classList.remove("hidden");
  });

  // ========== 天气 ==========
  const WX_KEY = "chaomi_wx_city";

  function loadWeather() {
    const saved = localStorage.getItem(WX_KEY) || "";
    const done = (query) => {
      api("/api/weather" + query).then((j) => {
        if (j && j.data) paintWeather(j.data);
      }).catch(() => {
        $("#wxText").textContent = "天气获取失败";
      });
    };
    if (saved) { done("?city=" + encodeURIComponent(saved)); return; }
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (pos) => done(`?lat=${pos.coords.latitude.toFixed(4)}&lon=${pos.coords.longitude.toFixed(4)}`),
        () => done(""),
        { timeout: 6000, maximumAge: 10 * 60 * 1000 }
      );
    } else {
      done("");
    }
  }

  function paintWeather(d) {
    $("#wxCity").textContent = d.city
      ? (d.region && d.region !== d.city ? `${d.city} · ${d.region}` : d.city)
      : "当前位置";
    const n = d.now || {};
    $("#wxIcon").textContent = n.icon || "🌡️";
    $("#wxTemp").textContent = n.tempC == null ? "--" : Math.round(n.tempC);
    $("#wxText").textContent = n.text || "";
    $("#wxSub").textContent =
      `体感 ${Math.round(n.feelsC == null ? 0 : n.feelsC)}° · 湿度 ${n.humidity || 0}% · 风 ${Math.round(n.windKph || 0)}km/h`;
    const days = (d.days || []).slice(0, 5);
    $("#wxDays").innerHTML = days.map((x, i) => {
      const label = i === 0 ? "今天" : weekdayOf(x.date);
      return `<div class="wx-day">
        <span class="wxd">${label}</span>
        <span class="wxdi">${x.icon || ""}</span>
        <span class="wxdt">${Math.round(x.maxC)}°<i>/${Math.round(x.minC)}°</i></span>
      </div>`;
    }).join("");
  }
  function weekdayOf(dateStr) {
    const d = new Date(dateStr + "T00:00:00");
    if (isNaN(d.getTime())) return "";
    return ["周日", "周一", "周二", "周三", "周四", "周五", "周六"][d.getDay()];
  }
  function bindWeatherSwitch() {
    $("#wxSwitch").addEventListener("click", () => {
      const cur = localStorage.getItem(WX_KEY) || "";
      const v = prompt("输入城市名（留空则用自动定位）", cur);
      if (v === null) return;
      const city = v.trim();
      if (city) localStorage.setItem(WX_KEY, city);
      else localStorage.removeItem(WX_KEY);
      loadWeather();
    });
  }

  // ========== 资讯（RSS） ==========
  const rssState = { cat: "", before: 0, loading: false, hasMore: true, seen: new Set() };

  async function loadRSSCats() {
    try {
      const j = await api("/api/rss/cats");
      const cats = (j.data && j.data.cats) || [];
      const box = $("#rssCats");
      box.innerHTML = [`<div class="chip active" data-cat="">全部</div>`]
        .concat(cats.map((c) =>
          `<div class="chip" data-cat="${esc(c.key)}">${esc(c.name)}<i>${c.count || 0}</i></div>`))
        .join("");
      $$(".chip", box).forEach((el) => el.addEventListener("click", () => {
        $$(".chip", box).forEach((x) => x.classList.remove("active"));
        el.classList.add("active");
        rssState.cat = el.dataset.cat || "";
        loadRSS(true);
      }));
    } catch (e) { /* 分类拉取失败不阻断列表 */ }
  }

  async function loadRSS(reset) {
    if (rssState.loading) return;
    if (reset) {
      rssState.before = 0;
      rssState.hasMore = true;
      rssState.seen = new Set();
      $("#rssList").innerHTML = "";
    }
    if (!rssState.hasMore) return;

    rssState.loading = true;
    $("#rssLoading").classList.remove("hidden");
    try {
      let u = "/api/rss/items?limit=12";
      if (rssState.cat) u += "&cat=" + encodeURIComponent(rssState.cat);
      if (rssState.before) u += "&before=" + rssState.before;
      const j = await api(u);
      const d = j.data || {};
      const list = d.list || [];
      rssState.before = d.nextBefore || 0;
      rssState.hasMore = !!d.hasMore && list.length > 0;

      const box = $("#rssList");
      let added = 0;
      for (const it of list) {
        if (rssState.seen.has(it.id)) continue;
        rssState.seen.add(it.id);
        box.insertAdjacentHTML("beforeend", rssCard(it));
        added++;
      }
      $("#rssEmpty").classList.toggle("hidden", box.children.length > 0);
      $("#rssMore").style.display = rssState.hasMore ? "block" : "none";
      if (added === 0 && rssState.hasMore) { rssState.loading = false; loadRSS(false); return; }
    } catch (e) {
      $("#rssEmpty").classList.remove("hidden");
      $("#rssEmpty").textContent = "资讯加载失败：" + (e.message || "网络错误");
    } finally {
      rssState.loading = false;
      $("#rssLoading").classList.add("hidden");
    }
  }

  function rssCard(it) {
    const title = it.titleZh || it.title || "";
    const showOrig = it.titleZh && it.title && it.titleZh !== it.title;
    const sum = it.summaryZh || it.summary || "";
    const img = it.image
      ? `<div class="rssimg"><img src="${esc(it.image)}" loading="lazy" alt=""></div>` : "";
    return `<article class="rsscard" data-link="${esc(it.link)}">
      ${img}
      <div class="rssbody">
        <h3>${esc(title)}</h3>
        ${showOrig ? `<div class="rssorig">${esc(it.title)}</div>` : ""}
        ${sum ? `<p>${esc(sum.slice(0, 160))}</p>` : ""}
        <div class="rssmeta">
          <span class="rsssrc">${esc(it.source || "")}</span>
          <span>${esc(timeAgo(it.pubAt))}</span>
        </div>
      </div>
    </article>`;
  }

  function bindRSSActions() {
    $("#rssMore").addEventListener("click", () => loadRSS(false));
    $("#rssList").addEventListener("click", (e) => {
      const card = e.target.closest(".rsscard");
      if (!card) return;
      const link = card.dataset.link;
      if (link) window.open(link, "_blank", "noopener");
    });
    // 刷新：触发服务端拉取，再等几秒重载
    $("#rssRefreshBtn").addEventListener("click", async () => {
      const btn = $("#rssRefreshBtn");
      btn.disabled = true; btn.textContent = "拉取中…";
      try {
        await api("/api/rss/refresh", { method: "POST" });
        toast("已触发拉取，稍候刷新");
      } catch (e) {
        toast(e.message === "请先登录" ? e.message : (e.message || "刷新失败"));
      }
      setTimeout(() => {
        loadRSS(true);
        btn.disabled = false; btn.textContent = "↻ 刷新";
      }, 6000);
    });
  }

  // ========== 服务监控 ==========
  // ★ 稳定渲染：不再每次 innerHTML 整块重建。
  //   以前每 3 秒重建 30 个卡片 → DOM 全换 → 列表高度/滚动位置抖动、
  //   动画重放，看起来「跳来跳去」。
  //   现在按 target key 复用节点，只改文字与颜色；顺序也固定（只排一次）。
  let monTimer = null;
  const monNodes = new Map(); // key -> {root, name, ms, err, at}
  let monOrder = "";
  const monState = { list: [] };

  function latCls(ms) {
    return ms < 200 ? "fast" : (ms < 800 ? "slow" : "vslow");
  }

  function monItemEl(x) {
    let n = monNodes.get(x.key);
    if (n) return n;
    const root = document.createElement("div");
    root.className = "mon-item";
    root.innerHTML = `
      <div class="mhead">
        <span class="mdot"></span>
        <span class="mname"></span>
        <span class="mms"></span>
      </div>
      <div class="merr hidden"></div>
      <div class="mat"></div>`;
    n = {
      root,
      name: root.querySelector(".mname"),
      ms: root.querySelector(".mms"),
      err: root.querySelector(".merr"),
      at: root.querySelector(".mat"),
    };
    n.name.textContent = x.name || x.key;
    root.dataset.key = x.key;   // 供事件委托取 key
    monNodes.set(x.key, n);
    return n;
  }

  // 监控详情：点卡片查看该目标的延迟曲线 / 可用率 / 错误
  let mdKey = null;

  function openMonDetail(key) {
    if (!key) return;
    mdKey = key;
    const n = monNodes.get(key);
    // 先把上一张卡片的内容清空，避免"看着是新卡片、数据还是旧的"
    $("#mdName").textContent = n ? n.name.textContent : key;
    $("#mdMs").textContent = "—";
    $("#mdCode").textContent = "—";
    $("#mdAt").textContent = "—";
    $("#mdStreak").textContent = "—";
    $("#mdAvail").textContent = "加载中…";
    $("#mdErr").classList.add("hidden");
    $("#mdErr").textContent = "";
    $("#mdDot").className = "md-dot";
    $("#mdBadge").className = "md-badge";
    $("#mdBadge").textContent = "加载中";
    $("#mdChart").innerHTML = '<div class="chart-empty">加载中…</div>';
    monNodes.forEach((nn, k) => nn.root.classList.toggle("active", k === key));
    $("#monDetail").classList.remove("hidden");
    loadMonDetail(key);
  }

  function closeMonDetail() {
    mdKey = null;
    $("#monDetail").classList.add("hidden");
    monNodes.forEach((n) => n.root.classList.remove("active"));
  }
  // 事件委托：不管卡片是新建的还是复用的，点击都能命中
  (function bindMonGridDelegation() {
    const grid = $("#monGrid");
    if (!grid) return;
    grid.addEventListener("click", (e) => {
      const item = e.target.closest(".mon-item");
      if (!item) return;
      const key = item.dataset.key;
      if (key) openMonDetail(key);
    });
  })();
  const mdCloseBtn = $("#mdClose");
  if (mdCloseBtn) mdCloseBtn.addEventListener("click", closeMonDetail);
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && mdKey) closeMonDetail();
  });

  async function loadMonDetail(key) {
    try {
      const j = await api("/api/watch/history?key=" + encodeURIComponent(key) + "&points=60");
      const pts = (j.data || []).slice(); // 时间升序

      // 曲线：失败点用 0 表示（掉线），成功用延迟
      const serie = pts.map((p) => ({ at: p.at, v: p.ok ? (p.ms || 0) : 0 }));
      renderSpark($("#mdChart"), serie, { emptyText: "该目标暂无历史探测记录" });

      // 可用率
      const total = pts.length;
      const okCnt = pts.filter((p) => p.ok).length;
      const rate = total ? (okCnt / total * 100) : 0;
      const avgMs = okCnt
        ? Math.round(pts.filter((p) => p.ok).reduce((s, p) => s + (p.ms || 0), 0) / okCnt)
        : 0;
      $("#mdAvail").innerHTML = total
        ? `最近 <b>${total}</b> 次探测，成功 <b>${okCnt}</b> 次，可用率 <b>${rate.toFixed(1)}%</b>` +
          (avgMs ? ` · 成功平均 <b>${avgMs}</b> ms` : "")
        : "暂无历史数据（服务刚启动时需等一轮探测）";

      // 当前状态（用列表里的最新值）
      const n = monNodes.get(key);
      const items = monState.list || [];
      const cur = items.find((x) => x.key === key) || {};
      $("#mdDot").className = "md-dot" + (cur.ok ? "" : " bad");
      const badge = $("#mdBadge");
      badge.className = "md-badge " + (cur.ok ? "ok" : "bad");
      badge.textContent = cur.ok ? "正常" : "异常";
      $("#mdMs").textContent = cur.ok ? (cur.latencyMs || 0) + " ms" : "—";
      $("#mdCode").textContent = cur.status ? String(cur.status) : "—";
      $("#mdAt").textContent = cur.checkedAt ? timeAgo(cur.checkedAt) : "—";
      $("#mdStreak").textContent = cur.failStreak ? cur.failStreak + " 次" : "0";

      const err = $("#mdErr");
      if (!cur.ok && cur.error) {
        err.textContent = "错误：" + cur.error;
        err.classList.remove("hidden");
      } else {
        err.classList.add("hidden");
      }
    } catch (e) {
      $("#mdChart").innerHTML = '<div class="chart-empty">详情加载失败</div>';
    }
  }

  async function loadMonitor() {
    try {
      const j = await api("/api/watch/status");
      const d = j.data || {};
      const list = d.list || [];
      monState.list = list;
      const up = d.up || 0, down = d.down || 0;

      $("#monTotal").textContent = list.length;
      $("#monUp").textContent = up;
      $("#monDown").textContent = down;

      let sumMs = 0, cnt = 0;
      list.forEach((x) => {
        if (x.ok) { sumMs += (x.latencyMs || 0); cnt++; }
      });
      $("#monMs").textContent = cnt > 0 ? Math.round(sumMs / cnt) : 0;

      const badge = $("#monTopBadge");
      const allOk = down === 0 && list.length > 0;
      badge.className = "mon-badge " + (allOk ? "ok" : "bad");
      badge.textContent = allOk ? "全部正常" : (down + " 项异常");

      const mb = $("#monBadge");
      if (down > 0) { mb.textContent = down; mb.classList.remove("hidden"); }
      else mb.classList.add("hidden");

      const grid = $("#monGrid");

      // 顺序是否变化（只有真的变化才重排，避免视觉跳动）
      const newOrder = list.map((x) => x.key).join(",");
      if (newOrder !== monOrder) {
        monOrder = newOrder;
        // 用现有节点按新顺序重排（不销毁）
        list.forEach((x) => {
          const n = monItemEl(x);
          grid.appendChild(n.root); // appendChild 已有节点 = 移动
        });
        // 清掉已经不存在的目标
        const alive = new Set(list.map((x) => x.key));
        monNodes.forEach((n, k) => {
          if (!alive.has(k)) { n.root.remove(); monNodes.delete(k); }
        });
      }

      // 只更新内容，不重建 DOM
      list.forEach((x) => {
        const n = monItemEl(x);
        const ok = !!x.ok;
        n.root.classList.toggle("down", !ok);

        if (ok) {
          n.ms.textContent = (x.latencyMs || 0) + " ms";
          n.ms.className = "mms " + latCls(x.latencyMs || 0);
          n.err.classList.add("hidden");
        } else {
          const streak = x.failStreak > 0 ? " · 连续 " + x.failStreak + " 次" : "";
          n.ms.textContent = "异常";
          n.ms.className = "mms vslow";
          if (x.error) {
            n.err.textContent = "↳ " + x.error;
            n.err.classList.remove("hidden");
          } else {
            n.err.textContent = "异常" + streak;
            n.err.classList.remove("hidden");
          }
        }
        n.at.textContent = timeAgo(x.checkedAt);
      });
    } catch (e) {
      $("#monTopBadge").className = "mon-badge bad";
      $("#monTopBadge").textContent = "获取失败";
    }
  }

  function startMonitor() {
    if (monTimer) return;
    loadMonitor();
    // 服务端每 15 秒采集一次，前端跟着 5 秒拉一次就够。
    // 之前 3 秒拉一次，视觉上频繁变动且浪费流量。
    monTimer = setInterval(() => {
      if (currentView === "monitor") loadMonitor();
      if (mdKey) loadMonDetail(mdKey);
    }, 5000);
  }
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) return; // 定时器只在 monitor 视图时触发
  });

  // ---------- 信息流 ----------
  const cards = new Map();
  const feedState = { sort: "", mine: false, q: "", tag: "" };

  function feedURL() {
    const a = ["limit=30"];
    if (feedState.sort) a.push("sort=" + feedState.sort);
    if (feedState.mine) a.push("mine=1");
    if (feedState.q) a.push("q=" + encodeURIComponent(feedState.q));
    if (feedState.tag) a.push("tag=" + encodeURIComponent(feedState.tag));
    return "/api/community/feed?" + a.join("&");
  }

  async function loadFeed() {
    try {
      const j = await api(feedURL());
      const list = Array.isArray(j.data) ? j.data : ((j.data && j.data.data) || []);
      const box = $("#postList");
      box.innerHTML = "";
      cards.clear();
      if (!list.length) {
        $("#feedEmpty").classList.remove("hidden");
        $("#feedEmpty").textContent = feedState.mine ? "你还没发过内容"
          : (feedState.q || feedState.tag) ? "没有匹配的内容" : "还没有内容，点「＋ 发布」发第一条吧。";
        return;
      }
      $("#feedEmpty").classList.add("hidden");
      list.forEach((p) => box.appendChild(renderPost(p)));
    } catch (e) {
      if (e.message !== "请先登录") toast("信息流加载失败");
    }
  }

  async function loadTags() {
    try {
      const j = await api("/api/community/tags");
      const tags = Array.isArray(j.data) ? j.data : [];
      const box = $("#tagRow");
      box.innerHTML = [`<div class="chip active" data-tag="">全部</div>`]
        .concat(tags.slice(0, 11).map((t) =>
          `<div class="chip" data-tag="${esc(t.tag)}">#${esc(t.tag)} ${t.count}</div>`))
        .join("");
      $$(".chip", box).forEach((el) => el.addEventListener("click", () => {
        $$(".chip", box).forEach((x) => x.classList.remove("active"));
        el.classList.add("active");
        feedState.tag = el.dataset.tag || "";
        feedState.mine = false;
        loadFeed();
      }));
    } catch (e) { /* 标签是增强项，失败静默 */ }
  }

  $$(".fs").forEach((b) => b.addEventListener("click", () => {
    $$(".fs").forEach((x) => x.classList.remove("active"));
    b.classList.add("active");
    feedState.mine = b.dataset.fmine === "1";
    feedState.sort = b.dataset.fsort || "";
    if (feedState.mine && !token()) { openAuth(); return; }
    loadFeed();
  }));
  $("#feedSearch").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      feedState.q = e.target.value.trim();
      feedState.mine = false;
      loadFeed();
    }
  });

  function typeBadge(t) {
    return { text: "文字", image: "图片", link: "链接", article: "图文" }[t] || t;
  }
  function renderPost(p) {
    const el = document.createElement("div");
    el.className = "card"; el.dataset.id = p.id;
    const initial = (p.author || "匿")[0];
    let html = `<div class="head">
      <div class="avatar">${esc(initial)}</div>
      <div><div class="name">${esc(p.author || "匿名")}</div>
      <div class="time">${esc(metaLine(p.createdAt, p.ipLoc))} · <span class="badge">${typeBadge(p.type)}</span></div></div>
    </div>`;
    if (p.body) html += `<div class="body">${esc(p.body)}</div>`;
    if (p.type === "image" && p.images && p.images.length) {
      html += `<div class="imgs">${p.images.map((u) => `<img src="${esc(u)}" loading="lazy">`).join("")}</div>`;
    }
    if (p.type === "link") {
      if (p.cover) html += `<img class="cover" src="${esc(p.cover)}" loading="lazy">`;
      html += `<a class="linkcard" href="${esc(p.url)}" target="_blank" rel="noopener">
        <div class="lt">${esc(p.title || p.url)}</div>${p.body ? `<div class="body">${esc(p.body)}</div>` : ""}
        <div class="lu">${esc(p.url)}</div></a>`;
    }
    if (p.type === "article") {
      if (p.cover) html += `<img class="cover" src="${esc(p.cover)}" loading="lazy">`;
      if (p.title) html += `<div class="body" style="font-weight:700">${esc(p.title)}</div>`;
      if (p.blocks && p.blocks.length) {
        html += `<div class="article-body">${blocksPreview(p.blocks)}</div>`;
      } else if (p.body) {
        html += `<div class="article-body">${esc(p.body)}</div>`;
      }
      if (p.url) html += `<a class="linkcard" href="${esc(p.url)}" target="_blank" rel="noopener"><div class="lu">查看原文 ↗</div></a>`;
    }
    if (p.tags && p.tags.length) {
      html += `<div class="tags">${p.tags.map((t) => `<span class="tagc" data-t="${esc(t)}">#${esc(t)}</span>`).join("")}</div>`;
    }
    const mine = token() && currentUser() && p.userId === currentUser().id;
    html += `<div class="actions">
      <button class="like ${p.liked ? "liked" : ""}" data-act="like">♥ <span class="lc">${p.likes || 0}</span></button>
      <button data-act="comment">💬 <span class="cc">${p.commentCount || 0}</span></button>
      ${mine ? `<button data-act="del" style="color:var(--bad)">删除</button>` : ""}
    </div>`;
    el.innerHTML = html;
    el.querySelector('[data-act="like"]').addEventListener("click", (e) => doLike(p.id, e.currentTarget));
    el.querySelector('[data-act="comment"]').addEventListener("click", () => openDetail(p.id));
    const del = el.querySelector('[data-act="del"]');
    if (del) del.addEventListener("click", () => delPost(p.id));
    el.querySelectorAll(".tagc").forEach((t) => t.addEventListener("click", () => {
      feedState.tag = t.dataset.t || "";
      feedState.mine = false;
      loadFeed();
    }));
    cards.set(p.id, el);
    return el;
  }

  function blocksPreview(blocks) {
    const out = [];
    let n = 0;
    for (const b of blocks) {
      if (!b) continue;
      if (b.type === "img") {
        if (b.src) { out.push(`<img src="${esc(b.src)}" loading="lazy">`); n++; }
      } else if (b.text) {
        out.push(`<div>${esc(b.text)}</div>`);
        n++;
      }
      if (n >= 3) break;
    }
    return out.join("");
  }

  async function delPost(id) {
    if (!confirm("删除这条内容？评论和点赞会一起移除。")) return;
    try {
      await api("/api/community/post/" + id, { method: "DELETE" });
      toast("已删除");
      loadFeed();
    } catch (e) { toast(e.message || "删除失败"); }
  }

  async function doLike(id, btn) {
    if (!token()) { openAuth(); return; }
    try {
      const j = await api("/api/community/post/" + id + "/like", { method: "POST" });
      btn.querySelector(".lc").textContent = j.data;
      btn.classList.toggle("liked", !!j.liked);
    } catch (e) { toast(e.message || "点赞失败"); }
  }

  // ---------- 详情 / 评论 ----------
  let curDetailId = null;
  async function openDetail(id) {
    curDetailId = id;
    try {
      const j = await api("/api/community/post/" + id);
      if (j.code !== 0) { toast("帖子不存在"); return; }
      renderDetail(j.data, j.comments || [], !!j.mine);
      $("#detail").classList.remove("hidden");
    } catch (e) { if (e.message !== "请先登录") toast("加载失败"); }
  }
  function renderDetail(p, comments, mine) {
    const box = $("#detailBody");
    let html = "";
    const initial = (p.author || "匿")[0];
    html += `<div class="head"><div class="avatar">${esc(initial)}</div>
      <div><div class="name">${esc(p.author || "匿名")}</div>
      <div class="time">${esc(metaLine(p.createdAt, p.ipLoc))} · ${p.views || 0} 次浏览</div></div></div>`;
    if (p.title) html += `<div class="body" style="font-weight:700;font-size:17px">${esc(p.title)}</div>`;
    if (p.body) html += `<div class="body">${esc(p.body)}</div>`;
    if (p.blocks && p.blocks.length) {
      html += `<div class="article-body">${renderBlocks(p.blocks)}</div>`;
    }
    if (p.tags && p.tags.length) {
      html += `<div class="tags">${p.tags.map((t) => `<span class="tagc">#${esc(t)}</span>`).join("")}</div>`;
    }
    if (p.url) html += `<a class="linkcard" href="${esc(p.url)}" target="_blank" rel="noopener"><div class="lu">原文 ↗</div></a>`;
    html += `<div class="actions">
      <button class="like ${p.liked ? "liked" : ""}" data-act="like">♥ <span class="lc">${p.likes || 0}</span></button>
      ${mine ? `<button data-act="del" style="color:var(--bad)">删除这条</button>` : ""}
    </div>`;
    html += `<div class="cmt"><h4>评论 ${comments.length}</h4>`;
    comments.forEach((c) => {
      html += `<div class="c"><div class="chd">
        <span class="cn">${esc(c.author || "匿名")}</span>
        <span class="cmeta">${esc(metaLine(c.createdAt, c.ipLoc))}</span>
      </div>
        <span class="cbd">${esc(c.body)}</span>
        ${c.mine ? `<button class="cdel" data-cid="${c.id}">删除</button>` : ""}</div>`;
    });
    html += `<div class="cmt-form"><input id="cmtInput" placeholder="写下评论…"><button class="primary" id="cmtSend">发送</button></div></div>`;
    box.innerHTML = html;

    box.querySelector('[data-act="like"]').addEventListener("click", async (e) => {
      if (!token()) { openAuth(); return; }
      try {
        const jj = await api("/api/community/post/" + p.id + "/like", { method: "POST" });
        e.currentTarget.querySelector(".lc").textContent = jj.data;
        e.currentTarget.classList.toggle("liked", !!jj.liked);
      } catch (err) { toast(err.message || "点赞失败"); }
    });
    const del = box.querySelector('[data-act="del"]');
    if (del) del.addEventListener("click", () => {
      if (!confirm("删除这条内容？")) return;
      api("/api/community/post/" + p.id, { method: "DELETE" }).then(() => {
        $("#detail").classList.add("hidden");
        toast("已删除");
        loadFeed();
      }).catch((err) => toast(err.message || "删除失败"));
    });
    box.querySelectorAll(".cdel").forEach((b) => b.addEventListener("click", () => {
      if (!confirm("删除这条评论？")) return;
      api("/api/community/comment/" + b.dataset.cid, { method: "DELETE" }).then(() => {
        openDetail(p.id);
        toast("已删除");
      }).catch((err) => toast(err.message || "删除失败"));
    }));
    box.querySelector("#cmtSend").addEventListener("click", () => sendComment(p.id));
  }

  function inline(raw) {
    let s = esc(raw == null ? "" : raw);
    s = s.replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>");
    s = s.replace(/==([^=\n]+)==/g, '<mark class="hl">$1</mark>');
    s = s.replace(/~~([^~\n]+)~~/g, "<del>$1</del>");
    s = s.replace(/`([^`\n]+)`/g, '<code class="ic">$1</code>');
    s = s.replace(/\*([^*\n]+)\*/g, "<em>$1</em>");
    return s;
  }

  function renderBlocks(blocks) {
    let ol = 0;
    return blocks.map((b) => {
      if (!b) return "";
      const center = b.style === "center" ? ' style="text-align:center"' : "";
      switch (b.type) {
        case "h2": ol = 0; return `<h2>${inline(b.text)}</h2>`;
        case "h3": ol = 0; return `<h3>${inline(b.text)}</h3>`;
        case "quote": ol = 0; return `<blockquote>${inline(b.text)}</blockquote>`;
        case "code": ol = 0; return `<pre>${esc(b.text || "")}</pre>`;
        case "li": ol = 0; return `<div class="li">• ${inline(b.text)}</div>`;
        case "ol": return `<div class="li">${++ol}. ${inline(b.text)}</div>`;
        case "hr": ol = 0; return `<hr>`;
        case "video":
          return b.src
            ? `<div class="vid"><a href="${esc(b.src)}" target="_blank" rel="noopener">▶ 播放视频</a></div>`
            : "";
        case "img": {
          if (!b.src) return "";
          const alt = b.alt ? `<div class="cap">${esc(b.alt)}</div>` : "";
          return `<img src="${esc(b.src)}" loading="lazy" alt="${esc(b.alt || "")}">${alt}`;
        }
        case "topic":
          ol = 0;
          return `<span class="tagc">#${esc(b.text || "")}</span>`;
        case "link":
          ol = 0;
          return b.src
            ? `<a class="linkcard" href="${esc(b.src)}" target="_blank" rel="noopener">
                 <div class="lt">${esc(b.text || b.src)}</div>
                 ${b.alt ? `<div class="ld">${esc(b.alt)}</div>` : ""}</a>`
            : "";
        default:
          ol = 0;
          return b.text ? `<p${center}>${inline(b.text)}</p>` : "";
      }
    }).join("");
  }

  async function sendComment(id) {
    if (!token()) { openAuth(); return; }
    const inp = $("#cmtInput");
    const body = inp.value.trim();
    if (!body) return;
    try {
      await api("/api/community/post/" + id + "/comments", {
        method: "POST", body: { author: nick(), body },
      });
      inp.value = "";
      openDetail(id);
      bumpComment(id);
    } catch (e) { toast(e.message || "评论失败"); }
  }
  function bumpComment(id) {
    const el = cards.get(id); if (!el) return;
    const cc = el.querySelector(".cc"); if (cc) cc.textContent = (parseInt(cc.textContent || "0") + 1);
  }

  // ---------- 发布器 ----------
  let curType = "text";
  $$(".type-tabs .tt").forEach((b) => b.addEventListener("click", () => {
    $$(".type-tabs .tt").forEach((x) => x.classList.remove("active"));
    b.classList.add("active"); curType = b.dataset.type;
    $$(".pane").forEach((p) => p.classList.toggle("hidden", p.dataset.pane !== curType));
  }));
  $$("[data-close]").forEach((b) => b.addEventListener("click", () => {
    $("#composer").classList.add("hidden");
    $("#detail").classList.add("hidden");
    $("#authSheet").classList.add("hidden");
  }));

  $("#submitPost").addEventListener("click", async () => {
    const author = nick();
    let payload = { author, type: curType };
    if (curType === "text") {
      payload.body = $("#t_body").value.trim(); if (!payload.body) return toast("写点什么吧");
    } else if (curType === "image") {
      const u = $("#i_url").value.trim(); if (!u) return toast("填图片链接");
      payload.images = [u]; payload.title = $("#i_title").value.trim();
    } else if (curType === "link") {
      const u = $("#l_url").value.trim(); if (!u) return toast("填链接");
      payload.url = u; payload.title = $("#l_title").value.trim();
      payload.cover = $("#l_cover").value.trim(); payload.body = $("#l_body").value.trim();
    } else if (curType === "article") {
      if (!pendingArticle) return toast("先解析公众号文章");
      payload = Object.assign({ author, type: "article" }, pendingArticle);
    }
    try {
      const j = await api("/api/community/posts", { method: "POST", body: payload });
      toast("已发布");
      $("#composer").classList.add("hidden");
      $("#t_body").value = ""; $("#i_url").value = ""; $("#i_title").value = "";
      $("#l_url").value = ""; $("#l_title").value = ""; $("#l_cover").value = ""; $("#l_body").value = "";
      pendingArticle = null; $("#a_preview").classList.add("hidden"); $("#a_url").value = "";
      switchView("feed");
      const box = $("#postList"); box.insertBefore(renderPost(j.data), box.firstChild);
      $("#feedEmpty").classList.add("hidden");
      loadTags();
    } catch (e) { toast("发布失败：" + (e.message || "")); }
  });

  // ---------- 公众号图文导入 ----------
  let pendingArticle = null;
  $("#a_parse").addEventListener("click", async () => {
    const u = $("#a_url").value.trim(); if (!u) return toast("粘贴文章链接");
    $("#a_parse").textContent = "解析中…"; $("#a_parse").disabled = true;
    try {
      const r = await fetch(API + "/api/article/parse?url=" + encodeURIComponent(u));
      const j = await r.json();
      if (j.code !== 0) { toast("解析失败：" + (j.error || "")); return; }
      const a = j.data;
      const blocks = a.blocks || [];
      pendingArticle = {
        title: a.title, body: a.summary || "", cover: a.cover,
        url: a.url, images: a.images || [], blocks: blocks,
      };
      const pv = $("#a_preview"); pv.classList.remove("hidden");
      pv.innerHTML = `<h3>${esc(a.title)}</h3>
        <div class="time">${a.author ? "来源：" + esc(a.author) + " · " : ""}共 ${blocks.length} 段 · 发布后为原生文章（不是网页）</div>
        <div class="ap-content">${renderBlocks(blocks)}</div>
        <div style="margin-top:8px"><button class="primary" id="a_use">用这个内容发帖</button></div>`;
      pv.querySelector("#a_use").addEventListener("click", () => {
        $("#t_body").value = ""; curType = "article";
        $$(".type-tabs .tt").forEach((x) => x.classList.toggle("active", x.dataset.type === "article"));
        $$(".pane").forEach((p) => p.classList.toggle("hidden", p.dataset.pane !== "article"));
        toast("已填入，点「发布」即可");
      });
    } catch (e) { toast("解析出错"); }
    finally { $("#a_parse").textContent = "解析"; $("#a_parse").disabled = false; }
  });

  // ---------- 节点心跳：每 3 秒一次 ----------
  const hb = { sent: 0, lost: 0, samples: [], timer: null };

  function latClass(ms) {
    if (ms < 60) return "good";
    if (ms < 150) return "ok";
    if (ms < 300) return "warn";
    return "bad";
  }

  async function heartbeat() {
    const t0 = performance.now();
    try {
      const r = await fetch(API + "/api/ping?t=" + Date.now(), { cache: "no-store" });
      const j = await r.json();
      let rtt = Math.round(performance.now() - t0);
      const cost = (j && j.data && Number(j.data.costMs)) || 0;
      if (cost > 0 && cost < rtt) rtt -= cost;
      if (rtt < 0) rtt = 0;
      hb.sent++;
      hb.samples.push(rtt);
      if (hb.samples.length > 40) hb.samples.shift();

      const txts = [$("#latText"), $("#latTextM")];
      const chips = [$("#latChip"), $("#latChipM")];
      txts.forEach((t) => { if (t) t.textContent = rtt + " ms"; });
      chips.forEach((c) => { if (c) { c.className = "lat " + latClass(rtt); } });
      const dots = [$("#wsdot"), $("#wsdotM")];
      dots.forEach((d) => { if (d) d.className = "dot " + (rtt < 300 ? "on" : "off"); });

      fetch(API + "/api/ping/report", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ rtt }),
      }).catch(() => {});
    } catch (e) {
      hb.sent++;
      hb.lost++;
      [$("#latText"), $("#latTextM")].forEach((t) => { if (t) t.textContent = "超时"; });
    }
  }

  function startHeartbeat() {
    if (hb.timer) return;
    heartbeat();
    hb.timer = setInterval(heartbeat, 3000);
  }
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      if (hb.timer) { clearInterval(hb.timer); hb.timer = null; }
    } else {
      startHeartbeat();
    }
  });

  // ---------- 实时 WebSocket ----------
  let ws = null;
  function connectWS() {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const url = proto + "://" + location.host + "/ws/community";
    try { ws = new WebSocket(url); } catch (e) { return; }
    ws.onopen = () => { [$("#wsdot"), $("#wsdotM")].forEach((d) => { if (d) d.classList.replace("off", "on"); }); };
    ws.onclose = () => { [$("#wsdot"), $("#wsdotM")].forEach((d) => { if (d) d.classList.replace("on", "off"); }); setTimeout(connectWS, 3000); };
    ws.onmessage = (ev) => {
      try {
        const m = JSON.parse(ev.data);
        if (m.type === "new_post" && m.post) {
          const box = $("#postList"); box.insertBefore(renderPost(m.post), box.firstChild);
          $("#feedEmpty").classList.add("hidden");
          if (currentView !== "feed") toast("有新帖子");
        } else if (m.type === "like" && cards.has(m.postId)) {
          const lc = cards.get(m.postId).querySelector(".lc"); if (lc) lc.textContent = m.likes;
        } else if (m.type === "new_comment" && m.postId === curDetailId) {
          openDetail(m.postId); bumpComment(m.postId);
        }
      } catch (e) {}
    };
  }

  // ---------- 走势图（内联 SVG，无外部依赖） ----------
  // 用 true 表示涨（红）还是跌（绿）：这里按「数值上升=红」的国内习惯，
  // 对汇率而言「1 元换到的外币变多」= 人民币升值，仍用红绿表达变化方向即可。
  function renderSpark(el, points, opts) {
    const o = opts || {};
    if (!el) return;
    if (!points || points.length < 2) {
      el.innerHTML = '<div class="chart-empty">' +
        (o.emptyText || '数据积累中<br>每整点记录一次，稍后即可看到走势') + '</div>';
      return;
    }
    const W = 640, H = 180, PAD_L = 6, PAD_R = 6, PAD_T = 12, PAD_B = 18;
    const vals = points.map((p) => p.v);
    let min = Math.min.apply(null, vals), max = Math.max.apply(null, vals);
    if (max === min) { max = min + Math.max(0.0001, Math.abs(min) * 0.001); }
    const span = max - min;
    // 上下各留 12% 余量，线条不贴边
    min -= span * 0.12; max += span * 0.12;

    const n = points.length;
    const innerW = W - PAD_L - PAD_R;
    const innerH = H - PAD_T - PAD_B;
    const xOf = (i) => PAD_L + (n === 1 ? innerW / 2 : (i / (n - 1)) * innerW);
    const yOf = (v) => PAD_T + innerH - ((v - min) / (max - min)) * innerH;

    let line = '', area = '';
    points.forEach((p, i) => {
      const x = xOf(i).toFixed(1), y = yOf(p.v).toFixed(1);
      line += (i === 0 ? 'M' : 'L') + x + ' ' + y + ' ';
      if (i === 0) area = 'M' + x + ' ' + (H - PAD_B) + ' L' + x + ' ' + y + ' ';
      else area += 'L' + x + ' ' + y + ' ';
    });
    area += 'L' + xOf(n - 1).toFixed(1) + ' ' + (H - PAD_B) + ' Z';

    const up = points[n - 1].v >= points[0].v;
    const stroke = up ? 'var(--bad)' : 'var(--ok)';
    const fillId = 'g' + Math.random().toString(36).slice(2, 8);
    const last = points[n - 1];

    // 少量网格线 + 首尾日期
    const fmtT = (ms) => {
      const d = new Date(ms);
      return (d.getMonth() + 1) + '/' + d.getDate() + ' ' +
        String(d.getHours()).padStart(2, '0') + ':00';
    };
    const grid = [0.25, 0.5, 0.75].map((r) => {
      const y = (PAD_T + innerH * r).toFixed(1);
      return `<line x1="${PAD_L}" y1="${y}" x2="${W - PAD_R}" y2="${y}"
        stroke="var(--line)" stroke-width="1" stroke-dasharray="3 4"/>`;
    }).join('');

    el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">
      <defs><linearGradient id="${fillId}" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="${stroke}" stop-opacity="0.28"/>
        <stop offset="100%" stop-color="${stroke}" stop-opacity="0"/>
      </linearGradient></defs>
      ${grid}
      <path d="${area}" fill="url(#${fillId})"/>
      <path d="${line}" fill="none" stroke="${stroke}" stroke-width="2"
            stroke-linejoin="round" stroke-linecap="round" vector-effect="non-scaling-stroke"/>
      <circle cx="${xOf(n - 1).toFixed(1)}" cy="${yOf(last.v).toFixed(1)}" r="3.5" fill="${stroke}"/>
      <text x="${PAD_L}" y="${H - 4}" font-size="10" fill="var(--mut-2)">${fmtT(points[0].at)}</text>
      <text x="${W - PAD_R}" y="${H - 4}" font-size="10" fill="var(--mut-2)" text-anchor="end">${fmtT(last.at)}</text>
    </svg>`;
  }

  // 走势图状态
  const fxTrend = { code: "USD", days: 7 };
  const oilTrend = { key: "brent", days: 7 };

  async function loadFxTrend() {
    try {
      const j = await api("/api/fx/history?code=" + encodeURIComponent(fxTrend.code) +
        "&days=" + fxTrend.days);
      const pts = (j.data && j.data.points) || [];
      $("#fxPick").textContent = fxTrend.code + " · " + fxTrend.days + " 天";
      renderSpark($("#fxChart"), pts);
    } catch (e) {
      $("#fxChart").innerHTML = '<div class="chart-empty">走势加载失败</div>';
    }
  }

  async function loadOilTrend() {
    try {
      const j = await api("/api/oil/history?key=" + encodeURIComponent(oilTrend.key) +
        "&days=" + oilTrend.days);
      const pts = (j.data && j.data.points) || [];
      $("#oilPick").textContent = oilTrend.key + " · " + oilTrend.days + " 天";
      renderSpark($("#oilChart"), pts);
    } catch (e) {
      $("#oilChart").innerHTML = '<div class="chart-empty">走势加载失败</div>';
    }
  }

  function bindTrendControls() {
    // ★ 用 document 级事件委托，而不是给容器绑 addEventListener 后再依赖"元素已存在"。
    //   原因：卡片是 loadFx/loadOil 里 innerHTML 重建的，容器本身虽然一直在，
    //   但之前整个 bindTrendControls() 从未被调用 → 点击完全没反应。
    //   委托到 document 上，一次性绑定、永远有效，也不怕重绘。
    document.addEventListener("click", (e) => {
      // 汇率卡片
      const fxItem = e.target.closest("#fxGrid .fx-item");
      if (fxItem) {
        const codeEl = fxItem.querySelector(".fx-code");
        if (codeEl) {
          const c = codeEl.textContent.trim();
          if (c && c !== "CNY") {   // 基准货币没有走势
            fxTrend.code = c;
            $$("#fxGrid .fx-item").forEach((x) => x.classList.remove("picked"));
            fxItem.classList.add("picked");
            loadFxTrend();
          }
        }
        return;
      }

      // 油价卡片
      const oilItem = e.target.closest("#oilCrude .oil-item");
      if (oilItem && oilItem.dataset.key) {
        oilTrend.key = oilItem.dataset.key;
        $$("#oilCrude .oil-item").forEach((x) => x.classList.remove("picked"));
        oilItem.classList.add("picked");
        loadOilTrend();
        return;
      }

      // 汇率区间按钮
      const fxDay = e.target.closest("#fxRange .cr");
      if (fxDay) {
        $$("#fxRange .cr").forEach((x) => x.classList.remove("active"));
        fxDay.classList.add("active");
        fxTrend.days = parseInt(fxDay.dataset.days, 10) || 7;
        loadFxTrend();
        return;
      }

      // 油价区间按钮
      const oilDay = e.target.closest("#oilRange .cr");
      if (oilDay) {
        $$("#oilRange .cr").forEach((x) => x.classList.remove("active"));
        oilDay.classList.add("active");
        oilTrend.days = parseInt(oilDay.dataset.days, 10) || 7;
        loadOilTrend();
        return;
      }
    });
  }

  // ---------- 汇率 ----------
  async function loadFx() {
    try {
      const j = await api("/api/fx");
      const d = j.data || {};
      const list = d.list || [];
      const badge = $("#fxBadge");
      badge.className = "mon-badge ok";
      badge.textContent = "1 人民币 = ? 外币";
      if (j.stale) { badge.className = "mon-badge"; badge.textContent += "（缓存）"; }

      const sp = d.cnhSpread;
      const spTxt = (sp > 0 ? "+" : "") + sp + "%";
      $("#fxSpread").innerHTML =
        `基准货币：<b>在岸人民币 CNY</b> · 离岸 <b>CNH</b> 相对在岸偏离 ` +
        `<b class="${sp > 0 ? "fx-up" : "fx-down"}">${spTxt}</b>` +
        ` · 更新于 ${timeAgo(d.updatedAt)}`;

      $("#fxGrid").innerHTML = list.map((x) => {
        const chg = Number(x.changePct || 0);
        const cls = chg > 0.001 ? "fx-up" : (chg < -0.001 ? "fx-down" : "fx-flat");
        const sign = chg > 0 ? "+" : "";
        const isBase = x.code === "CNY";
        // 基准为国：主数字显示「100 元兑换多少该外币」，比 0.15 这类小数好读
        const main = isBase ? "100" : String(x.per100);
        const unitHint = isBase ? "在岸人民币（基准）" : "100 元可兑";
        const picked = (!isBase && x.code === fxTrend.code) ? " picked" : "";
        return `<div class="fx-item${isBase ? " fx-base" : ""}${picked}">
          <div class="fx-head"><span>${x.flag || ""}</span><span>${esc(x.name)}</span>
            <span class="fx-code">${esc(x.code)}</span></div>
          <div class="fx-rate">${main}</div>
          <div class="fx-chg ${isBase ? "fx-flat" : cls}">${unitHint}${(!isBase && chg) ? " · " + sign + chg + "%" : ""}</div>
        </div>`;
      }).join("");

      // 走势图（基准 CNY 没有走势，默认看 USD）
      if (fxTrend.code !== "CNY") loadFxTrend();
    } catch (e) {
      $("#fxBadge").className = "mon-badge bad";
      $("#fxBadge").textContent = "获取失败";
    }
  }

  // ---------- 油价 ----------
  async function loadOil() {
    try {
      const j = await api("/api/oil");
      const d = j.data || {};
      const badge = $("#oilBadge");
      badge.className = "mon-badge ok";
      badge.textContent = "实时";
      if (j.stale) { badge.className = "mon-badge"; badge.textContent = "缓存"; }

      const crude = d.crude || [];
      $("#oilCrude").innerHTML = crude.map((x) => {
        const chg = Number(x.changePct || 0);
        const cls = chg > 0.001 ? "fx-up" : (chg < -0.001 ? "fx-down" : "fx-flat");
        const sign = chg > 0 ? "+" : "";
        const picked = x.key === oilTrend.key ? " picked" : "";
        return `<div class="oil-item${picked}" data-key="${esc(x.key)}">
          <div class="oi-name">${esc(x.name)}</div>
          <div class="oi-price">${x.price}</div>
          <div class="oi-unit">${esc(x.unit)}</div>
          <div class="oi-chg ${cls}">${sign}${chg}%</div>
        </div>`;
      }).join("") || '<div class="empty">原油报价暂不可用</div>';

      // 走势图
      loadOilTrend();

      const prov = d.domestic || [];
      const body = $("#oilBody");
      if (!prov.length) {
        body.innerHTML = '<tr><td colspan="4" class="dim" style="text-align:center;padding:22px">' +
          '国内零售价暂不可用' + (d.domesticErr ? "（" + esc(d.domesticErr) + "）" : "") + '</td></tr>';
      } else {
        body.innerHTML = prov.map((p) => `<tr>
          <td>${esc(p.region)}</td>
          <td class="num">${p.p92 || "—"}</td>
          <td class="num">${p.p95 || "—"}</td>
          <td class="num">${p.p0 || "—"}</td>
        </tr>`).join("");
      }
    } catch (e) {
      $("#oilBadge").className = "mon-badge bad";
      $("#oilBadge").textContent = "获取失败";
    }
  }

  // ---------- 台风路径 ----------
  let tyFrameSet = false;
  async function loadTyphoon() {
    try {
      const j = await api("/api/typhoon");
      const d = j.data || {};
      const active = d.active || [];
      const all = d.all || [];
      const badge = $("#tyBadge");
      if (active.length) {
        badge.className = "mon-badge bad";
        badge.textContent = active.length + " 个活跃台风";
      } else {
        badge.className = "mon-badge ok";
        badge.textContent = "当前无活跃台风";
      }

      // 活跃优先，其次最近编号（列表尾部是最新）
      let chips = active.map((t) => `<span class="ty-chip active">${esc(t.nameCn)}
        <small>${esc(t.nameEn)}${t.num ? " · " + esc(t.num) + "号" : ""}</small></span>`);
      if (!chips.length) {
        const recent = all.slice(-6).reverse();
        chips = recent.map((t) => `<span class="ty-chip">${esc(t.nameCn)}
          <small>${esc(t.nameEn)}</small></span>`);
        if (chips.length) chips.unshift('<span class="ty-chip">近 6 个台风</span>');
      }
      $("#tyList").innerHTML = chips.join("");

      if (!tyFrameSet && d.embedUrl) {
        $("#tyFrame").src = d.embedUrl;
        tyFrameSet = true;
      }
    } catch (e) {
      $("#tyBadge").className = "mon-badge bad";
      $("#tyBadge").textContent = "获取失败";
      // 接口失败也让地图可用
      if (!tyFrameSet) { $("#tyFrame").src = "https://typhoon.nmc.cn/web.html"; tyFrameSet = true; }
    }
  }

  // ---------- 短视频去水印 ----------
  let wmBound = false;
  let wmChannelLoader = null;

  function refreshChannel() {
    if (wmChannelLoader) wmChannelLoader();
  }

  function bindWatermarkOnce() {
    if (wmBound) return;
    wmBound = true;

    const input = $("#wmInput");
    const parseBtn = $("#wmParse");
    const badge = $("#wmBadge");

    // 粘贴按钮：优先用剪贴板 API，失败让用户手动长按粘贴
    $("#wmPaste").addEventListener("click", async () => {
      try {
        const t = await navigator.clipboard.readText();
        if (t) {
          input.value = t;
          toast("已粘贴");
        } else {
          toast("剪贴板是空的");
        }
      } catch (e) {
        // 浏览器不给读剪贴板（无 HTTPS / 未授权）时退回手动
        input.focus();
        toast("请长按输入框手动粘贴");
      }
    });

    $("#wmClear").addEventListener("click", () => {
      input.value = "";
      $("#wmResult").classList.add("hidden");
      badge.className = "mon-badge";
      badge.textContent = "就绪";
      input.focus();
    });

    parseBtn.addEventListener("click", doParse);

    // ---- 通道状态：显示解析引擎是否就绪 ----
    async function loadChannel() {
      try {
        const j = await api("/api/watermark/status");
        const d = j.data || {};
        const box = $("#wmChannel");
        const txt = $("#wmChannelTxt");
        box.classList.remove("hidden");
        if (d.douyinReady) {
          txt.innerHTML = '<span class="ok">✓ 解析服务已就绪</span> · ' +
            esc(d.engine || "") + ' · B站/抖音/TikTok 均可直接下载';
        } else {
          txt.innerHTML = '<span class="warn">解析服务未就绪</span> · ' +
            esc(d.note || "") + '（B站走自研，不受影响）';
        }
      } catch (e) {
        /* 状态接口失败不影响主流程 */
      }
    }
    wmChannelLoader = loadChannel;
    loadChannel();

    function pickImage(f) {
      if (!/^image\//.test(f.type) && !/\.(png|jpe?g|gif|bmp|webp)$/i.test(f.name)) {
        setImgInfo("请选择图片文件", "err");
        return;
      }
      if (f.size > 20 * 1024 * 1024) {
        setImgInfo("文件超过 20MB，请先压缩", "err");
        return;
      }
      cvFileObj = f;
      $("#cvDropTxt").textContent = f.name + "（" + (f.size / 1024).toFixed(0) + " KB）";
      setImgInfo("已选择，点「开始转换」", "");
    }

    function setImgInfo(msg, cls) {
      const el = $("#cvImgInfo");
      el.textContent = msg;
      el.className = "cv-note" + (cls ? " " + cls : "");
    }

    $("#cvQuality").addEventListener("input", (e) => {
      $("#cvQVal").textContent = e.target.value;
    });

    $("#cvImgGo").addEventListener("click", async () => {
      if (!cvFileObj) { setImgInfo("请先选择图片", "err"); return; }
      const btn = $("#cvImgGo");
      btn.disabled = true;
      const old = btn.textContent;
      btn.textContent = "转换中…";
      setImgInfo("正在转换…", "");
      try {
        const fd = new FormData();
        fd.append("file", cvFileObj);
        fd.append("to", $("#cvImgTo").value);
        fd.append("quality", $("#cvQuality").value);
        const res = await fetch(API + "/api/convert/image", { method: "POST", body: fd });
        if (!res.ok) {
          let msg = "转换失败";
          try { const j = await res.json(); msg = j.error || msg; } catch (e) {}
          throw new Error(msg);
        }
        const blob = await res.blob();
        const srcFmt = res.headers.get("X-Source-Format") || "?";
        const size = res.headers.get("X-Image-Size") || "?";
        const url = URL.createObjectURL(blob);
        const dl = $("#cvDownload");
        dl.href = url;
        const name = (cvFileObj.name.replace(/\.[^.]+$/, "") || "converted") + "." +
                     ($("#cvImgTo").value === "jpg" ? "jpg" : $("#cvImgTo").value);
        dl.download = name;
        dl.style.display = "inline-block";
        setImgInfo(`转换成功：${srcFmt} → ${$("#cvImgTo").value.toUpperCase()}，` +
          `尺寸 ${size}，${(blob.size / 1024).toFixed(0)} KB`, "ok");
        $("#cvBadge").className = "mon-badge ok";
        $("#cvBadge").textContent = "转换成功";
      } catch (e) {
        setImgInfo(e.message || "转换失败", "err");
        $("#cvBadge").className = "mon-badge bad";
        $("#cvBadge").textContent = "失败";
      } finally {
        btn.disabled = false;
        btn.textContent = old;
      }
    });

    // ---- 数据格式 ----
    $("#cvDataGo").addEventListener("click", async () => {
      const text = $("#cvDataIn").value;
      if (!text.trim()) { setDataInfo("请输入内容", "err"); return; }
      const from = $("#cvFrom").value, to = $("#cvTo").value;
      if (from === to) { setDataInfo("源格式和目标格式相同", "err"); return; }
      setDataInfo("转换中…", "");
      try {
        const j = await api("/api/convert/data", { method: "POST", body: { from, to, text } });
        const d = j.data || {};
        $("#cvDataOut").value = d.result || "";
        setDataInfo(`完成：${from.toUpperCase()} → ${to.toUpperCase()}` +
          (d.rows ? `，${d.rows} 行 ${d.cols} 列` : ""), "ok");
      } catch (e) {
        setDataInfo(e.message || "转换失败", "err");
      }
    });

    function setDataInfo(msg, cls) {
      const el = $("#cvDataInfo");
      el.textContent = msg;
      el.className = "cv-note" + (cls ? " " + cls : "");
    }

    $("#cvCopy").addEventListener("click", () => copyText($("#cvDataOut").value));

    // ---- 编码 ----
    $("#cvCodecGo").addEventListener("click", async () => {
      const text = $("#cvCodecIn").value;
      if (!text) { toast("请输入内容"); return; }
      try {
        const j = await api("/api/convert/codec", {
          method: "POST", body: { op: $("#cvOp").value, text },
        });
        $("#cvCodecOut").value = (j.data && j.data.result) || "";
      } catch (e) {
        toast(e.message || "执行失败");
      }
    });
    $("#cvCopy2").addEventListener("click", () => copyText($("#cvCodecOut").value));

    function copyText(t) {
      if (!t) { toast("没有可复制的内容"); return; }
      try {
        navigator.clipboard.writeText(t).then(
          () => toast("已复制"),
          () => toast("复制失败，请手动选择")
        );
      } catch (e) {
        // 退回到选中
        const ta = document.createElement("textarea");
        ta.value = t;
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand("copy"); toast("已复制"); } catch (e2) { toast("请手动复制"); }
        document.body.removeChild(ta);
      }
    }
  }

  // ---------- 管理员后台 ----------
  let admTab = "users";
  let admData = null;

  /** 管理员入口：插在 #adminSlot 里（导航最末的独立分组），不再 append 到末尾乱飘 */
  function paintAdminEntry() {
    const u = currentUser();
    const show = !!(token() && u && u.isAdmin);
    const slot = $("#adminSlot");
    if (!slot) return;
    slot.classList.toggle("hidden", !show);

    const btn = $("#adminEntry");
    if (btn && !btn._bound) {
      btn._bound = true;
      btn.addEventListener("click", openAdmin);
    }
  }

  async function openAdmin() {
    if (!token()) { openAuth("login"); return; }
    const u = currentUser();
    if (!u || !u.isAdmin) { toast("需要管理员权限"); return; }

    $("#adminSheet").classList.remove("hidden");
    $("#adminBody").innerHTML = '<div class="loading">加载中…</div>';
    $("#adminStats").innerHTML = "";
    await reloadAdmin();
  }

  async function reloadAdmin() {
    try {
      const j = await api("/api/admin/overview");
      admData = j.data || {};
      renderAdminStats(admData.stats || {});
      renderAdminList();
    } catch (e) {
      // 403（token 有效但非管理员）/ 其它错误：关掉弹层，避免「弹层开着但一片空白」
      $("#adminSheet").classList.add("hidden");
      toast(e.message || "无权访问管理员后台");
    }
  }

  function renderAdminStats(st) {
    $("#adminStats").innerHTML = `
      <div class="mon-card"><div class="k">注册用户</div><div class="v">${st.users || 0}</div></div>
      <div class="mon-card"><div class="k">帖子总数</div><div class="v">${st.posts || 0}</div></div>
      <div class="mon-card"><div class="k">评论数</div><div class="v">${st.comments || 0}</div></div>
      <div class="mon-card"><div class="k">今日新增</div><div class="v">${st.todayUsers || 0}</div></div>`;
  }

  /** 按当前 tab + 搜索词渲染列表 */
  function renderAdminList() {
    if (!admData) return;
    const kw = ($("#admSearch") ? $("#admSearch").value : "").trim().toLowerCase();
    const box = $("#adminBody");
    const meId = (admData.me && admData.me.id) || 0;

    const hit = (arr, fields) => {
      if (!kw) return arr;
      return arr.filter((x) => fields.some((f) =>
        String(x[f] == null ? "" : x[f]).toLowerCase().indexOf(kw) >= 0));
    };

    if (admTab === "users") {
      const list = hit(admData.users || [], ["nick", "email"]);
      if (!list.length) { box.innerHTML = '<div class="empty">没有匹配的用户</div>'; return; }
      box.innerHTML = '<div class="adminlist">' + list.map((u) => {
        const self = u.id === meId;
        return `<div class="adm-row">
          <span class="adm-main">${esc(u.nick || "—")}<i>${esc(u.email || "")} · 注册于 ${timeAgo(u.createdAt)}</i></span>
          <span class="adm-tag ${u.isAdmin ? "on" : ""}">${u.isAdmin ? "管理员" : "普通"}</span>
          ${self
            ? '<span class="adm-tag">当前账号</span>'
            : `<button class="adm-btn" data-uid="${u.id}" data-act="toggle">${u.isAdmin ? "取消管理" : "设为管理"}</button>
               <button class="adm-btn" data-uid="${u.id}" data-act="resetpw">重置密码</button>`}
        </div>`;
      }).join("") + '</div>';
    } else if (admTab === "posts") {
      const list = hit(admData.posts || [], ["title", "body", "author"]);
      if (!list.length) { box.innerHTML = '<div class="empty">没有匹配的帖子</div>'; return; }
      box.innerHTML = '<div class="adminlist">' + list.map((p) => `<div class="adm-row">
        <span class="adm-main">${esc((p.title || p.body || "（无正文）").slice(0, 50))}<i>${esc(p.author || "匿名")} · ${timeAgo(p.createdAt)}</i></span>
        <button class="adm-btn danger" data-pid="${p.id}" data-act="delpost">删除</button>
      </div>`).join("") + '</div>';
    } else {
      const list = hit(admData.comments || [], ["body", "author", "postTitle"]);
      if (!list.length) { box.innerHTML = '<div class="empty">没有匹配的评论</div>'; return; }
      box.innerHTML = '<div class="adminlist">' + list.map((c) => `<div class="adm-row">
        <span class="adm-main">${esc((c.body || "").slice(0, 50))}<i>${esc(c.author || "匿名")} · 在《${esc(c.postTitle || "帖子")}》· ${timeAgo(c.createdAt)}</i></span>
        <button class="adm-btn danger" data-cid="${c.id}" data-act="delcomment">删除</button>
      </div>`).join("") + '</div>';
    }

    // 事件绑定（每次重绘都要重新绑）
    $$('#adminBody [data-act="toggle"]').forEach((b) => b.addEventListener("click", async () => {
      b.disabled = true;
      try {
        await api("/api/admin/user/" + b.dataset.uid + "/role", { method: "POST" });
        toast("已更新角色");
        await reloadAdmin();
      } catch (e) { toast(e.message || "操作失败"); b.disabled = false; }
    }));
    $$('#adminBody [data-act="delpost"]').forEach((b) => b.addEventListener("click", async () => {
      if (!confirm("确认删除这条帖子？其评论与点赞会一并删除。")) return;
      b.disabled = true;
      try {
        await api("/api/admin/post/" + b.dataset.pid, { method: "DELETE" });
        toast("已删除");
        await reloadAdmin();
      } catch (e) { toast(e.message || "删除失败"); b.disabled = false; }
    }));
    $$('#adminBody [data-act="resetpw"]').forEach((b) => b.addEventListener("click", async () => {
      const pw = prompt("为该用户设置新密码（至少 6 位）：\n\n邮件收不到时的兜底通道，设置后请线下告知对方。");
      if (pw == null) return;
      if (pw.trim().length < 6) { toast("密码至少 6 位"); return; }
      b.disabled = true;
      try {
        const j = await api("/api/admin/user/" + b.dataset.uid + "/password", {
          method: "POST", body: { password: pw.trim() },
        });
        toast((j.data && j.data.message) || "已重置密码");
      } catch (e) { toast(e.message || "重置失败"); b.disabled = false; }
    }));
    $$('#adminBody [data-act="delcomment"]').forEach((b) => b.addEventListener("click", async () => {
      if (!confirm("确认删除这条评论？")) return;
      b.disabled = true;
      try {
        await api("/api/admin/comment/" + b.dataset.cid, { method: "DELETE" });
        toast("已删除");
        await reloadAdmin();
      } catch (e) { toast(e.message || "删除失败"); b.disabled = false; }
    }));
  }

  // 管理员弹层内的交互
  $$(".adm-tab").forEach((b) => b.addEventListener("click", () => {
    $$(".adm-tab").forEach((x) => x.classList.remove("active"));
    b.classList.add("active");
    admTab = b.dataset.admtab;
    renderAdminList();
  }));
  const admSearchEl = $("#admSearch");
  if (admSearchEl) {
    admSearchEl.addEventListener("input", () => renderAdminList());
  }
  const admRefreshEl = $("#admRefresh");
  if (admRefreshEl) {
    admRefreshEl.addEventListener("click", () => { toast("已刷新"); reloadAdmin(); });
  }

  // ---------- 启动 ----------
  paintAccount();
  paintAdminEntry();
  loadWeather();
  bindWeatherSwitch();
  loadFeed();
  loadTags();
  loadRSSCats();
  bindRSSActions();
  bindTrendControls();
  startHeartbeat();
  startMonitor();
  connectWS();

  // 后台预取行情数据：用户点「汇率/油价/台风」时直接出内容，不用等
  (function prefetchMarkets() {
    const run = (fn) => { try { fn(); } catch (e) {} };
    setTimeout(() => run(loadFx), 800);
    setTimeout(() => run(loadOil), 1600);
    setTimeout(() => run(loadTyphoon), 2400);
  })();

  // ?v=fx / ?v=oil / ?v=typhoon 直达某个视图（便于分享与排查）
  (function openViewParam() {
    let v = "";
    try { v = new URLSearchParams(location.search).get("v") || ""; } catch (e) {}
    if (v && VIEW_TITLES[v]) switchView(v);
  })();

  // 邮件重置链接落地：/community/?reset=TOKEN
  (function handleResetLink() {
    let tok = "";
    try { tok = new URLSearchParams(location.search).get("reset") || ""; } catch (e) {}
    if (tok) { openReset(tok, ""); }
  })();

  // 站内深链：/community?post=123 直接打开该帖
  (function openDeepLink() {
    let pid = 0;
    try {
      pid = parseInt(new URLSearchParams(location.search).get("post") || "0", 10) || 0;
    } catch (e) { pid = 0; }
    if (!pid) {
      const m = location.hash.match(/^#post=(\d+)$/);
      if (m) pid = parseInt(m[1], 10) || 0;
    }
    if (pid > 0) setTimeout(() => openDetail(pid), 120);
  })();
  if (token()) {
    api("/api/auth/me").then((j) => {
      if (j && j.data) { setAuth(null, j.data); paintAdminEntry(); }
    }).catch(() => {});
  }
  if ("serviceWorker" in navigator) {
    // 注册根级 sw.js（作用域 /community/），可接管页面导航的离线兜底
    navigator.serviceWorker.register("sw.js", { scope: "/community/" }).catch(() => {
      // 回落旧路径，保证老客户端也能注册成功
      navigator.serviceWorker.register("assets/sw.js").catch(() => {});
    });
  }
})();
