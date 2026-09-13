/* Static app assets only: no accounts, tokens, API replies, settings or games. */
const CACHE = 'mafia-static-v1';
const BASE = new URL('./', self.location.href);
const absolute = path => new URL(path, BASE).href;
const OFFLINE = absolute('offline.html');
const SHELL = ['offline.html', 'styles.css', 'fonts/vazirmatn.woff2', 'icons/icon-192.png', 'icons/favicon-32.png'];
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(SHELL.map(absolute))));
  // A new version waits for open games to close; it never reloads a live game.
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(
    keys.filter(key => key.startsWith('mafia-static-') && key !== CACHE).map(key => caches.delete(key))
  )));
});
function isStatic(url, request) {
  const path = url.pathname.slice(BASE.pathname.length);
  if (url.search || request.headers.has('Authorization')) return false;
  if (/^(api|auth|oauth|callback|session|token)(\/|\.|$)/i.test(path)) return false;
  if (['styles.css', 'boot.js', 'webApp.js', 'ai-bridge.js', 'manifest.webmanifest'].includes(path)) return true;
  if (/^(icons|fonts|composeResources)\//.test(path)) return /\.(png|webp|jpe?g|svg|woff2?|ttf|otf|cvr|bin)$/i.test(path);
  return !path.includes('/') && /\.(wasm|mjs)$/.test(path);
}
async function staticResponse(request) {
  const cache = await caches.open(CACHE);
  try {
    const response = await fetch(request);
    if (response.ok && response.type === 'basic' && !/no-store/i.test(response.headers.get('Cache-Control') || '')) {
      await cache.put(request, response.clone());
      const keys = await cache.keys();
      if (keys.length > 96) {
        const stale = keys.filter(key => !SHELL.map(absolute).includes(key.url));
        await Promise.all(stale.slice(0, keys.length - 96).map(key => cache.delete(key)));
      }
    }
    return response;
  } catch (error) {
    const cached = await cache.match(request);
    if (cached) return cached;
    throw error;
  }
}
self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== BASE.origin || !url.pathname.startsWith(BASE.pathname)) return;
  // In particular, OAuth callback URLs with query parameters never touch the cache.
  if (url.search || request.headers.has('Authorization')) return;
  if (request.mode === 'navigate') {
    if (url.pathname !== BASE.pathname && url.pathname !== new URL('index.html', BASE).pathname) return;
    event.respondWith(fetch(request).catch(async () => (await caches.match(OFFLINE)) || Response.error()));
  } else if (isStatic(url, request)) {
    event.respondWith(staticResponse(request));
  }
});
