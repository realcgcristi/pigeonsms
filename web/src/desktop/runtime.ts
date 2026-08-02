const allowedRoute =
  /^\/(?:call|chat|forum|friends|nest|notifications|pair|profile|search|settings|spaces|thread|threads|you)(?:\/|$)/;

export function isDesktopApp(): boolean {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;
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

export async function initializeDesktopRuntime(): Promise<void> {
  if (!isDesktopApp()) return;

  const [{ getCurrent, onOpenUrl }, { openUrl }] = await Promise.all([
    import('@tauri-apps/plugin-deep-link'),
    import('@tauri-apps/plugin-opener'),
  ]);

  const initialUrls = await getCurrent();
  initialUrls?.forEach(openDeepLink);
  await onOpenUrl((urls) => urls.forEach(openDeepLink));

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
}
