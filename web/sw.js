// 超米 Service Worker —— 离线兜底（**在线时永远以网络为准**）
//
// 教训：早期用 cache-first 缓存 app.js，导致发版后用户拿不到新前端，
// 表现为「新功能点了没反应 / 列表渲染不出来」，排查很久才发现是 SW 缓存在作祟。
// 现在策略：
//   · HTML 与 JS/CSS 一律 network-first（在线拿最新，失败才回落缓存）
//   · 只有图标这类真正不变的资源才 cache-first
//   · CACHE 版本变化时清掉所有旧缓存
const CACHE = "chaomi-v20";
const SHELL = [
  "/community/",
  "/community/assets/styles.css",
  "/community/assets/app.js",
  "/community/assets/manifest.webmanifest",
  "/community/assets/icon.svg",
];

self.addEventListener("install", (e) => {
  e.waitUntil(
    caches.open(CACHE)
      .then((c) => Promise.allSettled(SHELL.map((u) => c.add(u))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// 允许页面主动要求跳过等待（发版后立即生效）
self.addEventListener("message", (e) => {
  if (e.data === "skipWaiting") self.skipWaiting();
});

function isStatic(u) {
  return /\/assets\/icon\.svg$/.test(u) ||
         /\/assets\/manifest\.webmanifest$/.test(u);
}

self.addEventListener("fetch", (e) => {
  const req = e.request;
  const u = req.url;
  if (req.method !== "GET") return;
  // API / WebSocket 不缓存
  if (u.includes("/api/") || u.includes("/ws/")) return;

  // 只处理本站页面与静态资源
  if (!u.includes("/community/")) return;

  // 真正不变的资源：缓存优先
  if (isStatic(u)) {
    e.respondWith(
      caches.match(req).then((hit) => hit || fetch(req).then((res) => {
        if (res && res.status === 200) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
        }
        return res;
      }))
    );
    return;
  }

  // HTML / JS / CSS：网络优先，失败回落缓存
  e.respondWith(
    fetch(req)
      .then((res) => {
        if (res && res.status === 200) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
        }
        return res;
      })
      .catch(() =>
        caches.match(req).then((hit) =>
          hit || (req.mode === "navigate" ? caches.match("/community/") : undefined)
        )
      )
  );
});
