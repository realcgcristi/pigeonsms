const allowedRoute =
  /^\/(?:call|chat|forum|friends|nest|notifications|pair|profile|search|settings|spaces|thread|threads|you)(?:\/|$)/;

export function isDesktopApp(): boolean {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;
}

export interface DesktopUpdateInfo {
  version: string;
  currentVersion: string;
  notes?: string;
  date?: string;
}

export interface DesktopUpdateProgress {
  downloaded: number;
  total: number | null;
}

let pendingDesktopUpdate: import('@tauri-apps/plugin-updater').Update | null = null;
const desktopNotificationPreference = 'pigeon.desktop.notifications';

interface DesktopMessageDetail {
  title?: string;
  body?: string;
}

export async function checkDesktopUpdate(): Promise<DesktopUpdateInfo | null> {
  if (!isDesktopApp()) return null;
  const { check } = await import('@tauri-apps/plugin-updater');
  const next = await check({ timeout: 15_000 });
  if (pendingDesktopUpdate && pendingDesktopUpdate !== next) await pendingDesktopUpdate.close();
  pendingDesktopUpdate = next;
  return next
    ? {
        version: next.version,
        currentVersion: next.currentVersion,
        ...(next.body ? { notes: next.body } : {}),
        ...(next.date ? { date: next.date } : {}),
      }
    : null;
}

export async function installDesktopUpdate(
  onProgress?: (progress: DesktopUpdateProgress) => void,
): Promise<void> {
  if (!pendingDesktopUpdate) throw new Error('no desktop update is ready');
  let downloaded = 0;
  let total: number | null = null;
  await pendingDesktopUpdate.downloadAndInstall((event) => {
    if (event.event === 'Started') {
      total = event.data.contentLength ?? null;
      downloaded = 0;
    } else if (event.event === 'Progress') {
      downloaded += event.data.chunkLength;
    }
    onProgress?.({ downloaded, total });
  });
  const { relaunch } = await import('@tauri-apps/plugin-process');
  await relaunch();
}

export function desktopRouteFromUrl(value: string): string | null {
  try {
    const url = new URL(value);
    if (url.protocol !== 'pigeonsms:') return null;

    const host = url.hostname.toLowerCase();
    const prefix = host && host !== 'open' ? `/${host}` : '';
    const path = `${prefix}${url.pathname}`.replace(/\/{2,}/g, '/') || '/';
    if (path !== '/' && !allowedRoute.test(path)) return null;
    return `${path}${url.search}${url.hash}`;
  } catch {
    return null;
  }
}

export function prepareDesktopRuntime(): void {
  if (isDesktopApp()) document.documentElement.dataset.runtime = 'desktop';
}

function navigate(path: string): void {
  const current = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  if (path === current) return;
  window.history.pushState({}, '', path);
  window.dispatchEvent(new PopStateEvent('popstate'));
}

function openDeepLink(value: string): void {
  const route = desktopRouteFromUrl(value);
  if (route) navigate(route);
}

export async function storeDesktopSessionToken(token: string): Promise<void> {
  if (!isDesktopApp()) return;
  const { invoke } = await import('@tauri-apps/api/core');
  await invoke('store_session_token', { token });
}

export async function loadDesktopSessionToken(): Promise<string | null> {
  if (!isDesktopApp()) return null;
  const { invoke } = await import('@tauri-apps/api/core');
  return invoke<string | null>('load_session_token');
}

export async function clearDesktopSessionToken(): Promise<void> {
  if (!isDesktopApp()) return;
  const { invoke } = await import('@tauri-apps/api/core');
  await invoke('clear_session_token');
}

export async function desktopNotificationsEnabled(): Promise<boolean> {
  if (!isDesktopApp() || localStorage.getItem(desktopNotificationPreference) === 'off') return false;
  const { isPermissionGranted } = await import('@tauri-apps/plugin-notification');
  return isPermissionGranted();
}

export async function setDesktopNotificationsEnabled(enabled: boolean): Promise<boolean> {
  if (!isDesktopApp()) return false;
  if (!enabled) {
    localStorage.setItem(desktopNotificationPreference, 'off');
    return false;
  }

  const { isPermissionGranted, requestPermission } = await import('@tauri-apps/plugin-notification');
  const granted = (await isPermissionGranted()) || (await requestPermission()) === 'granted';
  localStorage.setItem(desktopNotificationPreference, granted ? 'on' : 'off');
  return granted;
}

export async function desktopAutostartEnabled(): Promise<boolean> {
  if (!isDesktopApp()) return false;
  const { isEnabled } = await import('@tauri-apps/plugin-autostart');
  return isEnabled();
}

export async function setDesktopAutostart(enabled: boolean): Promise<void> {
  if (!isDesktopApp()) return;
  const { disable, enable } = await import('@tauri-apps/plugin-autostart');
  await (enabled ? enable() : disable());
}

export async function syncDesktopUnread(count: number): Promise<void> {
  if (!isDesktopApp()) return;
  const { invoke } = await import('@tauri-apps/api/core');
  await invoke('set_unread_count', { count: Math.max(0, Math.min(Math.trunc(count), 9999)) });
}

export async function initializeDesktopRuntime(): Promise<void> {
  if (!isDesktopApp()) return;

  const [{ getCurrent, onOpenUrl }, { openUrl }, { getCurrentWindow, UserAttentionType }, shortcut, { invoke }] = await Promise.all([
    import('@tauri-apps/plugin-deep-link'),
    import('@tauri-apps/plugin-opener'),
    import('@tauri-apps/api/window'),
    import('@tauri-apps/plugin-global-shortcut'),
    import('@tauri-apps/api/core'),
  ]);
  const appWindow = getCurrentWindow();

  const initialUrls = await getCurrent();
  initialUrls?.forEach(openDeepLink);
  await onOpenUrl((urls) => urls.forEach(openDeepLink));

  try {
    if (!(await shortcut.isRegistered('CommandOrControl+Shift+P'))) {
      await shortcut.register('CommandOrControl+Shift+P', (event) => {
        if (event.state === 'Pressed') void invoke('toggle_main_window');
      });
    }
  } catch {
    undefined;
  }

  window.addEventListener('pigeon:desktop-message', (event) => {
    const detail = (event as CustomEvent<DesktopMessageDetail>).detail;
    void Promise.all([desktopNotificationsEnabled(), appWindow.isFocused()])
      .then(async ([enabled, focused]) => {
        if (!enabled || focused) return;
        const { sendNotification } = await import('@tauri-apps/plugin-notification');
        const title = String(detail?.title || 'new message').slice(0, 80);
        const body = String(detail?.body || '').replace(/\s+/g, ' ').trim().slice(0, 240);
        sendNotification({ title, ...(body ? { body } : {}) });
        await appWindow.requestUserAttention(UserAttentionType.Informational);
      })
      .catch(() => undefined);
  });

  document.addEventListener('click', (event) => {
    const target = event.target;
    if (!(target instanceof Element)) return;
    const anchor = target.closest('a');
    if (!(anchor instanceof HTMLAnchorElement) || !anchor.href) return;

    const url = new URL(anchor.href);
    const external =
      ['http:', 'https:', 'mailto:', 'tel:'].includes(url.protocol) &&
      (url.protocol !== 'https:' || url.origin !== window.location.origin);
    if (!external) return;

    event.preventDefault();
    void openUrl(anchor.href);
  });

  window.addEventListener('keydown', (event) => {
    if (event.ctrlKey && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      navigate('/search');
      return;
    }
    if (event.ctrlKey && event.key === ',') {
      event.preventDefault();
      navigate('/you');
      return;
    }
    if (event.altKey && event.key === 'ArrowLeft') {
      event.preventDefault();
      window.history.back();
    }
  });

  void checkDesktopUpdate()
    .then((update) => {
      if (update) window.dispatchEvent(new CustomEvent('pigeon:desktop-update', { detail: update }));
    })
    .catch(() => undefined);
}
