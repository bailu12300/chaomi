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
    let n = $("#nick").value.trim();
    if (!n) n = "匿名";
    localStorage.setItem("zaoba_nick", n);
    $("#nickEcho").textContent = n;
    return n;
  }

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
  async function loadFeed() {
    try {
      const r = await fetch(API + "/api/community/feed");
      const j = await r.json();
      const list = Array.isArray(j.data) ? j.data : ((j.data && j.data.data) || []);
      const box = $("#postList");
      box.innerHTML = "";
      cards.clear();
      if (!list.length) { $("#feedEmpty").style.display = "block"; return; }
      $("#feedEmpty").style.display = "none";
      list.forEach((p) => box.appendChild(renderPost(p)));
    } catch (e) {
      toast("信息流加载失败");
    }
  }

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
      if (p.body) html += `<div class="article-body">${p.body}</div>`;
      if (p.url) html += `<a class="linkcard" href="${esc(p.url)}" target="_blank" rel="noopener"><div class="lu">查看原文 ↗</div></a>`;
    }
    if (p.tags && p.tags.length) {
      html += `<div class="tags">${p.tags.map((t) => `<span class="tagc">#${esc(t)}</span>`).join("")}</div>`;
    }
    html += `<div class="actions">
      <button class="like ${p.likes ? "liked" : ""}" data-act="like">♥ <span class="lc">${p.likes || 0}</span></button>
      <button data-act="comment">💬 <span class="cc">${p.commentCount || 0}</span></button>
    </div>`;
    el.innerHTML = html;
    el.querySelector('[data-act="like"]').addEventListener("click", (e) => doLike(p.id, e.currentTarget));
    el.querySelector('[data-act="comment"]').addEventListener("click", () => openDetail(p.id));
    cards.set(p.id, el);
    return el;
  }

  async function doLike(id, btn) {
    const r = await fetch(API + "/api/community/post/" + id + "/like", { method: "POST" });
    const j = await r.json();
    if (j.code === 0) { btn.querySelector(".lc").textContent = j.data; btn.classList.add("liked"); }
  }

  // ---------- 详情 / 评论 ----------
  let curDetailId = null;
  async function openDetail(id) {
    curDetailId = id;
    try {
      const r = await fetch(API + "/api/community/post/" + id);
      const j = await r.json();
      if (j.code !== 0) { toast("帖子不存在"); return; }
      renderDetail(j.data, j.comments || []);
      $("#detail").classList.remove("hidden");
    } catch (e) { toast("加载失败"); }
  }
  function renderDetail(p, comments) {
    const box = $("#detailBody");
    let html = "";
    const initial = (p.author || "匿")[0];
    html += `<div class="head"><div class="avatar">${esc(initial)}</div>
      <div><div class="name">${esc(p.author || "匿名")}</div><div class="time">${timeAgo(p.createdAt)}</div></div></div>`;
    if (p.body) html += `<div class="body">${esc(p.body)}</div>`;
    if (p.type === "article" && p.body) html += `<div class="article-body">${p.body}</div>`;
    if (p.url) html += `<a class="linkcard" href="${esc(p.url)}" target="_blank" rel="noopener"><div class="lu">原文 ↗</div></a>`;
    html += `<div class="actions"><button class="like" data-act="like">♥ <span class="lc">${p.likes || 0}</span></button></div>`;
    html += `<div class="cmt"><h4>评论 ${comments.length}</h4>`;
    comments.forEach((c) => { html += `<div class="c"><span class="cn">${esc(c.author || "匿名")}</span><span>${esc(c.body)}</span></div>`; });
    html += `<div class="cmt-form"><input id="cmtInput" placeholder="写下评论…"><button class="primary" id="cmtSend">发送</button></div></div>`;
    box.innerHTML = html;
    box.querySelector('[data-act="like"]').addEventListener("click", async (e) => {
      const rr = await fetch(API + "/api/community/post/" + p.id + "/like", { method: "POST" });
      const jj = await rr.json(); if (jj.code === 0) e.currentTarget.querySelector(".lc").textContent = jj.data;
    });
    box.querySelector("#cmtSend").addEventListener("click", () => sendComment(p.id));
  }
  async function sendComment(id) {
    const inp = $("#cmtInput"); const body = inp.value.trim(); if (!body) return;
    const r = await fetch(API + "/api/community/post/" + id + "/comments", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ author: nick(), body }),
    });
    const j = await r.json();
    if (j.code === 0) { inp.value = ""; openDetail(id); bumpComment(id); }
  }
  function bumpComment(id) {
    const el = cards.get(id); if (!el) return;
    const cc = el.querySelector(".cc"); if (cc) cc.textContent = (parseInt(cc.textContent || "0") + 1);
  }

  // ---------- 发布器 ----------
  let curType = "text";
  function openComposer() { $("#composer").classList.remove("hidden"); }
  $$(".type-tabs .tt").forEach((b) => b.addEventListener("click", () => {
    $$(".type-tabs .tt").forEach((x) => x.classList.remove("active"));
    b.classList.add("active"); curType = b.dataset.type;
    $$(".pane").forEach((p) => p.classList.toggle("hidden", p.dataset.pane !== curType));
  }));
  $$("[data-close]").forEach((b) => b.addEventListener("click", () => {
    $("#composer").classList.add("hidden"); $("#detail").classList.add("hidden");
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
    const r = await fetch(API + "/api/community/posts", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload),
    });
    const j = await r.json();
    if (j.code === 0) {
      toast("已发布");
      $("#composer").classList.add("hidden");
      $("#t_body").value = ""; $("#i_url").value = ""; $("#i_title").value = "";
      $("#l_url").value = ""; $("#l_title").value = ""; $("#l_cover").value = ""; $("#l_body").value = "";
      pendingArticle = null; $("#a_preview").classList.add("hidden"); $("#a_url").value = "";
      switchFeed();
      const box = $("#postList"); box.insertBefore(renderPost(j.data), box.firstChild);
      $("#feedEmpty").style.display = "none";
    } else toast("发布失败：" + (j.error || ""));
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
      const a = j.data; pendingArticle = { title: a.title, body: a.content, cover: a.cover, url: a.url, images: a.images || [] };
      const pv = $("#a_preview"); pv.classList.remove("hidden");
      pv.innerHTML = `<h3>${esc(a.title)}</h3>${a.author ? `<div class="time">作者：${esc(a.author)}</div>` : ""}
        ${a.cover ? `<img class="cover" src="${esc(a.cover)}">` : ""}
        <div class="ap-content">${a.content}</div>
        <div style="margin-top:8px"><button class="primary" id="a_use">转译为社区帖子</button></div>`;
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
  const saved = localStorage.getItem("zaoba_nick");
  if (saved) $("#nick").value = saved;
  $("#nick").addEventListener("input", nick);
  nick();
  loadFeed();
  loadCats();
  connectWS();
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("assets/sw.js").catch(() => {});
  }
})();
