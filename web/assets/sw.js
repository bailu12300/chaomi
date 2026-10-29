// 超米 Service Worker —— 离线缓存应用壳
const CACHE = "chaomi-v7";
const SHELL = ["/community", "/community/assets/styles.css", "/community/assets/app.js", "/community/assets/manifest.webmanifest", "/community/assets/icon.svg"];
self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", (e) => {
  const u = e.request.url;
  if (e.request.method !== "GET") return;
  // API / WebSocket 走网络
  if (u.includes("/api/") || u.includes("/ws/")) return;
  e.respondWith(
    fetch(e.request).catch(() => caches.match(e.request).then((r) => r || caches.match("/community")))
  );
});
