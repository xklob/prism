// Scope includes the deployment path so multiple prism installations can coexist.
const prefix = 'prism:' + self.registration.scope + ':';
const cacheName = prefix + '__BUILD_HASH__';
const urls = new Set(/* FILES */ [].map((name) => new URL(name, self.registration.scope).href));
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(cacheName)
      .then((cache) => cache.addAll([...urls].map((url) => new Request(url, { cache: 'reload' }))))
  );
});
self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      for (const name of await caches.keys()) {
        if (name.startsWith(prefix) && name !== cacheName) await caches.delete(name);
      }
      await self.clients.claim();
    })()
  );
});
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;
  const url =
    event.request.mode === 'navigate' && event.request.url.startsWith(self.registration.scope)
      ? new URL('index.html', self.registration.scope).href
      : event.request.url;
  if (urls.has(url))
    event.respondWith(
      caches.open(cacheName).then(async (cache) => (await cache.match(url)) || fetch(event.request))
    );
});
