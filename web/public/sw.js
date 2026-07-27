const CACHE_NAME = 'pigeon-web-v1'
const TOKEN_CACHE = 'pigeon-auth-v1'
const TOKEN_URL = '/__session-token'
const API = 'https://api.pigeonsms.aldi.best'

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

async function sessionToken() {
  const cache = await caches.open(TOKEN_CACHE)
  const stored = await cache.match(TOKEN_URL)
  if (!stored) return null
  const value = await stored.text()
  return value || null
}

async function latestNotification() {
  const token = await sessionToken()
  if (!token) return null
  const res = await fetch(`${API}/notifications?limit=1`, {
    headers: { authorization: `Bearer ${token}` },
  })
  if (!res.ok) return null
  const body = await res.json().catch(() => null)
  const item = body?.notifications?.[0]
  if (!item) return null
  return {
    title: item.title || 'pigeonsms',
    body: item.body || '',
    channelId: item.channel_id || null,
    id: item.id,
  }
}

self.addEventListener('push', (event) => {
  event.waitUntil(
    (async () => {
      const focused = (await self.clients.matchAll({ type: 'window' })).some((c) => c.focused)
      if (focused) return
      const item = await latestNotification()
      await self.registration.showNotification(item?.title ?? 'pigeonsms', {
        body: item?.body ?? 'new activity',
        icon: '/icon-192.png',
        badge: '/icon-192.png',
        tag: item?.channelId ?? 'pigeonsms',
        renotify: true,
        data: { channelId: item?.channelId ?? null },
      })
    })(),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const channelId = event.notification.data?.channelId
  const target = channelId ? `/chat/${channelId}` : '/'
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      for (const client of windows) {
        if ('focus' in client) {
          await client.focus()
          client.postMessage({ type: 'navigate', to: target })
          return
        }
      }
      await self.clients.openWindow(target)
    })(),
  )
})

self.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return
  event.respondWith(
    caches.open(CACHE_NAME).then(async (cache) => {
      const cached = await cache.match(request)
      const network = fetch(request)
        .then((response) => {
          if (response.ok && request.url.includes('/assets/')) cache.put(request, response.clone())
          return response
        })
        .catch(() => cached)
      return cached ?? network
    }),
  )
})
