// 早八社区 SPA —— 文字/图片/链接/公众号图文，实时 WebSocket
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
  function toast(msg) {
    const t = $("#toast");
    t.textContent = msg; t.classList.remove("hidden");
    clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.add("hidden"), 2200);
  }
  function nick() {
    const u = ls("zaoba_user");
    if (u && u.nick) return u.nick;
    let n = $("#nickEcho") ? $("#nickEcho").textContent.trim() : "";
    return n && n !== "匿名" ? n : "匿名";
  }

  // ---------- 账号 ----------
  function ls(k) {
    try { return JSON.parse(localStorage.getItem(k) || "null"); } catch (e) { return null; }
  }
  function token() { return localStorage.getItem("zaoba_token") || ""; }
  function currentUser() { return ls("zaoba_user"); }
  function setAuth(t, u) {
    if (t) localStorage.setItem("zaoba_token", t);
    if (u) localStorage.setItem("zaoba_user", JSON.stringify(u));
    paintAccount();
  }
  function clearAuth() {
    localStorage.removeItem("zaoba_token");
    localStorage.removeItem("zaoba_user");
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
    if (v === "market") loadMarket();
  }));

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
      <div class="time">${timeAgo(p.createdAt)} · <span class="badge">${typeBadge(p.type)}</span></div></div>
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
      <div class="time">${timeAgo(p.createdAt)} · ${p.views || 0} 次浏览</div></div></div>`;
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
      html += `<div class="c"><span class="cn">${esc(c.author || "匿名")}</span>
        <span>${esc(c.body)}</span>
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
  function renderBlocks(blocks) {
    return blocks.map((b) => {
      if (!b) return "";
      switch (b.type) {
        case "h2": return `<h2>${esc(b.text || "")}</h2>`;
        case "h3": return `<h3>${esc(b.text || "")}</h3>`;
        case "quote": return `<blockquote>${esc(b.text || "")}</blockquote>`;
        case "code": return `<pre>${esc(b.text || "")}</pre>`;
        case "li": return `<div class="li">• ${esc(b.text || "")}</div>`;
        case "img": return b.src ? `<img src="${esc(b.src)}" loading="lazy" alt="${esc(b.alt || "")}">` : "";
        default: return b.text ? `<p>${esc(b.text)}</p>` : "";
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

  // ---------- 硬件行情（品类 / 搜索 / 排序 / 涨跌榜 / 走势） ----------
  const hw = { cat: "", q: "", sort: "" };

  async function loadCats() {
    try {
      const r = await fetch(API + "/api/hw/cats");
      const j = await r.json();
      const cats = (j.data && j.data.cats) || [];
      const box = $("#hwCats");
      box.innerHTML = [`<div class="chip active" data-cat="">全部</div>`]
        .concat(cats.map((c) => `<div class="chip" data-cat="${esc(c.cat)}">${esc(c.cat)} ${c.count}</div>`))
        .join("");
      $$(".chip", box).forEach((el) => el.addEventListener("click", () => {
        $$(".chip", box).forEach((x) => x.classList.remove("active"));
        el.classList.add("active");
        hw.cat = el.dataset.cat || "";
        loadMarket();
      }));
    } catch (e) {}
  }

  async function loadMarket() {
    const box = $("#marketList");
    $("#marketEmpty").style.display = "block";
    $("#marketEmpty").textContent = "加载中…";
    try {
      let url;
      if (hw.sort === "change") {
        url = API + "/api/hw/movers?limit=60" + (hw.cat ? "&cat=" + encodeURIComponent(hw.cat) : "");
      } else {
        url = API + "/api/hw/models?limit=300"
          + (hw.cat ? "&cat=" + encodeURIComponent(hw.cat) : "")
          + (hw.q ? "&q=" + encodeURIComponent(hw.q) : "")
          + (hw.sort ? "&sort=" + hw.sort : "");
      }
      const [r, sr] = await Promise.all([fetch(url), fetch(API + "/api/hw/status")]);
      const j = await r.json();
      const sj = await sr.json().catch(() => ({}));
      const models = (j.data && (j.data.models || j.data.movers)) || [];
      const st = (sj.data || {});

      if (hw.sort === "change") {
        $("#marketHint").textContent = models.length
          ? `涨跌榜 · 仅展示合理波动（±40% 内）· ${models.length} 条`
          : "涨跌榜 · 本轮价格无变化，采集积累后自动出现";
      } else {
        $("#marketHint").textContent = `共 ${st.count || 0} 个型号`
          + (st.rounds ? ` · 已采集 ${st.rounds} 轮` : "")
          + (st.updatedAt ? ` · 更新 ${timeAgo(st.updatedAt)}` : "")
          + (hw.cat ? ` · ${hw.cat}` : "")
          + (hw.q ? ` · 搜索「${hw.q}」` : "");
      }

      if (!models.length) {
        box.innerHTML = "";
        $("#marketEmpty").textContent = hw.sort === "change" ? "本轮价格无变化" : "暂无行情";
        return;
      }
      $("#marketEmpty").style.display = "none";
      box.innerHTML = models.map((m, i) => {
        const price = m.price == null ? "暂无准确报价" : "¥" + Number(m.price).toLocaleString();
        const cls = m.trend === "up" ? "up" : m.trend === "down" ? "down" : "flat";
        const chg = m.hasChange && m.changePct
          ? `<span class="mchg ${cls}">${m.changePct > 0 ? "+" : ""}${Number(m.changePct).toFixed(2)}%</span>` : "";
        return `<div class="mcard" data-i="${i}"><div class="mname">${esc(m.model || m.key)}</div>
          <div class="mspec">${esc(m.spec || "")} · ${esc(m.catLabel || "")}</div>
          <div class="mprice ${cls}">${price}${chg}</div>
          <div class="meta">${esc(m.platform || "")} · ${esc(m.shop || "")}</div></div>`;
      }).join("");
      $$(".mcard", box).forEach((el) => el.addEventListener("click", () => {
        openModel(models[Number(el.dataset.i)]);
      }));
    } catch (e) {
      $("#marketEmpty").style.display = "block";
      $("#marketEmpty").textContent = "行情加载失败";
    }
  }

  $("#hwSearch").addEventListener("keydown", (e) => {
    if (e.key === "Enter") { hw.q = e.target.value.trim(); loadMarket(); }
  });
  $$(".st").forEach((b) => b.addEventListener("click", () => {
    $$(".st").forEach((x) => x.classList.remove("active"));
    b.classList.add("active");
    hw.sort = b.dataset.sort || "";
    loadMarket();
  }));

  // 走势图：原生 SVG，无第三方依赖
  function sparkSVG(points) {
    const W = 320, H = 170, padL = 42, padR = 10, padT = 14, padB = 22;
    if (!points || !points.length) {
      return `<svg class="spark" viewBox="0 0 ${W} ${H}"><text x="${W / 2}" y="${H / 2}" fill="#8b949e"
        font-size="12" text-anchor="middle">暂无历史价格，采集积累后自动生成走势</text></svg>`;
    }
    const vals = points.map((p) => Number(p.ref));
    let min = Math.min(...vals), max = Math.max(...vals);
    if (max - min < 1) { max = max + Math.max(1, max * 0.02); min = Math.max(0, min - Math.max(1, min * 0.02)); }
    const span = max - min;
    const plotW = W - padL - padR, plotH = H - padT - padB;
    const xs = points.map((_, i) => points.length === 1 ? padL + plotW / 2 : padL + plotW * i / (points.length - 1));
    const ys = vals.map((v) => padT + plotH * (max - v) / span);
    const up = vals[vals.length - 1] > vals[0];
    const down = vals[vals.length - 1] < vals[0];
    const col = up ? "#f0524a" : down ? "#3fb950" : "#58a6ff";

    let grid = "", labels = "";
    for (let i = 0; i <= 3; i++) {
      const y = padT + plotH * i / 3;
      grid += `<line x1="${padL}" y1="${y}" x2="${W - padR}" y2="${y}" stroke="#30363d" stroke-width="0.7"/>`;
      labels += `<text x="${padL - 6}" y="${y + 4}" fill="#8b949e" font-size="9" text-anchor="end">${(max - span * i / 3).toFixed(0)}</text>`;
    }
    const line = xs.map((x, i) => `${i ? "L" : "M"}${x.toFixed(1)},${ys[i].toFixed(1)}`).join(" ");
    const area = `${line} L${xs[xs.length - 1].toFixed(1)},${padT + plotH} L${xs[0].toFixed(1)},${padT + plotH} Z`;
    const dots = xs.map((x, i) => `<circle cx="${x.toFixed(1)}" cy="${ys[i].toFixed(1)}" r="${i === xs.length - 1 ? 3.4 : 2}" fill="${col}"/>`).join("");
    const d0 = new Date(points[0].ts * 1000), d1 = new Date(points[points.length - 1].ts * 1000);
    const fmt = (d) => `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

    return `<svg class="spark" viewBox="0 0 ${W} ${H}">
      <defs><linearGradient id="g1" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="${col}" stop-opacity="0.35"/>
        <stop offset="100%" stop-color="${col}" stop-opacity="0"/>
      </linearGradient></defs>
      ${grid}${labels}
      <path d="${area}" fill="url(#g1)"/>
      <path d="${line}" fill="none" stroke="${col}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>
      ${dots}
      <text x="${padL}" y="${H - 6}" fill="#8b949e" font-size="9">${fmt(d0)}</text>
      <text x="${W - padR}" y="${H - 6}" fill="#8b949e" font-size="9" text-anchor="end">${fmt(d1)}</text>
    </svg>`;
  }

  async function openModel(m) {
    if (!m) return;
    $("#mdlTitle").textContent = ((m.brand || "") + " " + (m.model || "")).trim() || "型号详情";
    const price = m.price == null ? "暂无准确报价" : "¥" + Number(m.price).toLocaleString();
    $("#mdlBody").innerHTML = `
      <div style="font-size:12px;color:var(--gold)">${esc(m.catLabel || "")} ${esc(m.spec || "")}</div>
      <div style="font-size:26px;font-weight:800;margin-top:4px">${price}</div>
      <div style="color:var(--mut);font-size:12px;margin-top:2px">${esc(m.platform || "")} · ${esc(m.shop || "")}</div>
      ${sparkSVG([{ ts: Date.now() / 1000, ref: Number(m.price) || 0 }])}
      <div id="mdlChart">加载走势…</div>`;
    $("#mdl").classList.remove("hidden");

    try {
      const r = await fetch(API + "/api/hw/history/" + encodeURIComponent(m.key));
      const j = await r.json();
      const d = j.data || {};
      const pts = d.points || [], s = d.stats || {};
      const fmtY = (v) => v > 0 ? "¥" + Number(v).toLocaleString() : "—";
      $("#mdlChart").innerHTML = `
        ${sparkSVG(pts)}
        <div class="mdl-stats">
          <div><b>${fmtY(s.min)}</b><span>历史最低</span></div>
          <div><b>${fmtY(s.max)}</b><span>历史最高</span></div>
          <div><b>${fmtY(s.avg)}</b><span>均价</span></div>
          <div><b>${s.count || 0}</b><span>样本点</span></div>
        </div>
        <button class="primary" id="mdlPost" style="width:100%;margin-top:14px">用这个型号发一条讨论</button>`;
      const mb = $("#mdlPost");
      if (mb) mb.addEventListener("click", () => {
        $("#mdl").classList.add("hidden");
        openComposer();
        const body = $("#t_body");
        if (body) body.value = `【比价】${((m.brand || "") + " " + (m.model || "")).trim()} 现在 ${price}\n`
          + `${m.platform || ""} ${m.shop || ""}\n大家觉得这个价能上车吗？`;
      });
    } catch (e) {
      $("#mdlChart").textContent = "走势加载失败";
    }
  }

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
  loadFeed();
  loadTags();
  loadCats();
  connectWS();
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
