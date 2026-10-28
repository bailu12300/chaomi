// 超米 SPA —— 文字/图片/链接/公众号图文，实时 WebSocket
(function () {
  "use strict";
  const API = ""; // 同源
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

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
  /** 「广东 · 3 小时前」；归属地缺失时只显示时间 */
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
  }
  function clearAuth() {
    localStorage.removeItem("chaomi_token");
    localStorage.removeItem("chaomi_user");
    paintAccount();
  }
  /** 统一请求：自动带 token，401 弹登录 */
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
    if (r.status === 401) { openAuth(); throw new Error((j && j.error) || "请先登录"); }
    if (!j) throw new Error("响应异常");
    if (j.code !== 0 && j.error) throw new Error(j.error);
    return j;
  }
  function paintAccount() {
    const b = $("#accBtn");
    if (!b) return;
    const u = currentUser();
    if (token() && u) {
      b.textContent = u.nick || "我";
      b.classList.add("on");
    } else {
      b.textContent = "登录";
      b.classList.remove("on");
    }
    if ($("#nickEcho")) $("#nickEcho").textContent = nick();
  }

  let authReg = false;
  function openAuth() {
    $("#authSheet").classList.remove("hidden");
  }
  function applyAuthMode() {
    $("#authTitle").textContent = authReg ? "注册" : "登录";
    $("#authSubmit").textContent = authReg ? "注册并登录" : "登录";
    $("#authSwitch").textContent = authReg ? "已有账号？去登录" : "还没有账号？去注册";
    $("#authNick").classList.toggle("hidden", !authReg);
  }
  $("#accBtn").addEventListener("click", () => {
    const u = currentUser();
    if (!token() || !u) { authReg = false; applyAuthMode(); openAuth(); return; }
    if (confirm("已登录：" + u.nick + "（" + (u.email || "") + "）\n\n确定退出登录？")) {
      fetch(API + "/api/auth/logout", {
        method: "POST", headers: { Authorization: "Bearer " + token() },
      }).catch(() => {});
      clearAuth();
      loadFeed();
      toast("已退出登录");
    }
  });
  $("#authSwitch").addEventListener("click", () => { authReg = !authReg; applyAuthMode(); });
  $("#authSubmit").addEventListener("click", async () => {
    const email = $("#authEmail").value.trim();
    const pass = $("#authPass").value;
    const nk = $("#authNick").value.trim();
    if (!email || email.indexOf("@") < 0) { toast("请填写正确邮箱"); return; }
    if (pass.length < 6) { toast("密码至少 6 位"); return; }
    const btn = $("#authSubmit");
    btn.disabled = true;
    try {
      const j = await api(authReg ? "/api/auth/register" : "/api/auth/login", {
        method: "POST",
        body: authReg ? { email, password: pass, nick: nk } : { email, password: pass },
      });
      setAuth(j.data.token, j.data.user);
      $("#authSheet").classList.add("hidden");
      $("#authPass").value = "";
      toast("欢迎，" + (j.data.user && j.data.user.nick ? j.data.user.nick : "你"));
      loadFeed();
      loadTags();
    } catch (e) {
      toast(e.message || "操作失败");
    } finally {
      btn.disabled = false;
    }
  });

  // ---------- 视图切换 ----------
  $$(".tab").forEach((t) => t.addEventListener("click", () => {
    if (t.id === "openComposer") { openComposer(); return; }
    $$(".tab").forEach((x) => x.classList.remove("active"));
    t.classList.add("active");
    const v = t.dataset.view;
    $$(".view").forEach((x) => x.classList.remove("active"));
    $("#" + v).classList.add("active");
    if (v === "rss") loadRSS(true);
  }));

  // ========== 天气卡片 ==========
  // 定位优先级：浏览器定位 > 上次选的城市 > 服务端按 IP 定位
  const WX_KEY = "chaomi_wx_city";

  function loadWeather() {
    const saved = localStorage.getItem(WX_KEY) || "";
    const done = (query) => {
      api("/api/weather" + query).then((j) => {
        if (j && j.data) paintWeather(j.data);
      }).catch(() => {
        $(".wxtext").textContent = "天气获取失败";
      });
    };

    if (saved) { done("?city=" + encodeURIComponent(saved)); return; }

    // 尝试浏览器定位（需用户授权），失败则退回服务端 IP 定位
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
    const box = $("#weatherCard");
    if (!box) return;
    box.dataset.city = d.city || "";
    $(".wxcity", box).textContent = d.city
      ? (d.region && d.region !== d.city ? `${d.city} · ${d.region}` : d.city)
      : "当前位置";
    const n = d.now || {};
    $(".wxicon", box).textContent = n.icon || "🌡️";
    $(".wxtemp b", box).textContent = n.tempC == null ? "--" : Math.round(n.tempC);
    $(".wxtext", box).textContent = n.text || "";
    $(".wxsub", box).textContent =
      `体感 ${Math.round(n.feelsC == null ? 0 : n.feelsC)}° · 湿度 ${n.humidity || 0}% · 风 ${Math.round(n.windKph || 0)}km/h`;

    const days = (d.days || []).slice(0, 5);
    $("#wxDays", box).innerHTML = days.map((x, i) => {
      const label = i === 0 ? "今天" : weekdayOf(x.date);
      return `<div class="wxday">
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
    const btn = $("#wxSwitch");
    if (!btn) return;
    btn.addEventListener("click", () => {
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
      $("#rssEmpty").style.display = box.children.length ? "none" : "block";
      $("#rssMore").style.display = rssState.hasMore ? "block" : "none";
      if (added === 0 && rssState.hasMore) { rssState.loading = false; loadRSS(false); return; }
    } catch (e) {
      $("#rssEmpty").style.display = "block";
      $("#rssEmpty").textContent = "资讯加载失败：" + (e.message || "网络错误");
    } finally {
      rssState.loading = false;
      $("#rssLoading").classList.add("hidden");
    }
  }

  function rssCard(it) {
    // 有译文优先显示译文，原文作为副标题（离线也能读）
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
    // 点卡片用系统浏览器打开原文（服务端已抓过，这里给的是原站链接）
    $("#rssList").addEventListener("click", (e) => {
      const card = e.target.closest(".rsscard");
      if (!card) return;
      const link = card.dataset.link;
      if (link) window.open(link, "_blank", "noopener");
    });
  }

  // ---------- 信息流 ----------
  const cards = new Map(); // id -> element
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
        $("#feedEmpty").style.display = "block";
        $("#feedEmpty").textContent = feedState.mine ? "你还没发过内容"
          : (feedState.q || feedState.tag) ? "没有匹配的内容" : "还没有内容，点「＋ 发布」发第一条吧。";
        return;
      }
      $("#feedEmpty").style.display = "none";
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
      // 优先用原生块预览，比 webview 正文更干净
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
      ${mine ? `<button data-act="del" style="color:var(--up)">删除</button>` : ""}
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

  /** 文章块 → 精简 HTML（列表页只取前几块） */
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
    // 文章：原生块完整渲染
    if (p.blocks && p.blocks.length) {
      html += `<div class="article-body">${renderBlocks(p.blocks)}</div>`;
    }
    if (p.tags && p.tags.length) {
      html += `<div class="tags">${p.tags.map((t) => `<span class="tagc">#${esc(t)}</span>`).join("")}</div>`;
    }
    if (p.url) html += `<a class="linkcard" href="${esc(p.url)}" target="_blank" rel="noopener"><div class="lu">原文 ↗</div></a>`;
    html += `<div class="actions">
      <button class="like ${p.liked ? "liked" : ""}" data-act="like">♥ <span class="lc">${p.likes || 0}</span></button>
      ${mine ? `<button data-act="del" style="color:var(--up)">删除这条</button>` : ""}
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

  /** 文章块完整渲染 */
  /**
   * 行内标记渲染：**粗** *斜* ==高亮== ~~删除~~ `代码`
   * 必须先转义再套标签，否则用户输入的 < > 会破坏结构。
   */
  function inline(raw) {
    let s = esc(raw == null ? "" : raw);
    // 顺序：成对双符号优先，避免 * 抢走 **
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
  function openComposer() {
    if (!token()) { toast("请先登录再发布"); openAuth(); return; }
    $("#composer").classList.remove("hidden");
  }
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
      switchFeed();
      const box = $("#postList"); box.insertBefore(renderPost(j.data), box.firstChild);
      $("#feedEmpty").style.display = "none";
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
      // 关键：保留 blocks 而不是 HTML，发布后是原生文章
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

  // 行情模块已移除

  // ---------- 节点心跳：每 3 秒一次（20 次/分钟） ----------
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
      // 扣掉服务端处理耗时，得到近似网络往返
      if (cost > 0 && cost < rtt) rtt -= cost;
      if (rtt < 0) rtt = 0;

      hb.sent++;
      hb.samples.push(rtt);
      if (hb.samples.length > 40) hb.samples.shift();

      const chip = $("#latChip");
      const txt = $("#latText");
      if (txt) txt.textContent = rtt + " ms";
      if (chip) {
        chip.className = "latchip " + latClass(rtt);
        chip.title = `App ↔ 服务器延迟 ${rtt}ms\n心跳 ${hb.sent} 次 · 每 3 秒 1 次（20 次/分钟）`
          + `\n丢包 ${hb.lost} 次`;
      }
      // 回传样本供服务端统计（失败不影响 UI）
      fetch(API + "/api/ping/report", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ rtt }),
      }).catch(() => {});
    } catch (e) {
      hb.sent++;
      hb.lost++;
      const chip = $("#latChip");
      const txt = $("#latText");
      if (txt) txt.textContent = "超时";
      if (chip) chip.className = "latchip bad";
    }
  }

  function startHeartbeat() {
    if (hb.timer) return;
    heartbeat();
    hb.timer = setInterval(heartbeat, 3000); // 20 次/分钟
  }

  // 页面切到后台时降频，省电也省流量；回来立刻补一次
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
    ws.onopen = () => $("#wsdot").classList.replace("off", "on");
    ws.onclose = () => { $("#wsdot").classList.replace("on", "off"); setTimeout(connectWS, 3000); };
    ws.onmessage = (ev) => {
      try {
        const m = JSON.parse(ev.data);
        if (m.type === "new_post" && m.post) {
          const box = $("#postList"); box.insertBefore(renderPost(m.post), box.firstChild);
          $("#feedEmpty").style.display = "none";
          if (!$("#feed").classList.contains("active")) toast("有新帖子");
        } else if (m.type === "like" && cards.has(m.postId)) {
          const lc = cards.get(m.postId).querySelector(".lc"); if (lc) lc.textContent = m.likes;
        } else if (m.type === "new_comment" && m.postId === curDetailId) {
          openDetail(m.postId); bumpComment(m.postId);
        }
      } catch (e) {}
    };
  }

  function switchFeed() {
    $$(".tab").forEach((x) => x.classList.toggle("active", x.dataset.view === "feed"));
    $$(".view").forEach((x) => x.classList.remove("active"));
    $("#feed").classList.add("active");
  }

  // ---------- 启动 ----------
  paintAccount();
  loadWeather();
  bindWeatherSwitch();
  loadFeed();
  loadTags();
  loadRSSCats();
  bindRSSActions();
  startHeartbeat();
  connectWS();

  // 站内深链：/community?post=123 直接打开该帖（用于「引用旧文章」跳转）
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
  // 有 token 时校验一次，失效则静默清掉
  if (token()) {
    api("/api/auth/me").then((j) => {
      if (j && j.data) setAuth(null, j.data);
    }).catch(() => {});
  }
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("assets/sw.js").catch(() => {});
  }
})();
