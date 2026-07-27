import { api } from '@/api/client'

const TOKEN_CACHE = 'pigeon-auth-v1'
const TOKEN_URL = '/__session-token'

function urlBase64ToUint8Array(base64: string): Uint8Array {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4)
  const normalized = (base64 + padding).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(normalized)
  const output = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i += 1) output[i] = raw.charCodeAt(i)
  return output
}

export function pushSupported(): boolean {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
}

export async function registerServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (!('serviceWorker' in navigator)) return null
  try {
    return await navigator.serviceWorker.register('/sw.js', { scope: '/' })
  } catch {
    return null
  }
}

export async function shareTokenWithWorker(token: string | null): Promise<void> {
  if (!('caches' in window)) return
  const cache = await caches.open(TOKEN_CACHE)
  if (!token) {
    await cache.delete(TOKEN_URL)
    return
  }
  await cache.put(TOKEN_URL, new Response(token))
}

export async function pushEnabled(): Promise<boolean> {
  if (!pushSupported()) return false
  const registration = await navigator.serviceWorker.getRegistration()
  const subscription = await registration?.pushManager.getSubscription()
  return !!subscription && Notification.permission === 'granted'
}

export async function enablePush(): Promise<boolean> {
  if (!pushSupported()) return false
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') return false

  const { key } = await api.webPushKey()
  if (!key) return false

  const registration = (await navigator.serviceWorker.getRegistration()) ?? (await registerServiceWorker())
  if (!registration) return false
  await navigator.serviceWorker.ready

  const existing = await registration.pushManager.getSubscription()
  const subscription =
    existing ??
    (await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(key) as unknown as BufferSource,
    }))

  const json = subscription.toJSON() as { endpoint?: string; keys?: { p256dh?: string; auth?: string } }
  if (!json.endpoint || !json.keys?.p256dh || !json.keys?.auth) return false
  await api.subscribeWebPush({
    endpoint: json.endpoint,
    keys: { p256dh: json.keys.p256dh, auth: json.keys.auth },
  })
  return true
}

export async function disablePush(): Promise<void> {
  if (!pushSupported()) return
  const registration = await navigator.serviceWorker.getRegistration()
  const subscription = await registration?.pushManager.getSubscription()
  if (!subscription) return
  const endpoint = subscription.endpoint
  await subscription.unsubscribe().catch(() => undefined)
  await api.unsubscribeWebPush(endpoint).catch(() => undefined)
}
