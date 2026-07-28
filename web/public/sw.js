const CACHE_NAME = 'pigeon-web-v2'
const TOKEN_CACHE = 'pigeon-auth-v1'
const TOKEN_URL = '/__session-token'
const API = 'https://api.pigeonsms.aldi.best'

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then((cache) => cache.addAll(['/', '/manifest.webmanifest']))
      .catch(() => undefined)
      .then(() => self.skipWaiting()),
  )
})
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((key) => key !== CACHE_NAME && key !== TOKEN_CACHE).map((key) => caches.delete(key))),
      )
      .then(() => self.clients.claim()),
  )
})

async function sessionToken() {
  const cache = await caches.open(TOKEN_CACHE)
  const stored = await cache.match(TOKEN_URL)
  if (!stored) return null
  const value = await stored.text()
  return value || null
}

async function latestNotification() {
  const token = await sessionToken()
  const headers = token ? { authorization: `Bearer ${token}` } : {}
  const res = await fetch(`${API}/notifications?limit=1`, {
    headers,
    credentials: 'include',
  })
  if (!res.ok) return null
  const body = await res.json().catch(() => null)
  const item = body?.notifications?.[0]
  if (!item) return null
  return {
    title: item.title || 'pigeonsms',
    body: item.body || '',
    channelId: item.channel_id || null,
    spaceId: item.space_id || null,
    messageId: item.message_id || null,
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
        data: {
          channelId: item?.channelId ?? null,
          spaceId: item?.spaceId ?? null,
          messageId: item?.messageId ?? null,
        },
      })
    })(),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const channelId = event.notification.data?.channelId
  const search = new URLSearchParams()
  if (event.notification.data?.spaceId) search.set('space', 'true')
  if (event.notification.data?.messageId) search.set('message', event.notification.data.messageId)
  const target = channelId ? `/chat/${channelId}?${search.toString()}` : '/notifications'
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
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) caches.open(CACHE_NAME).then((cache) => cache.put('/', response.clone()))
          return response
        })
        .catch(() => caches.match('/').then((cached) => cached || Response.error())),
    )
    return
  }
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
